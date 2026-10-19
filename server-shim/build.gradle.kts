plugins {
    java
}

java {
    // Same major version as the JBR bundled with IntelliJ IDEA 2026.2, which runs the server
    toolchain {
        languageVersion = JavaLanguageVersion.of(25)
    }
}

dependencies {
    implementation(libs.alloy.lsp)
    testImplementation(libs.junit)
}

tasks.test {
    useJUnit()
    systemProperty("java.util.prefs.PreferencesFactory", "com.serranofp.alloy.server.IsolatedPreferencesFactory")
    systemProperty("alloy.ide.pref.SkolemDepth3", "3")
    // Booleans use the analyzer's own encoding, y/n
    systemProperty("alloy.ide.pref.NoOverflow", "n")
    // Lets the test read the platform store to check that it is left alone
    jvmArgs("--add-opens", "java.prefs/java.util.prefs=ALL-UNNAMED")
}
