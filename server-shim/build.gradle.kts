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
}
