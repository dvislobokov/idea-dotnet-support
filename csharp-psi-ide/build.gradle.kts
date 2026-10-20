import org.jetbrains.intellij.platform.gradle.TestFrameworkType

// csharp-psi-ide (CSHARP_PSI_MIGRATION.md): composed into the plugin jar by the root project; still empty.
plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.intellij.platform.module)
}

// The repositories and the IDE (IntelliJ IDEA Community, `platformIde`) come from the root build.gradle.kts.
dependencies {
    implementation(project(":csharp-psi-core"))
    implementation(project(":csharp-psi-semantic"))
    // ML completion engine (shared with idea-golang-support): n-gram LM, ranker, neural inference + native kernels in its jar
    implementation(project(":ml-core"))
    intellijPlatform {
        testFramework(TestFrameworkType.Platform)
    }
    testImplementation(libs.junit)
    testImplementation(libs.opentest4j)
}
