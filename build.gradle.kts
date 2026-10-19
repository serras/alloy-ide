import org.jetbrains.changelog.Changelog
import org.jetbrains.intellij.platform.gradle.IntelliJPlatformType
import org.jetbrains.intellij.platform.gradle.TestFrameworkType
import org.jetbrains.intellij.platform.gradle.tasks.PrepareSandboxTask

plugins {
    id("org.jetbrains.kotlin.jvm")
    id("org.jetbrains.changelog")
    id("org.jetbrains.intellij.platform")
    id("org.jetbrains.intellij.platform.grammarkit")
}

// Runtime classpath of the out-of-process language server, shipped in lib/server/ so that
// it never ends up on the plugin classloader (it brings its own, older lsp4j)
val alloyServer = configurations.register("alloyServer") {
    isCanBeConsumed = false
    isCanBeResolved = true
    attributes {
        attribute(Usage.USAGE_ATTRIBUTE, objects.named(Usage.JAVA_RUNTIME))
        attribute(Category.CATEGORY_ATTRIBUTE, objects.named(Category.LIBRARY))
        attribute(LibraryElements.LIBRARY_ELEMENTS_ATTRIBUTE, objects.named(LibraryElements.JAR))
        attribute(Bundling.BUNDLING_ATTRIBUTE, objects.named(Bundling.EXTERNAL))
    }
}

dependencies {
    alloyServer(libs.alloy.lsp)
    alloyServer(project(":server-shim"))

    testImplementation(libs.junit)

    intellijPlatform {
        intellijIdea("2026.2.3")
        testFramework(TestFrameworkType.Platform)

        // Add plugin dependencies for compilation here, for example:
        // bundledPlugin("com.intellij.java")
    }
}

intellijPlatform {
    pluginConfiguration {
        ideaVersion {
            sinceBuild = "262"
        }
        changeNotes = provider {
            changelog.renderItem(changelog.getOrNull(version.toString()) ?: changelog.getUnreleased(), Changelog.OutputType.HTML)
        }
    }
    pluginVerification {
        ides {
            create(IntelliJPlatformType.IntellijIdea, "2026.2.3")
        }
    }
}

val generatedLexerDir = layout.buildDirectory.dir("generated/lexer")
val generatedParserDir = layout.buildDirectory.dir("generated/parser")

tasks.generateLexer {
    sourceFile = file("src/main/grammars/Alloy.flex")
    targetRootOutputDir = generatedLexerDir
    packageName = "com.serranofp.alloy.lexer"
    purgeOldFiles = true
}

tasks.generateParser {
    sourceFile = file("src/main/grammars/Alloy.bnf")
    targetRootOutputDir = generatedParserDir
    pathToParser = "com/serranofp/alloy/parser/AlloyParser.java"
    pathToPsiRoot = "com/serranofp/alloy/psi"
    purgeOldFiles = true
}

sourceSets.main {
    java.srcDirs(generatedLexerDir, generatedParserDir)
}

tasks.compileKotlin {
    dependsOn(tasks.generateLexer, tasks.generateParser)
}

tasks.compileJava {
    dependsOn(tasks.generateLexer, tasks.generateParser)
}

// Every sandbox, not just prepareSandbox (used by buildPlugin): runIde and the other run tasks
// prepare their own (prepareSandbox_runIde, ...)
tasks.withType<PrepareSandboxTask>().configureEach {
    from(alloyServer) {
        into(pluginName.map { "$it/lib/server" })
    }
}

tasks.test {
    // Load only this plugin in tests; the full unified IDE distribution fails to start under the test
    // classpath (https://github.com/JetBrains/intellij-platform-gradle-plugin/issues/2261)
    systemProperty("idea.load.plugins.id", "com.serranofp.alloy-ide")
}
