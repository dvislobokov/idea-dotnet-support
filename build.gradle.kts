import org.jetbrains.intellij.platform.gradle.TestFrameworkType
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension
import org.jetbrains.kotlin.gradle.dsl.KotlinVersion

plugins {
    id("org.jetbrains.kotlin.jvm") version "2.3.21"
    id("org.jetbrains.intellij.platform") version "2.19.0"
    // Applied by the csharp-psi modules (csharp-psi-core, csharp-psi-semantic, csharp-psi-ide); versions in gradle/libs.versions.toml.
    alias(libs.plugins.intellij.platform.module) apply false
}

group = providers.gradleProperty("pluginGroup").get()
version = providers.gradleProperty("pluginVersion").get()

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

dependencies {
    intellijPlatform {
        val localIde = providers.gradleProperty("localIdePath").orNull
        if (localIde != null && file(localIde).exists()) {
            local(localIde)
        } else {
            intellijIdea(providers.gradleProperty("platformVersion"))
        }
        testFramework(TestFrameworkType.Platform)
        // JSON (a plugin since 2024.3): only the content module io.github.dotnetsupport.jsonschema needs it, see its descriptor
        bundledPlugin("com.intellij.modules.json")
        // The native C# PSI (../csharp-psi; CSHARP_PSI_MIGRATION.md, step 1), empty until step 7: composed, so the classes go into the main
        // jar, which the main descriptor loads, and their META-INF/csharp-psi-*.xml are xi:included by plugin.xml. The content module
        // io.github.dotnetsupport.roslyn sees these classes (its loader has the main one as a parent), never the other way round.
        pluginComposedModule(implementation(project(":csharp-psi-core")))
        pluginComposedModule(implementation(project(":csharp-psi-semantic")))
        pluginComposedModule(implementation(project(":csharp-psi-ide")))
    }
    testImplementation("junit:junit:4.13.2")
}

java {
    sourceCompatibility = JavaVersion.VERSION_21
    targetCompatibility = JavaVersion.VERSION_21
}

kotlin {
    compilerOptions {
        jvmTarget = JvmTarget.JVM_21
        // The platform bundles its own Kotlin stdlib (2.3.20 in 2026.1), don't use newer API.
        apiVersion = KotlinVersion.KOTLIN_2_3
        languageVersion = KotlinVersion.KOTLIN_2_3
    }
}

// The csharp-psi modules compile like the plugin: Java 21 bytecode, Kotlin API 2.3 (the stdlib of the platform).
subprojects {
    plugins.withId("org.jetbrains.kotlin.jvm") {
        extensions.configure<JavaPluginExtension> {
            sourceCompatibility = JavaVersion.VERSION_21
            targetCompatibility = JavaVersion.VERSION_21
        }
        extensions.configure<KotlinJvmProjectExtension> {
            compilerOptions {
                jvmTarget = JvmTarget.JVM_21
                apiVersion = KotlinVersion.KOTLIN_2_3
                languageVersion = KotlinVersion.KOTLIN_2_3
            }
        }
    }
}

// `./gradlew runIdeForUiTests`: a sandbox IDE with the plugin and the Remote Robot server (https://github.com/JetBrains/intellij-ui-test-robot)
// on http://127.0.0.1:8583, for driving the UI from outside: component tree, clicks, actions, screenshots. See tools/ui-robot.
// `-ProbotPort=N` moves it (with ROBOT_PORT=N for robot.py): two worktrees can each run a sandbox.
// The robot-server plugin is the one thing the build downloads (from the JetBrains plugin repository), and only for this task.
val runIdeForUiTests by intellijPlatformTesting.runIde.registering {
    task {
        jvmArgumentProviders += CommandLineArgumentProvider {
            listOf(
                "-Drobot-server.port=" + providers.gradleProperty("robotPort").getOrElse("8583"),
                "-Dide.mac.message.dialogs.as.sheets=false",
                "-Djb.privacy.policy.text=<!--999.999-->",
                "-Djb.consents.confirmation.enabled=false",
                "-Dide.show.tips.on.startup.default.value=false",
                "-Didea.trust.all.projects=true",
            )
        }
    }
    plugins {
        robotServerPlugin()
    }

}

// The page about the plugin has one source, docs/demo.html (opened from the repository for demos); the plugin carries it as
// welcome/index.html and shows it in an editor tab after the installation (WelcomePage).
tasks.processResources {
    from("docs/demo.html") {
        into("welcome")
        rename { "index.html" }
    }
    // the documentation, the second page of the same tab
    from("docs/guide.html") {
        into("welcome")
    }
    // The indexer of assemblies goes as its source: the plugin builds it on the machine of the user, with the SDK that is there
    // (IndexerTool). One source, indexer/ of the repository.
    from("indexer") {
        include("Program.cs", "AssemblyIndexer.csproj")
        into("indexer")
    }
    // the watcher of allocations, the same way
    from("allocwatch") {
        include("Program.cs", "AllocWatch.csproj")
        into("allocwatch")
    }
    // and the calculator of code metrics
    from("metrics") {
        include("Program.cs", "CodeMetrics.csproj")
        into("metrics")
    }
    // and the logger of live test results, loaded by `dotnet test` (testing/LiveTestEvents.kt)
    from("testlogger") {
        include("TestLogger.cs", "DotNetSupport.TestLogger.csproj")
        into("testlogger")
    }
    // The helpers that stay running (cli/HelperConnection), helpers/<name>/: their sources, and next to them a copy of the protocol
    // they share, which their projects link from helpers/protocol in the repository.
    file("helpers").listFiles { it.isDirectory && it.name != "protocol" }.orEmpty().forEach { helper ->
        from(helper) {
            include("*.cs", "*.csproj")
            into(helper.name)
        }
        from("helpers/protocol") {
            include("Protocol.cs")
            into(helper.name)
        }
    }
}

// CHANGELOG.md → <change-notes> (Plugins → What's New): the section of the current version and the older ones. Every feature is a new
// version 0.1.x with its own section, so a version without one is a mistake and fails the build.
fun changeNotesHtml(markdown: String, version: String): String {
    val sections = markdown.split(Regex("(?m)^## ")).drop(1).map { it.substringBefore('\n').trim() to it.substringAfter('\n') }
    if (sections.none { it.first == version }) throw GradleException("CHANGELOG.md has no section '## $version' for pluginVersion = $version")
    fun inline(text: String) = text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
        // the HTML of Swing drops a plain space in front of <code>
        .replace(Regex(" `([^`]+)`"), "&nbsp;`$1`").replace(Regex("`([^`]+)`"), "<code>$1</code>").replace(Regex("\\*\\*([^*]+)\\*\\*"), "<b>$1</b>")
    return sections.dropWhile { it.first != version }.joinToString("") { (title, body) ->
        val html = StringBuilder("<h3>${inline(title)}</h3>")
        var inList = false
        for (line in body.lines()) {
            when {
                line.startsWith("- ") -> { if (!inList) html.append("<ul>"); inList = true; html.append("<li>").append(inline(line.removePrefix("- "))) }
                line.startsWith("  ") && inList -> html.append(' ').append(inline(line.trim()))
                line.isBlank() -> { if (inList) html.append("</ul>"); inList = false }
                else -> { if (inList) html.append("</ul>"); inList = false; html.append("<p>").append(inline(line)).append("</p>") }
            }
        }
        if (inList) html.append("</ul>")
        html.toString()
    }
}

intellijPlatform {
    buildSearchableOptions = false
    pluginConfiguration {
        // computed while configuring: a lambda of the script can't go into the configuration cache, the file read is still its input
        changeNotes = changeNotesHtml(providers.fileContents(layout.projectDirectory.file("CHANGELOG.md")).asText.get(), providers.gradleProperty("pluginVersion").get())
        ideaVersion {
            // 2026.1: the first platform with the DAP module (intellij.platform.dap) the debugger is going to be built on
            sinceBuild = "261"
            untilBuild = provider { null }
        }
    }
}
