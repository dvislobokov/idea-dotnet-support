import org.jetbrains.intellij.platform.gradle.TestFrameworkType
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension
import org.jetbrains.kotlin.gradle.dsl.KotlinVersion

plugins {
    id("org.jetbrains.kotlin.jvm") version "2.3.21"
    id("org.jetbrains.intellij.platform") version "2.19.0"
    // Applied by the csharp-psi modules (csharp-psi-core, csharp-psi-semantic, csharp-psi-ide); versions in gradle/libs.versions.toml.
    alias(libs.plugins.intellij.platform.module) apply false
    alias(libs.plugins.intellij.platform.grammarkit) apply false
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
        // The native C# PSI (CSHARP_PSI_MIGRATION.md; the parser in csharp-psi-core, -semantic and -ide still empty): composed, so the classes go into the main
        // jar, which the main descriptor loads, and their META-INF/csharp-psi-*.xml are xi:included by plugin.xml. The content module
        // io.github.dotnetsupport.roslyn sees these classes (its loader has the main one as a parent), never the other way round.
        pluginComposedModule(implementation(project(":csharp-psi-core")))
        pluginComposedModule(implementation(project(":csharp-psi-semantic")))
        pluginComposedModule(implementation(project(":csharp-psi-ide")))
    }
    testImplementation("junit:junit:4.13.2")
    // The ML completion engine (ml-core/, a copy of idea-ml-completion's module): the C# adapter of its ranker lives in csharp-psi-ide,
    // the models service, the weigher and the grey-text provider (ml/CSharpMl*, ML_INLINE_TASK.md) in the main module; the offline
    // export of completion lists (CSharpMlDatasetExport, task mlDataset) writes its shards. The jar is lib/ml-core.jar of the plugin.
    implementation(project(":ml-core"))
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

// `./gradlew formatOracle` (tools/csharp-psi/format-oracle.sh): the native formatter of C# (CSharpFeature.FORMATTING) against
// `dotnet format whitespace` on the playground and a sample of the corpus. It runs `dotnet`, so it is never part of `test`.
// Options: -PformatOracle.<name>=<value>, read by CSharpFormatOracle.
val formatOraclePattern = "*FormatOracle"
tasks.test {
    filter { excludeTestsMatching(formatOraclePattern) }
}
intellijPlatformTesting.testIde.register("formatOracle") {
    val localIde = providers.gradleProperty("localIdePath").orNull?.let(::file)?.takeIf { it.exists() }
    if (localIde != null) localPath = localIde
    else {
        type = org.jetbrains.intellij.platform.gradle.IntelliJPlatformType.IntellijIdea
        version = providers.gradleProperty("platformVersion")
    }
    task {
        description = "Compares the native C# formatter with dotnet format whitespace (tools/csharp-psi/format-oracle.sh)."
        group = "verification"
        testClassesDirs = sourceSets.test.get().output.classesDirs
        classpath += tasks.test.get().classpath
        useJUnit()
        isScanForTestClasses = false
        include("**/*FormatOracle.class")
        filter {
            includeTestsMatching(formatOraclePattern)
            isFailOnNoMatchingTests = false
        }
        mustRunAfter("prepareTestSandbox")
        outputs.upToDateWhen { false }
        maxHeapSize = "3g"
        systemProperty("formatOracle.repoRoot", layout.projectDirectory.asFile.absolutePath)
        providers.gradlePropertiesPrefixedBy("formatOracle.").get().forEach { (key, value) -> systemProperty(key, value) }
        testLogging {
            showStandardStreams = true
        }
    }
}

// `./gradlew semanticGate` (CSHARP_PSI_MIGRATION.md, step 11, task C0): the resolver of C# names and types against Roslyn
// (`roslyndump semantics`) on projects of the playground and libraries of the corpus, per layer and category. Runs `dotnet`: never part
// of `test`. Options: -PsemanticGate.<name>=<value>, read by CSharpSemanticGate.
val semanticGatePattern = "*SemanticGate"
tasks.test {
    filter { excludeTestsMatching(semanticGatePattern) }
}
intellijPlatformTesting.testIde.register("semanticGate") {
    val localIde = providers.gradleProperty("localIdePath").orNull?.let(::file)?.takeIf { it.exists() }
    if (localIde != null) localPath = localIde
    else {
        type = org.jetbrains.intellij.platform.gradle.IntelliJPlatformType.IntellijIdea
        version = providers.gradleProperty("platformVersion")
    }
    task {
        description = "Compares the resolver of C# names and types with Roslyn (roslyndump semantics), per layer and category."
        group = "verification"
        testClassesDirs = sourceSets.test.get().output.classesDirs
        classpath += tasks.test.get().classpath
        useJUnit()
        isScanForTestClasses = false
        include("**/*SemanticGate.class")
        filter {
            includeTestsMatching(semanticGatePattern)
            isFailOnNoMatchingTests = false
        }
        mustRunAfter("prepareTestSandbox")
        outputs.upToDateWhen { false }
        maxHeapSize = "3g"
        systemProperty("semanticGate.repoRoot", layout.projectDirectory.asFile.absolutePath)
        providers.gradlePropertiesPrefixedBy("semanticGate.").get().forEach { (key, value) -> systemProperty(key, value) }
        testLogging {
            showStandardStreams = true
        }
    }
}

// ML completion (ML_INLINE_TASK.md): `-PmlEnabled=true` (or MLENABLED=true in the environment) puts the ML features into the plugin —
// META-INF/csharp-ml.xml (the grey-text inline provider, the weigher of the completion list, the Settings | .NET | ML completion page)
// and the models of `-Pml.models` (a directory; default ml-models/csharp) under ml/csharp/: the transformer cs31m-e2-lr2e3.cml with its
// vocabulary cs-16384.bpe (required), the ranker pair e15-a.cml + e19-rank-gbdt.cml (GBDT, engine e19) (optional: without them only the grey text works), the import
// statistics cs-imports-e20.cml (optional) and,
// with `-Pml.big=true`, the big transformer cs50m-e3-lr2e3.cml (a switch on the settings page). The proxy ranker e15-a-rank.cml is never
// shipped. The zip gets the classifier `-ml`; a build without the flag has no trace of any of it.
val mlEnabled = providers.gradleProperty("mlEnabled").orElse(providers.environmentVariable("MLENABLED")).map { it.equals("true", ignoreCase = true) }.getOrElse(false)
if (mlEnabled) {
    val mlModels = providers.gradleProperty("ml.models").map { file(it) }.getOrElse(file("ml-models/csharp"))
    val mlBig = providers.gradleProperty("ml.big").map { it.equals("true", ignoreCase = true) }.getOrElse(false)
    val nnFiles = listOf("cs31m-e2-lr2e3.cml", "cs-16384.bpe")
    for (name in nnFiles) check(File(mlModels, name).isFile) { "mlEnabled: $name not found in $mlModels" }
    val rankerFiles = listOf("e15-a.cml", "e19-rank-gbdt.cml").filter { File(mlModels, it).isFile }
    // the import statistics of CSharpImportStats (engine e20; optional, the plain build reads it from the model directory of the settings)
    val importsFiles = listOf("cs-imports-e20.cml").filter { File(mlModels, it).isFile }
    val bigFiles = if (mlBig) listOf("cs50m-e3-lr2e3.cml").also { for (name in it) check(File(mlModels, name).isFile) { "ml.big: $name not found in $mlModels" } } else emptyList()
    tasks.processResources {
        from("src/ml/resources")
        from(mlModels) {
            include(nnFiles + rankerFiles + importsFiles + bigFiles)
            into("ml/csharp")
        }
    }
    // the ML build is a separate file next to the plain one: idea-dotnet-support-<version>-ml.zip
    tasks.buildPlugin { archiveClassifier.set("ml") }
}

// `./gradlew mlDataset` (ML_RANKER_EXPORT_TASK.md, ADAPTER.md §3): runs the plugin's real completion headlessly over C# repositories and
// writes one ml-core example shard per repository for the ranker of https://github.com/dvislobokov/idea-ml-completion. Not a test of
// behaviour, never part of `test`. Options: -Pml.repos=<file with repository names> -Pml.lm=<n-gram .cml> [-Pml.data=<corpus root>
// -Pml.out=<shards dir> -Pml.perFile=10 -Pml.maxFiles=120 -Pml.cache=0.3 -Pml.names=false -Pml.seed=7 -Pml.heap=6g -Pml.restore=false -Pml.projects=<dir> -Pml.snapshot=true
// -Pml.sandbox=<dir> -Pml.helpers=<dir>], read by CSharpMlDatasetExport (the last two by this task).
val mlDatasetPattern = "*MlDatasetExport"
tasks.test {
    filter { excludeTestsMatching(mlDatasetPattern) }
}
intellijPlatformTesting.testIde.register("mlDataset") {
    val localIde = providers.gradleProperty("localIdePath").orNull?.let(::file)?.takeIf { it.exists() }
    if (localIde != null) localPath = localIde
    else {
        type = org.jetbrains.intellij.platform.gradle.IntelliJPlatformType.IntellijIdea
        version = providers.gradleProperty("platformVersion")
    }
    task {
        description = "Exports ML ranker training examples (*MlDatasetExport) from C# repositories: -Pml.repos=<list> -Pml.lm=<lm.cml> [-Pml.data -Pml.out -Pml.perFile -Pml.maxFiles -Pml.cache -Pml.names -Pml.seed -Pml.heap]."
        group = "verification"
        testClassesDirs = sourceSets.test.get().output.classesDirs
        classpath += tasks.test.get().classpath
        useJUnit()
        isScanForTestClasses = false
        include("**/*MlDatasetExport.class")
        filter {
            includeTestsMatching(mlDatasetPattern)
            isFailOnNoMatchingTests = false
        }
        mustRunAfter("prepareTestSandbox")
        outputs.upToDateWhen { false }
        maxHeapSize = providers.gradleProperty("ml.heap").orNull ?: "6g"
        providers.gradlePropertiesPrefixedBy("ml.").get().forEach { (key, value) -> if (key != "ml.heap") systemProperty(key, value) }
        // -Pml.sandbox=<dir>: a persistent system/config directory instead of the sandbox of the build (the indexes of the IDE and the index
        // of assemblies survive between runs); -Pml.helpers=<dir>: the indexer and its indexes, one folder for all the workers (its own lock).
        // Added last, after the sandbox properties of the platform plugin: the last -D wins.
        val sandbox = providers.gradleProperty("ml.sandbox").orNull
        val helpers = providers.gradleProperty("ml.helpers").orNull
        jvmArgumentProviders.add(CommandLineArgumentProvider {
            listOfNotNull(
                sandbox?.let { "-Didea.system.path=$it/system" }, sandbox?.let { "-Didea.config.path=$it/config" }, sandbox?.let { "-Didea.log.path=$it/log" },
                helpers?.let { "-Ddotnet.support.root=$it" },
            )
        })
        // one broken repository must not fail the export of hundreds; corpora contain generated files above the platform's 2.5 MB PSI limit
        systemProperty("intellij.testFramework.rethrow.logged.errors", "false")
        systemProperty("idea.max.intellisense.filesize", "20000")
        testLogging {
            showStandardStreams = true
        }
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
