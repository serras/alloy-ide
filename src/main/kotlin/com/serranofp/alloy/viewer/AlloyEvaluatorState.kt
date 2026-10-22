package com.serranofp.alloy.viewer

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.BaseState
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.SimplePersistentStateComponent
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.StoragePathMacros
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.openapi.vfs.newvfs.BulkFileListener
import com.intellij.openapi.vfs.newvfs.events.VFileDeleteEvent
import com.intellij.openapi.vfs.newvfs.events.VFileEvent
import com.intellij.openapi.vfs.newvfs.events.VFileMoveEvent
import com.intellij.openapi.vfs.newvfs.events.VFilePropertyChangeEvent

/** The watches of one model file, keyed by its URL. */
class FileWatches : BaseState() {
    var url by string()
    var expressions by list<String>()
}

class AlloyEvaluatorWatches : BaseState() {
    var files by list<FileWatches>()
    fun increment() { this.incrementModificationCount() }
}

/**
 * The evaluator's watches, per model file, in the project's workspace: they are expressions over one model's
 * names, and are not shared through VCS, as the debugger's watches are not. The watches follow their file, or a
 * directory above it, when it is moved or renamed, and are forgotten when it is deleted. They are not pruned when
 * the state is loaded, as a file may be missing then only for a while (an unmounted path, a VFS not refreshed yet).
 */
@Service(Service.Level.PROJECT)
@State(name = "AlloyEvaluator", storages = [Storage(StoragePathMacros.WORKSPACE_FILE)])
class AlloyEvaluatorState : SimplePersistentStateComponent<AlloyEvaluatorWatches>(AlloyEvaluatorWatches()), Disposable {
    fun watches(file: VirtualFile): List<String> = state.files.firstOrNull { it.url == file.url }?.expressions.orEmpty().toList()

    fun setWatches(file: VirtualFile, expressions: List<String>) {
        val files = state.files
        files.removeIf { it.url == file.url }
        if (expressions.isNotEmpty()) files += FileWatches().also {
            it.url = file.url
            it.expressions = expressions.toMutableList()
        }
        state.increment()
    }

    /** Called by the platform for the service, once; instances made in tests do not follow the files. */
    override fun initializeComponent() {
        ApplicationManager.getApplication().messageBus.connect(this).subscribe(VirtualFileManager.VFS_CHANGES, object : BulkFileListener {
            override fun after(events: List<VFileEvent>) = events.forEach(::fileChanged)
        })
    }

    private fun fileChanged(event: VFileEvent) {
        val protocol = event.fileSystem.protocol
        when {
            event is VFileMoveEvent -> moved(VirtualFileManager.constructUrl(protocol, event.oldPath), event.file.url)
            event is VFilePropertyChangeEvent && event.propertyName == VirtualFile.PROP_NAME ->
                moved(VirtualFileManager.constructUrl(protocol, event.oldPath), event.file.url)
            event is VFileDeleteEvent -> {
                val url = VirtualFileManager.constructUrl(protocol, event.path)
                if (state.files.removeIf { it.url?.isUnder(url) == true }) state.increment()
            }
        }
    }

    /** The watches of the file at [from], or of every file under that directory, are now at [to]. */
    private fun moved(from: String, to: String) {
        var changed = false
        for (entry in state.files) {
            val url = entry.url ?: continue
            if (!url.isUnder(from)) continue
            entry.url = to + url.removePrefix(from)
            changed = true
        }
        if (changed) state.increment()
    }

    private fun String.isUnder(url: String) = this == url || startsWith("$url/")

    override fun dispose() {}

    companion object {
        fun getInstance(project: Project): AlloyEvaluatorState = project.service()
    }
}
