import org.jetbrains.intellij.platform.gradle.IntelliJPlatformType
import org.jetbrains.intellij.platform.gradle.TestFrameworkType
import org.jetbrains.intellij.platform.gradle.tasks.GenerateLexerTask

// csharp-psi-core (CSHARP_PSI_MIGRATION.md, docs/csharp-psi): the lexer, the port of Roslyn's LanguageParser and the PSI generated from
// Syntax.xml. Composed into the plugin jar by the root project. Its tests, corpus gates and benchmarks run here (docs/csharp-psi/TESTING.md).
plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.intellij.platform.module)
    alias(libs.plugins.intellij.platform.grammarkit)
}

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

// Like the root project: the installed IDE from localIdePath when it exists (nothing is downloaded), otherwise IntelliJ IDEA of platformVersion.
val localIde: File? = providers.gradleProperty("localIdePath").orNull?.let(::file)?.takeIf { it.exists() }

dependencies {
    intellijPlatform {
        if (localIde != null) local(localIde) else intellijIdea(providers.gradleProperty("platformVersion"))
        testFramework(TestFrameworkType.Platform)
    }
    testImplementation(libs.junit)
    testImplementation(libs.opentest4j)
}

// --- JFlex: src/main/grammar/CSharp.flex -> _CSharpLexer (build/generated, never committed) ---------------------

val generateLexer = tasks.named<GenerateLexerTask>("generateLexer") {
    sourceFile = layout.projectDirectory.file("src/main/grammar/CSharp.flex")
    purgeOldFiles = true
}

sourceSets {
    main {
        java.srcDir(generateLexer.flatMap { it.targetRootOutputDir })
    }
}

tasks.named("compileKotlin") { dependsOn(generateLexer) }
tasks.named("compileJava") { dependsOn(generateLexer) }
tasks.matching { it.name.endsWith("ourcesJar") }.configureEach { dependsOn(generateLexer) }

// --- Tests ------------------------------------------------------------------------------------
// `test`: fast tests. `corpusTest` (*CorpusTest): gates over .corpus/ against tools/csharp-psi/roslyndump. `benchmark` (*Benchmark).

val testDataDir = layout.projectDirectory.dir("testData").asFile
val repoRootDir = rootProject.layout.projectDirectory.asFile
val corpusDir = File(repoRootDir, ".corpus")

tasks.withType<Test>().configureEach {
    systemProperty("csharppsi.testDataPath", testDataDir.absolutePath)
    systemProperty("csharppsi.repoRoot", repoRootDir.absolutePath)
    systemProperty("csharppsi.corpus", corpusDir.absolutePath)
    // Pass-through of every csharppsi.* property given to Gradle as -D or -P (-P wins), e.g.
    // -Dcsharppsi.updateGoldens=true, -Pcsharppsi.roslyndump=<RoslynDump.dll>, -Pcsharppsi.corpus=<dir>.
    val passThrough = providers.systemPropertiesPrefixedBy("csharppsi.").get() + providers.gradlePropertiesPrefixedBy("csharppsi.").get()
    passThrough.forEach { (key, value) -> systemProperty(key, value) }
    // The IntelliJ Platform Gradle Plugin attaches the kotlinx-coroutines debug agent to test JVMs. Its class transformer fails on some
    // platform classes and prints "JPLISAgent.c ... ASSERTION FAILED" lines; tests do not need coroutine debug probes, so the agent is
    // dropped (-Pcsharppsi.coroutinesAgent=true keeps it).
    if (!providers.gradleProperty("csharppsi.coroutinesAgent").map(String::toBoolean).getOrElse(false)) {
        doFirst {
            val test = this as Test
            val original = test.jvmArgumentProviders.toList()
            test.jvmArgumentProviders.clear()
            original.forEach { provider ->
                test.jvmArgumentProviders.add(CommandLineArgumentProvider {
                    provider.asArguments().filterNot { it.startsWith("-javaagent:") && "coroutines-javaagent" in it }
                })
            }
        }
    }
    // The platform class loader disables CDS for non-system classes and the JVM warns about it on every start.
    jvmArgs("-Xlog:cds=off", "-Xlog:cds+dynamic=off")
    testLogging {
        events("failed", "skipped")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}

val corpusTestPattern = "*CorpusTest"
val benchmarkPattern = "*Benchmark"

tasks.test {
    failOnNoDiscoveredTests = false
    filter {
        excludeTestsMatching(corpusTestPattern)
        excludeTestsMatching(benchmarkPattern)
    }
}

intellijPlatformTesting {
    testIde {
        fun gate(name: String, text: String, pattern: String, classes: String) = register(name) {
            if (localIde != null) localPath = localIde
            else {
                type = IntelliJPlatformType.IntellijIdea
                version = providers.gradleProperty("platformVersion")
            }
            task {
                description = text
                group = "verification"
                testClassesDirs = sourceSets.test.get().output.classesDirs
                // The standard test task carries the platform test framework (ParsingTestCase etc.).
                classpath += tasks.test.get().classpath
                useJUnit()
                // Classes extending platform test bases cannot be detected by hierarchy scanning here.
                isScanForTestClasses = false
                failOnNoDiscoveredTests = false
                include(classes)
                filter {
                    includeTestsMatching(pattern)
                    isFailOnNoMatchingTests = false
                }
                shouldRunAfter(tasks.test)
                // The test task's sandbox shares directories with this one: order them when both run in one build.
                mustRunAfter("prepareTestSandbox")
                outputs.upToDateWhen { false }
                maxHeapSize = "3g"
                // Corpora have generated files above the platform's default 2.5 MB PSI limit.
                jvmArgs("-Didea.max.intellisense.filesize=20000")
                testLogging {
                    showStandardStreams = true
                }
            }
        }
        gate("corpusTest", "Runs the corpus gates (*CorpusTest): trees and tokens of .corpus/ against tools/csharp-psi/roslyndump.",
            corpusTestPattern, "**/*CorpusTest.class")
        gate("benchmark", "Runs the benchmarks (*Benchmark).", benchmarkPattern, "**/*Benchmark.class")
    }
}
