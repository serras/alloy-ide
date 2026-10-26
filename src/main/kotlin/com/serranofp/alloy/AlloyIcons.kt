package com.serranofp.alloy

import com.intellij.openapi.util.IconLoader
import javax.swing.Icon

object AlloyIcons {
    @JvmField
    val FILE: Icon = IconLoader.getIcon("/icons/alloy.svg", AlloyIcons::class.java)

    /** The Visualization and Table tab buttons, with their two state panes side by side or stacked. */
    @JvmField
    val VISUALIZATION_ROW: Icon = IconLoader.getIcon("/icons/visualizationRow.svg", AlloyIcons::class.java)

    @JvmField
    val VISUALIZATION_COLUMN: Icon = IconLoader.getIcon("/icons/visualizationColumn.svg", AlloyIcons::class.java)

    @JvmField
    val TABLE_ROW: Icon = IconLoader.getIcon("/icons/tableRow.svg", AlloyIcons::class.java)

    @JvmField
    val TABLE_COLUMN: Icon = IconLoader.getIcon("/icons/tableColumn.svg", AlloyIcons::class.java)

    /** The New buttons: a configuration of nodes, and the trace path strip's states and arrows, what is new in blue. */
    @JvmField
    val NEW_CONFIG: Icon = IconLoader.getIcon("/icons/newConfig.svg", AlloyIcons::class.java)

    @JvmField
    val NEW_TRACE: Icon = IconLoader.getIcon("/icons/newTrace.svg", AlloyIcons::class.java)

    @JvmField
    val NEW_INIT: Icon = IconLoader.getIcon("/icons/newInit.svg", AlloyIcons::class.java)

    @JvmField
    val NEW_FORK: Icon = IconLoader.getIcon("/icons/newFork.svg", AlloyIcons::class.java)
}
