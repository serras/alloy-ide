package com.serranofp.alloy.run.tests

import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.options.SettingsEditor
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.TextFieldWithBrowseButton
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.panel
import com.serranofp.alloy.AlloyFileType
import javax.swing.JComponent

/** The file of an *Alloy Commands* configuration. */
class AlloyCommandsSettingsEditor(project: Project) : SettingsEditor<AlloyCommandsRunConfiguration>() {
    internal val file = TextFieldWithBrowseButton().apply {
        addBrowseFolderListener(project, FileChooserDescriptorFactory.createSingleFileDescriptor(AlloyFileType).withTitle("Alloy File"))
    }

    override fun resetEditorFrom(configuration: AlloyCommandsRunConfiguration) {
        file.text = VfsUtilCore.urlToPath(configuration.fileUrl)
    }

    override fun applyEditorTo(configuration: AlloyCommandsRunConfiguration) {
        // An unchanged path keeps its URL, whose file system may not be the local one
        if (VfsUtilCore.urlToPath(configuration.fileUrl) != file.text) {
            configuration.fileUrl = if (file.text.isEmpty()) "" else VfsUtilCore.pathToUrl(file.text)
        }
    }

    override fun createEditor(): JComponent = panel {
        row("Alloy file:") { cell(file).align(AlignX.FILL) }
    }
}
