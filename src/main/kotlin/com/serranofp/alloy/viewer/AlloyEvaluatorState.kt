package com.serranofp.alloy.viewer

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

/** The watches of one model file, keyed by its URL. */
class FileWatches : BaseState() {
    var url by string()
    var expressions by list<String>()
}

class AlloyEvaluatorWatches : BaseState() {
    var files by list<FileWatches>()
}

/**
 * The evaluator's watches, per model file, in the project's workspace: they are expressions over one model's
 * names, and are not shared through VCS, as the debugger's watches are not. Files that no longer exist are
 * forgotten when the state is loaded.
 */
@Service(Service.Level.PROJECT)
@State(name = "AlloyEvaluator", storages = [Storage(StoragePathMacros.WORKSPACE_FILE)])
class AlloyEvaluatorState : SimplePersistentStateComponent<AlloyEvaluatorWatches>(AlloyEvaluatorWatches()) {
    fun watches(file: VirtualFile): List<String> = state.files.firstOrNull { it.url == file.url }?.expressions.orEmpty().toList()

    fun setWatches(file: VirtualFile, expressions: List<String>) {
        val files = state.files
        files.removeIf { it.url == file.url }
        if (expressions.isNotEmpty()) files += FileWatches().also {
            it.url = file.url
            it.expressions = expressions.toMutableList()
        }
        state.intIncrementModificationCount()
    }

    override fun loadState(state: AlloyEvaluatorWatches) {
        state.files.removeIf { entry -> entry.url?.let(VirtualFileManager.getInstance()::findFileByUrl) == null }
        super.loadState(state)
    }

    companion object {
        fun getInstance(project: Project): AlloyEvaluatorState = project.service()
    }
}
