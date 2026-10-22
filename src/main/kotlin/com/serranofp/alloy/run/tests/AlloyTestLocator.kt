package com.serranofp.alloy.run.tests

import com.intellij.execution.Location
import com.intellij.execution.PsiLocation
import com.intellij.execution.testframework.sm.runner.SMTestLocator
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.psi.PsiManager
import com.intellij.psi.search.GlobalSearchScope
import com.serranofp.alloy.psi.AlloyFile
import com.serranofp.alloy.run.AlloyCommandRef
import com.serranofp.alloy.run.AlloyCommandResultHintsProvider
import com.serranofp.alloy.run.AlloyCommands

/**
 * The location of a test node, `alloy://<file URL>#<index>:<label>`: the command is found by its label, or by its
 * position among the commands when it has none, as the gutter does.
 */
object AlloyTestLocator : SMTestLocator {
    const val PROTOCOL = "alloy"

    fun url(file: VirtualFile, ref: AlloyCommandRef): String = "$PROTOCOL://${file.url}#${AlloyCommandResultHintsProvider.Codec.encode(ref)}"

    /** The file URL and the command of the path of a location (what follows `alloy://`). */
    fun parse(path: String): Pair<String, AlloyCommandRef>? {
        val hash = path.lastIndexOf('#')
        if (hash < 0) return null
        val ref = AlloyCommandResultHintsProvider.Codec.decode(path.substring(hash + 1)) ?: return null
        return path.substring(0, hash) to ref
    }

    override fun getLocation(protocol: String, path: String, project: Project, scope: GlobalSearchScope): List<Location<*>> {
        if (protocol != PROTOCOL) return emptyList()
        val (url, ref) = parse(path) ?: return emptyList()
        val file = VirtualFileManager.getInstance().findFileByUrl(url) ?: return emptyList()
        val psiFile = PsiManager.getInstance(project).findFile(file) as? AlloyFile ?: return emptyList()
        val command = AlloyCommands.find(psiFile, ref) ?: return emptyList()
        return listOf(PsiLocation(project, command))
    }
}
