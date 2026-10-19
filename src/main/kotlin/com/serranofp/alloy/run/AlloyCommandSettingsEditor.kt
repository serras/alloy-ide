package com.serranofp.alloy.run

import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.options.SettingsEditor
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.TextFieldWithBrowseButton
import com.intellij.ui.components.JBTextField
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.panel
import com.serranofp.alloy.AlloyFileType
import javax.swing.JComponent
import javax.swing.JSpinner
import javax.swing.SpinnerNumberModel

class AlloyCommandSettingsEditor(project: Project) : SettingsEditor<AlloyCommandRunConfiguration>() {
    private val file = TextFieldWithBrowseButton().apply {
        addBrowseFolderListener(
            project,
            FileChooserDescriptorFactory.createSingleFileDescriptor(AlloyFileType).withTitle("Alloy Model"),
        )
    }
    private val label = JBTextField()
    private val position = JSpinner(SpinnerNumberModel(1, 1, Int.MAX_VALUE, 1))

    override fun resetEditorFrom(configuration: AlloyCommandRunConfiguration) {
        file.text = configuration.filePath
        label.text = configuration.command.label.orEmpty()
        position.value = configuration.command.index.coerceAtLeast(0) + 1
    }

    override fun applyEditorTo(configuration: AlloyCommandRunConfiguration) {
        configuration.filePath = file.text.trim()
        configuration.command = AlloyCommandRef(label.text.trim().ifEmpty { null }, (position.value as Int) - 1)
    }

    override fun createEditor(): JComponent = panel {
        row("Model file:") { cell(file).align(AlignX.FILL) }
        row("Command:") {
            cell(label).align(AlignX.FILL)
                .comment("Label of the run or check command, such as <code>show</code>; leave empty to use the position")
        }
        row("Position:") {
            cell(position).comment("Position of the command in the file, used when it has no label")
        }
    }
}
