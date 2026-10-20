package com.serranofp.alloy.semantics

import com.intellij.navigation.ItemPresentation
import com.intellij.openapi.application.PathManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.AdditionalLibraryRootsProvider
import com.intellij.openapi.roots.SyntheticLibrary
import com.intellij.openapi.vfs.VirtualFile
import com.serranofp.alloy.AlloyIcons
import edu.mit.csail.sdg.alloy4.Util
import java.util.jar.JarFile
import javax.swing.Icon

/**
 * Makes the library modules bundled in the analyzer's JAR a library of every project, so that they are indexed,
 * listed under External Libraries and opened as library sources (in Reader mode, if the user enables it).
 */
class AlloyLibraryRootsProvider : AdditionalLibraryRootsProvider() {
    override fun getAdditionalProjectLibraries(project: Project): Collection<SyntheticLibrary> =
        listOfNotNull(AlloyPositions.libraryRoot()?.let(::AlloyLibrary))

    // The JAR does not change while the IDE runs
    override fun getRootsToWatch(project: Project): Collection<VirtualFile> = emptyList()
}

class AlloyLibrary(private val root: VirtualFile) : SyntheticLibrary(), ItemPresentation {
    override fun getSourceRoots(): Collection<VirtualFile> = listOf(root)

    override fun getPresentableText(): String = "Alloy $version"

    override fun getIcon(unused: Boolean): Icon = AlloyIcons.FILE

    override fun equals(other: Any?): Boolean = other is AlloyLibrary && other.root == root

    override fun hashCode(): Int = root.hashCode()

    companion object {
        /**
         * The analyzer's version, such as 6.2.0. `Version.getShortversion()` is empty here, because it reads
         * only the manifest of Alloy's `dist` JAR; this applies its rule to the `Bundle-Version` of the core JAR.
         */
        val version: String by lazy {
            val jar = PathManager.getJarPathForClass(Util::class.java) ?: return@lazy ""
            val bundleVersion = runCatching { JarFile(jar).use { it.manifest?.mainAttributes?.getValue("Bundle-Version") } }
                .getOrNull() ?: return@lazy ""
            bundleVersion.substringBeforeLast('.')
        }
    }
}
