import org.jetbrains.intellij.platform.gradle.IntelliJPlatformType
import org.jetbrains.intellij.platform.gradle.TestFrameworkType
import org.jetbrains.intellij.platform.gradle.extensions.IntelliJPlatformDependenciesExtension
import org.jetbrains.intellij.platform.gradle.extensions.IntelliJPlatformRepositoriesExtension
import org.jetbrains.intellij.platform.gradle.extensions.IntelliJPlatformTestingExtension
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

/** The build runs on the company's Jenkins (it sets JENKINS_URL in every build): the dependencies come from its own repositories. */
fun checkJenkinsUrl(): Boolean = !providers.environmentVariable("JENKINS_URL").orNull.isNullOrBlank()

/**
 * The repositories of every project of the build: the root and its subprojects take them from here, so moving the build into the company's
 * network changes this one place.
 */
fun RepositoryHandler.buildRepositories() {
    if (checkJenkinsUrl()) {
        // placeholders of the internal repositories; intellij-releases carries com.jetbrains.intellij.idea:ideaIC (platformIde) and the JBR
        maven("https://nexus.example.com/repository/maven-central/")
        maven("https://nexus.example.com/repository/intellij-releases/")
        maven("https://nexus.example.com/repository/intellij-dependencies/")
    } else {
        mavenCentral()
    }
}

/** And the IntelliJ ones, for the projects of the IntelliJ Platform Gradle Plugin. */
fun RepositoryHandler.intellijRepositories() {
    val platform = (this as ExtensionAware).extensions.getByType<IntelliJPlatformRepositoriesExtension>()
    // on Jenkins: the bundled plugins of the IDE (JSON, YAML), made into this local repository by the IntelliJ Platform Gradle Plugin
    if (checkJenkinsUrl()) platform.localPlatformArtifacts() else platform.defaultRepositories()
}

repositories {
    buildRepositories()
    intellijRepositories()
}

/**
 * The IDE the plugin is built and tested against (the user's decision 2026-10-05: the public build, nothing paid): IntelliJ IDEA Community
 * (IC), never the unified IntelliJ IDEA of 2025.3+ (IU), which has the paid modules in it. `localIdePath` when that folder exists (an
 * installed IDE, or another one to check: `-PlocalIdePath=C:/Program Files/JetBrains/<IDE>`); otherwise IC of `platformVersion` from the
 * IntelliJ repository (`useInstaller = false`: `com.jetbrains.intellij.idea:ideaIC`, which JetBrains still publishes there — the installers
 * of 2025.3+ are IU only), with a JBR of its own, as that archive has none. A local IDE of product code IU fails the build.
 */
val localIde: File? = providers.gradleProperty("localIdePath").orNull?.takeIf { it.isNotBlank() }?.let(::file)?.takeIf { it.isDirectory }
val platformVersion: String = providers.gradleProperty("platformVersion").get()

localIde?.let { ide ->
    val info = listOf("product-info.json", "Contents/Resources/product-info.json").map { File(ide, it) }.firstOrNull { it.isFile }
    val code = info?.let { Regex(""""productCode"\s*:\s*"(\w+)"""").find(it.readText())?.groupValues?.get(1) }
    if (code == "IU") throw GradleException(
        "$ide is the unified IntelliJ IDEA (product code IU, with the paid modules): the plugin is built and tested on IntelliJ IDEA Community (IC). " +
            "Point localIdePath to an IC installation, or to nothing, and the build takes IC of platformVersion from the IntelliJ repository."
    )
}

/** Adds the IDE of the build to the IntelliJ Platform dependencies of a project. */
fun IntelliJPlatformDependenciesExtension.platformIde() {
    if (localIde != null) local(localIde.path)
    else {
        intellijIdeaCommunity(platformVersion) { useInstaller = false }
        jetbrainsRuntime()
    }
}

/** The same IDE for a task of `intellijPlatformTesting` (the gates and the harnesses). */
fun IntelliJPlatformTestingExtension.CommonParameters<*>.platformIde() {
    if (localIde != null) localPath = localIde
    else {
        type = IntelliJPlatformType.IntellijIdeaCommunity
        version = platformVersion
        useInstaller = false
    }
}

dependencies {
    intellijPlatform {
        platformIde()
        testFramework(TestFrameworkType.Platform)
        // JSON (a plugin since 2024.3): only the content module io.github.dotnetsupport.jsonschema needs it, see its descriptor
        bundledPlugin("com.intellij.modules.json")
        // YAML: only the content module io.github.dotnetsupport.frameworks.unityyaml needs it (the scenes and prefabs of Unity)
        bundledPlugin("org.jetbrains.plugins.yaml")
        // The native C# PSI (CSHARP_PSI_MIGRATION.md; the parser in csharp-psi-core, -semantic and -ide still empty): composed, so the classes go into the main
        // jar, which the main descriptor loads, and their META-INF/csharp-psi-*.xml are xi:included by plugin.xml. The content module
        // io.github.dotnetsupport.roslyn sees these classes (its loader has the main one as a parent), never the other way round.
        pluginComposedModule(implementation(project(":csharp-psi-core")))
        pluginComposedModule(implementation(project(":csharp-psi-semantic")))
        pluginComposedModule(implementation(project(":csharp-psi-ide")))
        // The Razor parser (RAZOR_PLAN.md, R2; razor-psi-core): composed the same way, used by the host's frameworks/razor.
        pluginComposedModule(implementation(project(":razor-psi-core")))
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

// The subprojects take the repositories and the IDE from here (buildRepositories, platformIde; their gates call `extra["platformIde"]`),
// and the csharp-psi modules compile like the plugin: Java 21 bytecode, Kotlin API 2.3 (the stdlib of the platform).
subprojects {
    repositories {
        buildRepositories()
    }
    extra["platformIde"] = { parameters: Any -> (parameters as IntelliJPlatformTestingExtension.CommonParameters<*>).platformIde() }
    plugins.withId("org.jetbrains.intellij.platform.module") {
        repositories {
            intellijRepositories()
        }
        dependencies.extensions.getByType<IntelliJPlatformDependenciesExtension>().platformIde()
    }
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
    platformIde()
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
    platformIde()
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

// `./gradlew harness` (SolutionDiagnosticsHarnessTest, CSHARP_PSI_MIGRATION.md D6e2): the checks over whole restored solutions, one task
// `harness_<name>` per solution of `-Pharness.solutions=<name>=<folder>;…` (default: the Razor corpus), each in its own sandbox with its own
// results (`build/solution-harness/<name>.txt`), one after another. With the configuration cache they run beside `test` and `semanticGate`:
// `./gradlew test semanticGate harness --continue` is the whole verification at once. `-Pharness.file=<end of a path>`: one file only.
val harnessPattern = "*SolutionDiagnosticsHarnessTest"
tasks.test {
    filter { excludeTestsMatching(harnessPattern) }
}
val harnessSolutions = (providers.gradleProperty("harness.solutions").orNull
    ?: "eshop=.corpus/razor/eshop/src;mudblazor=.corpus/razor/mudblazor/src;nopcommerce=.corpus/razor/nopcommerce/src")
    .split(';').map { it.trim() }.filter { it.isNotEmpty() }.map { it.substringBefore('=').trim() to it.substringAfter('=').trim() }
val harnessNames = harnessSolutions.map { "harness_${it.first}" }
for ((index, solution) in harnessSolutions.withIndex()) {
    val (name, folder) = solution
    intellijPlatformTesting.testIde.register("harness_$name") {
        platformIde()
        task {
            description = "The C# checks over the solution $name ($folder): SolutionDiagnosticsHarnessTest."
            group = "verification"
            testClassesDirs = sourceSets.test.get().output.classesDirs
            classpath += tasks.test.get().classpath
            useJUnit()
            isScanForTestClasses = false
            include("**/SolutionDiagnosticsHarnessTest.class")
            filter {
                includeTestsMatching(harnessPattern)
                isFailOnNoMatchingTests = false
            }
            mustRunAfter("prepareTestSandbox")
            // one solution after another: the index cache of the assemblies is shared, and three harness JVMs next to the tests are too much memory
            if (index > 0) mustRunAfter(harnessNames[index - 1])
            outputs.upToDateWhen { false }
            maxHeapSize = "3g"
            workingDir = layout.projectDirectory.asFile
            systemProperty("harness.solution", file(folder).absolutePath)
            systemProperty("harness.generated", providers.gradleProperty("harness.generated").orNull ?: "true")
            systemProperty("harness.out", layout.buildDirectory.file("solution-harness/$name.txt").get().asFile.absolutePath)
            providers.gradleProperty("harness.file").orNull?.let { systemProperty("harness.file", it) }
        }
    }
}
tasks.register("harness") {
    description = "Runs SolutionDiagnosticsHarnessTest on every solution of -Pharness.solutions, one after another."
    group = "verification"
    dependsOn(harnessNames)
}

// The edition checks (edition/DotNetEdition.kt; the user's decision 2026-10-17): in GIGA IDE without Pro the paid features are off. For the
// local development `-PeditionChecks=false` puts the resource edition/unlocked into the plugin, and the plugin works as in Pro everywhere.
// The zip keeps its name, so three things keep such a build away from the users: the publication and the signing fail here, the plugin
// says "Edition checks are off" at its start, and the release checks the zip (tools/release/check-zip.sh). Never put the flag into a
// gradle.properties: the verification of a release builds in a worktree that would read it.
val editionChecks = providers.gradleProperty("editionChecks").map { !it.equals("false", ignoreCase = true) }.getOrElse(true)
if (!editionChecks) {
    tasks.processResources { from("src/edition-unlocked/resources") }
    // a dependency, not a doFirst: signPlugin and publishPlugin skip themselves without their credentials, their dependencies still run
    val refuseUnlocked = tasks.register("refuseUnlockedBuild") {
        doLast { throw GradleException("-PeditionChecks=false makes a development build with the edition checks off: it is not published or signed") }
    }
    tasks.matching { it.name == "publishPlugin" || it.name == "signPlugin" }.configureEach { dependsOn(refuseUnlocked) }
} else {
    // processResources copies and never deletes: the marker of an earlier development build would stay in build/resources and in the jar
    val staleMarker = layout.buildDirectory.file("resources/main/edition/unlocked").get().asFile
    tasks.processResources { doLast { staleMarker.delete() } }
}

// ML completion (ML_INLINE_TASK.md): `-PmlEnabled=true` (or MLENABLED=true in the environment) puts the ML features into the plugin —
// META-INF/csharp-ml.xml (the grey-text inline provider, the weigher of the completion list, the Settings | .NET | ML completion page)
// and the models of `-Pml.models` (a directory; default ml-models/csharp) under ml/csharp/: ONE transformer chosen by `-Pml.modelSize`
// (0.1.164: `50`, the default, is cs50m-caret-ft5e5.cml, `100` is cs102m-caret-ft5e5.cml — the names `CSharpMlModels.NN_MODELS` knows) with
// its vocabulary cs-16384.bpe (required; before 0.1.159 cs31m-e2-lr2e3.cml, with `-Pml.big=true` also cs50m-e3-lr2e3.cml),
// the ranker pair e15-a.cml + e19-rank-gbdt.cml (GBDT, engine e19) (optional: without them only the grey text works) and the import
// statistics cs-imports-e20.cml (optional). The proxy ranker e15-a-rank.cml is never
// shipped. The zip gets the classifier `-ml` (50) or `-ml-100m` (100); a build without the flag has no trace of any of it.
val mlEnabled = providers.gradleProperty("mlEnabled").orElse(providers.environmentVariable("MLENABLED")).map { it.equals("true", ignoreCase = true) }.getOrElse(false)
if (mlEnabled) {
    val mlModels = providers.gradleProperty("ml.models").map { file(it) }.getOrElse(file("ml-models/csharp"))
    val mlModelSize = providers.gradleProperty("ml.modelSize").getOrElse("50")
    val nnModel = when (mlModelSize) {
        "50" -> "cs50m-caret-ft5e5.cml"
        "100" -> "cs102m-caret-ft5e5.cml"
        else -> throw GradleException("ml.modelSize must be 50 or 100, got '$mlModelSize'")
    }
    val nnFiles = listOf(nnModel, "cs-16384.bpe")
    for (name in nnFiles) check(File(mlModels, name).isFile) { "mlEnabled: $name not found in $mlModels" }
    val rankerFiles = listOf("e15-a.cml", "e19-rank-gbdt.cml").filter { File(mlModels, it).isFile }
    // the import statistics of CSharpImportStats (engine e20; optional, the plain build reads it from the model directory of the settings)
    val importsFiles = listOf("cs-imports-e20.cml").filter { File(mlModels, it).isFile }
    tasks.processResources {
        from("src/ml/resources")
        from(mlModels) {
            include(nnFiles + rankerFiles + importsFiles)
            into("ml/csharp")
        }
    }
    // the ML build is a separate file next to the plain one: idea-dotnet-support-<version>-ml.zip, or -ml-100m.zip with the 100 M network
    tasks.buildPlugin { archiveClassifier.set(if (mlModelSize == "100") "ml-100m" else "ml") }
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
    platformIde()
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
    // the package of the link with the Unity editor: the plugin writes it into Packages/ of a Unity project (frameworks/unity/UnityEditorPackage)
    from("unity-package") {
        into("unity-package")
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
