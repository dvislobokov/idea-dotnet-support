import org.jetbrains.intellij.platform.gradle.TestFrameworkType

// csharp-psi-ide of ../csharp-psi (CSHARP_PSI_MIGRATION.md): composed into the plugin jar by the root project; empty until step 7.
plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.intellij.platform.module)
}

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

dependencies {
    implementation(project(":csharp-psi-core"))
    implementation(project(":csharp-psi-semantic"))
    intellijPlatform {
        // Like the root project: the installed IDE from localIdePath when it exists (nothing is downloaded), otherwise IntelliJ IDEA of platformVersion.
        val localIde = providers.gradleProperty("localIdePath").orNull
        if (localIde != null && file(localIde).exists()) {
            local(localIde)
        } else {
            intellijIdea(providers.gradleProperty("platformVersion"))
        }
        testFramework(TestFrameworkType.Platform)
    }
    testImplementation(libs.junit)
    testImplementation(libs.opentest4j)
}
