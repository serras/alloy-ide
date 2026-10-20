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

// The analyzer's factories for external solvers, without the native libraries and executables that the same JAR
// carries for four platforms: JNI solvers are not offered, and external ones are only used from the PATH
val pardinusNative = configurations.register("pardinusNative") {
    isCanBeConsumed = false
    isCanBeResolved = true
    isTransitive = false
}

val pardinusNativeFactories = tasks.register<Jar>("pardinusNativeFactories") {
    archiveBaseName = "org.alloytools.pardinus.native-factories"
    archiveVersion = libs.versions.alloy
    destinationDirectory = layout.buildDirectory.dir("pardinus")
    from(pardinusNative.map { configuration -> configuration.files.map(::zipTree) }) {
        exclude("native/**")
    }
}

// The IDE provides slf4j; a second copy on the plugin classloader could clash with it
configurations.runtimeClasspath {
    exclude(group = "org.slf4j")
}

dependencies {
    // The analyzer, on the plugin classloader
    implementation(libs.alloy.core)
    implementation(libs.alloy.application)
    pardinusNative(libs.pardinus.native)
    implementation(files(pardinusNativeFactories))

    testImplementation(libs.junit)

    intellijPlatform {
        intellijIdea("2026.2.3")
        testFramework(TestFrameworkType.Platform)
        // For myFixture.testStructureView, whose component is in this module
        testBundledModule("intellij.platform.structureView")

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

// Without compatibility stubs, a class does not override the platform's deprecated interface defaults
// (such as InlayActionHandler.handleClick(Editor, ...)) just by implementing the interface
kotlin {
    compilerOptions {
        jvmDefault.set(org.jetbrains.kotlin.gradle.dsl.JvmDefaultMode.NO_COMPATIBILITY)
    }
}

tasks.compileJava {
    dependsOn(tasks.generateLexer, tasks.generateParser)
}

// SAT4J's test classes come with it as a dependency of pardinus; the plugin does not need them
tasks.withType<PrepareSandboxTask>().configureEach {
    exclude("**/org.sat4j.core-*-tests.jar")
}

tasks.test {
    // Load only this plugin in tests; the full unified IDE distribution fails to start under the test
    // classpath (https://github.com/JetBrains/intellij-platform-gradle-plugin/issues/2261)
    systemProperty("idea.load.plugins.id", "com.serranofp.alloy-ide")
}
