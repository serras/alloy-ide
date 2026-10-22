package com.serranofp.alloy.semantics

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.ControlFlowException
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Key
import com.intellij.openapi.util.UserDataHolderEx
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.psi.PsiManager
import com.intellij.psi.util.CachedValue
import com.intellij.psi.util.CachedValueProvider
import com.intellij.psi.util.CachedValuesManager
import com.intellij.util.concurrency.ThreadingAssertions
import com.serranofp.alloy.psi.AlloyFile
import com.serranofp.alloy.psi.AlloyOpenDecl
import com.serranofp.alloy.psi.moduleName
import com.serranofp.alloy.viewer.isAlloyEvaluatorFragment
import edu.mit.csail.sdg.alloy4.A4Reporter
import edu.mit.csail.sdg.alloy4.Err
import edu.mit.csail.sdg.alloy4.ErrorFatal
import edu.mit.csail.sdg.alloy4.ErrorWarning
import edu.mit.csail.sdg.alloy4.Util
import edu.mit.csail.sdg.parser.CompModule
import edu.mit.csail.sdg.parser.CompUtil
import org.jetbrains.annotations.TestOnly
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock

/**
 * The project files that were given to the analyzer: [files] keyed by the analyzer's name for them (see
 * [AlloyPositions.filename]), their [contents], and the modules that each of them opens. Library modules are
 * not among the [files], but they are among the [opens] of the project files that open them.
 */
class AlloyModules(
    val files: Map<String, VirtualFile>,
    val opens: Map<String, Set<String>>,
    val contents: Map<String, String> = emptyMap(),
) {
    private val lines = ConcurrentHashMap<String, AlloyLines>()

    /** Whether [to] is [from] or one of the modules it opens, directly or not. */
    fun reaches(from: String, to: String): Boolean {
        val seen = mutableSetOf<String>()
        val pending = ArrayDeque(listOf(from))
        while (pending.isNotEmpty()) {
            val next = pending.removeFirst()
            if (next == to) return true
            if (seen.add(next)) pending.addAll(opens[next].orEmpty())
        }
        return false
    }

    /** The lines of the [contents] of [filename], computed once: every reference converts positions with them. */
    internal fun lines(filename: String): AlloyLines? =
        contents[filename]?.let { text -> lines.computeIfAbsent(filename) { AlloyLines(text) } }
}

/** What the analyzer makes of an Alloy file, together with the project modules it opens. */
sealed interface AlloyModelResult {
    val modules: AlloyModules

    /** The model parses and type-checks. */
    class Model(val module: CompModule, val warnings: List<ErrorWarning>, override val modules: AlloyModules) : AlloyModelResult {
        private val derived = ConcurrentHashMap<Key<*>, Lazy<Any>>()

        /**
         * The data under [key] computed from this model, such as an index over its expressions: [compute] runs
         * once per model, so the data lives as long as the cached result and is shared by every caller. A
         * computation that throws (for example, because it is cancelled) is not kept.
         */
        fun <T : Any> derived(key: Key<T>, compute: (Model) -> T): T {
            val value = derived.computeIfAbsent(key) { lazy { compute(this) } }
            @Suppress("UNCHECKED_CAST")
            return value.value as T
        }
    }

    /** The analyzer stopped at a syntax or type error. */
    class Errors(val errors: List<Err>, override val modules: AlloyModules) : AlloyModelResult

    /** The analyzer failed unexpectedly, or the file is not a module, so nothing is known about the model. */
    class Unavailable(val cause: Throwable, override val modules: AlloyModules) : AlloyModelResult
}

/**
 * Whether the analyzer can make a model of this file: not the evaluator's fragment, which is an expression
 * rather than a module, nor a copy without a file of its own, such as a preview.
 */
val AlloyFile.isAnalyzable: Boolean
    get() = virtualFile != null && !isAlloyEvaluatorFragment

/**
 * Parses and type-checks each Alloy file once per version of its text. The result is cached on the file and
 * recomputed when the file or one of the modules it opens changes, or when files are created, deleted or
 * moved. Each parse sees the current text of those files, including unsaved edits; a module the analyzer
 * reads from disk instead, such as a `.md` module, is tracked by its file's modification stamp.
 *
 * A parse can be cancelled, as all work in a read action should be: a pending write action, such as the next
 * keystroke, cancels it, and nothing is cached. Only one thread parses a file at a time; the others wait for
 * its result rather than parse the same text again.
 */
@Service(Service.Level.PROJECT)
class AlloyModelService(private val project: Project) {
    private val key = Key.create<Entry>("alloy.model")

    /** The cached model of a file, and the lock held by the thread that computes it. */
    private class Entry(val model: CachedValue<AlloyModelResult>) {
        val computing = ReentrantLock()
    }

    /** `CompUtil.parseEverything_fromFile`, replaced in tests. */
    internal var parse: (A4Reporter, Map<String, String>, String) -> CompModule = CompUtil::parseEverything_fromFile
        @TestOnly set

    /**
     * Needs a read action; must not be called on the EDT, since parsing a large model takes time. A file that
     * is not [isAnalyzable] has no model.
     */
    fun model(file: AlloyFile): AlloyModelResult {
        ThreadingAssertions.assertBackgroundThread()
        ApplicationManager.getApplication().assertReadAccessAllowed()
        if (!file.isAnalyzable) return AlloyModelResult.Unavailable(IllegalArgumentException("Not an Alloy module: ${file.name}"), NO_MODULES)
        val entry = file.getUserData(key) ?: (file as UserDataHolderEx).putUserDataIfAbsent(
            key,
            Entry(CachedValuesManager.getManager(project).createCachedValue({ compute(file) }, false)),
        )
        entry.model.upToDateOrNull?.let { return it.get() }
        // Waiting for another thread's parse must not hold off a write action either
        while (!entry.computing.tryLock(WAIT_MILLIS, TimeUnit.MILLISECONDS)) ProgressManager.checkCanceled()
        try {
            return entry.model.value
        } finally {
            entry.computing.unlock()
        }
    }

    private fun compute(file: AlloyFile): CachedValueProvider.Result<AlloyModelResult> {
        val (modules, psiFiles) = collect(file)
        // The analyzer adds to it every module that it reads, including those it finds on disk
        val contents = LinkedHashMap(modules.contents)
        val result = analyze(AlloyPositions.filename(file.virtualFile), contents, modules)
        // Under the `.als` name the analyzer looked for, also when it read the `.md` file instead
        val fromDisk = (contents.keys - psiFiles.keys).filterNot(AlloyPositions::isLibrary).mapNotNull {
            val files = LocalFileSystem.getInstance()
            files.findFileByPath(it) ?: files.findFileByPath(it.removeSuffix(".als") + ".md")
        }
        return CachedValueProvider.Result.create(
            result,
            *psiFiles.values.toTypedArray(), *fromDisk.toTypedArray(), VirtualFileManager.VFS_STRUCTURE_MODIFICATIONS,
        )
    }

    /**
     * The model of [file] if it is already computed for its current text, without computing it; usable on
     * the EDT, where parsing would block the editor.
     */
    fun cachedModel(file: AlloyFile): AlloyModelResult? = file.getUserData(key)?.model?.upToDateOrNull?.get()

    /** [model] off the EDT, [cachedModel] on it. Needs a read action. */
    fun availableModel(file: AlloyFile): AlloyModelResult? =
        if (ApplicationManager.getApplication().isDispatchThread) cachedModel(file) else model(file)

    /**
     * The module that [open] in [file] opens: a project file found as the analyzer finds it, or else the
     * bundled library module of that name.
     */
    fun openedModule(file: AlloyFile, open: AlloyOpenDecl): AlloyFile? {
        val name = open.qualifiedNameList.firstOrNull()?.text ?: return null
        val project = openedFile(file, name)
        if (project != null) return project
        val library = AlloyPositions.libraryFile(libraryFilename(name)) ?: return null
        return PsiManager.getInstance(this.project).findFile(library) as? AlloyFile
    }

    /** The analyzer's name (see [AlloyPositions.filename]) for the [openedModule]. */
    fun openedFilename(file: AlloyFile, open: AlloyOpenDecl): String? {
        val name = open.qualifiedNameList.firstOrNull()?.text ?: return null
        openedFile(file, name)?.let { return AlloyPositions.filename(it.virtualFile) }
        return libraryFilename(name).takeIf { AlloyPositions.libraryFile(it) != null }
    }

    /** As `CompUtil` names a bundled library module. */
    private fun libraryFilename(name: String): String =
        (Util.jarPrefix() + "models/" + name + ".als").replace('/', File.separatorChar)

    private fun openedFile(file: AlloyFile, name: String): AlloyFile? {
        // Completion works on a copy of the file, which has no VirtualFile of its own
        val virtualFile = file.originalFile.virtualFile ?: return null
        val directory = virtualFile.parent ?: return null
        val path = openedPath(virtualFile.path, file.moduleName.orEmpty(), name) ?: return null
        return directory.fileSystem.findFileByPath(path)?.let { PsiManager.getInstance(project).findFile(it) as? AlloyFile }
    }

    /** Parses the model in [contents], to which the analyzer adds the modules it reads from elsewhere. */
    private fun analyze(filename: String, contents: LinkedHashMap<String, String>, modules: AlloyModules): AlloyModelResult {
        val warnings = mutableListOf<ErrorWarning>()
        // CompModule reports each paragraph it resolves and type-checks, which is where the time goes
        val reporter = object : A4Reporter() {
            override fun parse(msg: String?) = ProgressManager.checkCanceled()
            override fun typecheck(msg: String?) = ProgressManager.checkCanceled()
            override fun warning(msg: ErrorWarning) {
                ProgressManager.checkCanceled()
                warnings += msg
            }
        }
        return try {
            AlloyModelResult.Model(parse(reporter, contents, filename), warnings, modules)
        } catch (e: Throwable) {
            // CompUtil wraps a cancellation, as any exception it does not expect, in an ErrorFatal
            generateSequence(e) { it.cause.takeIf { cause -> cause !== it } }.firstOrNull { it is ControlFlowException }?.let { throw it }
            when (e) {
                is ErrorFatal -> unavailable(e, modules)
                is Err -> AlloyModelResult.Errors(listOf(e), modules)
                else -> unavailable(e, modules)
            }
        }
    }

    private fun unavailable(cause: Throwable, modules: AlloyModules): AlloyModelResult {
        thisLogger().warn("The Alloy analyzer failed", cause)
        return AlloyModelResult.Unavailable(cause, modules)
    }

    /** [root] and the project modules it opens, directly or not, found the way the analyzer finds them. */
    private fun collect(root: AlloyFile): Pair<AlloyModules, Map<String, AlloyFile>> {
        val psiFiles = LinkedHashMap<String, AlloyFile>()
        val opens = mutableMapOf<String, Set<String>>()
        val pending = ArrayDeque(listOf(root))
        while (pending.isNotEmpty()) {
            val file = pending.removeFirst()
            val filename = AlloyPositions.filename(file.virtualFile)
            if (psiFiles.putIfAbsent(filename, file) != null) continue
            val declarations = file.children.filterIsInstance<AlloyOpenDecl>()
            opens[filename] = declarations.mapNotNullTo(LinkedHashSet()) { openedFilename(file, it) }
            pending.addAll(declarations.mapNotNull { open -> open.qualifiedNameList.firstOrNull()?.text?.let { openedFile(file, it) } })
        }
        // The text that the PSI was built from, so that ranges agree with the PSI the result is cached on
        val contents = psiFiles.mapValues { it.value.text }
        return AlloyModules(psiFiles.mapValues { it.value.virtualFile }, opens, contents) to psiFiles
    }

    companion object {
        fun getInstance(project: Project): AlloyModelService = project.service()

        private const val WAIT_MILLIS = 10L

        private val NO_MODULES = AlloyModules(emptyMap(), emptyMap())

        /**
         * Where `CompUtil` looks for the module [opened] by the module [moduleName] in [path], before falling
         * back to the bundled library: [moduleName] tells how many directories above [path] module names start.
         * `main` in `/m/main.als` opens `sub/test` from `/m/sub/test.als`; `util/x` in `/m/util/x.als` opens
         * `util/y` from `/m/util/y.als`. Paths use `/`, as in the VFS.
         */
        fun openedPath(path: String, moduleName: String, opened: String): String? {
            var a = moduleName.ifEmpty { "anything" }
            var b = opened
            while (a.isNotEmpty() && b.isNotEmpty()) {
                val i = a.indexOf('/')
                val j = b.indexOf('/')
                val headA = if (i >= 0) a.substring(0, i) else a
                val headB = if (j >= 0) b.substring(0, j) else b
                if (headA != headB || i < 0 || j < 0) {
                    return up(path, a.count { it == '/' } + 1) + "/" + b + ".als"
                }
                a = a.substring(i + 1)
                b = b.substring(j + 1)
            }
            return null
        }

        /**
         * The directory under which a module in [path] finds the modules it opens by a path-derived name: its own
         * directory, whatever the module's name. Names there start with the directory part of the module's name,
         * which `openedPath` matches before it climbs: `app/main` in `/m/main.als` opens `app/sub/y` from
         * `/m/sub/y.als`.
         */
        fun openerRoot(path: String): String = path.substringBeforeLast('/', "")

        /**
         * The name under which the module [moduleName] in [opener] opens [path], derived from where [path] is,
         * or null if it is not under the [openerRoot] or that name would not lead back to it.
         */
        fun pathDerivedName(opener: String, moduleName: String, path: String): String? {
            val root = openerRoot(opener) + "/"
            if (!path.startsWith(root) || !path.endsWith(".als")) return null
            val relative = path.removePrefix(root).removeSuffix(".als")
            val name = moduleName.substringBeforeLast('/', "").let { if (it.isEmpty()) relative else "$it/$relative" }
            if (!name.split('/').all { IDENTIFIER.matches(it) }) return null
            return name.takeIf { openedPath(opener, moduleName, it) == path }
        }

        private val IDENTIFIER = Regex("[A-Za-z][A-Za-z0-9_'\"]*")

        private fun up(path: String, steps: Int): String {
            var result = path
            repeat(steps) {
                val i = result.lastIndexOf('/')
                if (i <= 0) return ""
                result = result.substring(0, i)
            }
            return result
        }
    }
}
