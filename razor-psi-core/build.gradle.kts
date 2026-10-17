import org.jetbrains.intellij.platform.gradle.IntelliJPlatformType
import org.jetbrains.intellij.platform.gradle.TestFrameworkType

// razor-psi-core (RAZOR_PLAN.md, R2): the port of the Razor compiler's parser (HtmlMarkupParser, CSharpCodeParser, the tokenizers) and
// its syntax tree, without IntelliJ PSI, icons or texts; the C# tokenizer runs on csharp-psi-core's lexer. Composed into the plugin jar
// by the root project (the Razor language, file type and PSI are the host's frameworks/razor). Its gates run here: `test` is the fast
// tests, `corpusTest` (*CorpusTest) diffs the trees of .corpus/razor against tools/razor/razordump and the compiler's own baselines.
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

// Like the root project: the installed IDE from localIdePath when it exists (nothing is downloaded), otherwise IntelliJ IDEA of platformVersion.
val localIde: File? = providers.gradleProperty("localIdePath").orNull?.let(::file)?.takeIf { it.exists() }

dependencies {
    implementation(project(":csharp-psi-core"))
    intellijPlatform {
        if (localIde != null) local(localIde) else intellijIdea(providers.gradleProperty("platformVersion"))
        testFramework(TestFrameworkType.Platform)
    }
    testImplementation(libs.junit)
    testImplementation(libs.opentest4j)
}

val testDataDir = layout.projectDirectory.dir("testData").asFile
val repoRootDir = rootProject.layout.projectDirectory.asFile
val corpusDir = File(repoRootDir, ".corpus")

tasks.withType<Test>().configureEach {
    systemProperty("razorpsi.testDataPath", testDataDir.absolutePath)
    systemProperty("razorpsi.repoRoot", repoRootDir.absolutePath)
    systemProperty("razorpsi.corpus", corpusDir.absolutePath)
    // Pass-through of every razorpsi.* property given to Gradle as -D or -P (-P wins): -Drazorpsi.updateGoldens=true,
    // -Prazorpsi.razordump=<RazorDump.dll>, -Prazorpsi.corpus=<dir>.
    val passThrough = providers.systemPropertiesPrefixedBy("razorpsi.").get() + providers.gradlePropertiesPrefixedBy("razorpsi.").get()
    passThrough.forEach { (key, value) -> systemProperty(key, value) }
    // As csharp-psi-core: the coroutines debug agent of the platform plugin fails on some platform classes; dropped.
    if (!providers.gradleProperty("razorpsi.coroutinesAgent").map(String::toBoolean).getOrElse(false)) {
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
    jvmArgs("-Xlog:cds=off", "-Xlog:cds+dynamic=off")
    testLogging {
        events("failed", "skipped")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}

val corpusTestPattern = "*CorpusTest"

tasks.test {
    failOnNoDiscoveredTests = false
    filter {
        excludeTestsMatching(corpusTestPattern)
    }
}

intellijPlatformTesting {
    testIde {
        register("corpusTest") {
            if (localIde != null) localPath = localIde
            else {
                type = IntelliJPlatformType.IntellijIdea
                version = providers.gradleProperty("platformVersion")
            }
            task {
                description = "Runs the corpus gates (*CorpusTest): the trees of .corpus/razor against tools/razor/razordump and the compiler's baselines."
                group = "verification"
                testClassesDirs = sourceSets.test.get().output.classesDirs
                classpath += tasks.test.get().classpath
                useJUnit()
                isScanForTestClasses = false
                failOnNoDiscoveredTests = false
                include("**/*CorpusTest.class")
                filter {
                    includeTestsMatching(corpusTestPattern)
                    isFailOnNoMatchingTests = false
                }
                shouldRunAfter(tasks.test)
                mustRunAfter("prepareTestSandbox")
                outputs.upToDateWhen { false }
                maxHeapSize = "3g"
                testLogging {
                    showStandardStreams = true
                }
            }
        }
    }
}
