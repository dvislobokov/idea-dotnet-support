package io.github.dotnetsupport

import com.intellij.execution.actions.ConfigurationContext
import com.intellij.openapi.editor.markup.RangeHighlighter
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import io.github.dotnetsupport.coverage.CoberturaParser
import io.github.dotnetsupport.coverage.DotNetCoverageService
import io.github.dotnetsupport.run.DotNetCommand
import io.github.dotnetsupport.run.DotNetRunConfiguration
import io.github.dotnetsupport.testing.DotNetTestLocator
import io.github.dotnetsupport.testing.TestDiscovery
import io.github.dotnetsupport.testing.TestOutcome
import io.github.dotnetsupport.testing.TrxEventsConverter
import io.github.dotnetsupport.testing.TrxParser
import java.io.File

class TestingAndCoverageTest : BasePlatformTestCase() {
    private val testSource = """
        using Xunit;
        namespace Calc.Tests;

        public class CalculatorTests
        {
            private readonly Calculator _calculator = new();

            [Fact] public void Adds() => Assert.Equal(3, _calculator.Add(1, 2));

            [Theory]
            [InlineData(4, 2, 2)]
            [InlineData(9, 3, 3)]
            public async Task Divides(int a, int b, int r)
            {
                var items = new[] { a, b };
                Assert.Equal(r, _calculator.Div(items[0], items[1]));
            }

            [Fact(Skip = "later")] public void Skipped() { }

            private void Helper() { Adds(); }

            public class Nested
            {
                [Xunit.Fact]
                public void Inner() { }
            }
        }

        public class NotATest { public void Run() { } }
    """.trimIndent()

    // trimmed copy of a report written by `dotnet test --logger trx` (xunit, .NET SDK 10)
    private val trx = """
        <?xml version="1.0" encoding="utf-8"?>
        <TestRun id="9fc0faaa" xmlns="http://microsoft.com/schemas/VisualStudio/TeamTest/2010">
          <Results>
            <UnitTestResult testId="t1" testName="Calc.Tests.CalculatorTests.Divides(a: 4, b: 2, r: 2)" duration="00:00:00.0001814" outcome="Passed" />
            <UnitTestResult testId="t2" testName="Calc.Tests.CalculatorTests.Fails" duration="00:00:01.5000000" outcome="Failed">
              <Output>
                <StdOut>hello from test</StdOut>
                <ErrorInfo>
                  <Message>Assert.Equal() Failure: Values differ&#xD;
        Expected: 5&#xD;
        Actual:   4</Message>
                  <StackTrace>   at Calc.Tests.CalculatorTests.Fails() in C:\src\Calc.Tests\UnitTest1.cs:line 6</StackTrace>
                </ErrorInfo>
              </Output>
            </UnitTestResult>
            <UnitTestResult testId="t3" testName="Calc.Tests.CalculatorTests.Skipped" duration="00:00:00.0010000" outcome="NotExecuted">
              <Output><ErrorInfo><Message>later</Message></ErrorInfo></Output>
            </UnitTestResult>
          </Results>
          <TestDefinitions>
            <UnitTest name="Calc.Tests.CalculatorTests.Divides(a: 4, b: 2, r: 2)" id="t1"><TestMethod className="Calc.Tests.CalculatorTests" name="Divides" /></UnitTest>
            <UnitTest name="Calc.Tests.CalculatorTests.Fails" id="t2"><TestMethod className="Calc.Tests.CalculatorTests" name="Fails" /></UnitTest>
            <UnitTest name="Calc.Tests.CalculatorTests.Skipped" id="t3"><TestMethod className="Calc.Tests.CalculatorTests" name="Skipped" /></UnitTest>
          </TestDefinitions>
        </TestRun>
    """.trimIndent()

    fun testDiscovery() {
        val targets = TestDiscovery.targets(testSource)
        assertEquals(
            listOf(
                "Calc.Tests.CalculatorTests.Adds", "Calc.Tests.CalculatorTests.Divides", "Calc.Tests.CalculatorTests.Skipped",
                "Calc.Tests.CalculatorTests+Nested.Inner", "Calc.Tests.CalculatorTests", "Calc.Tests.CalculatorTests+Nested",
            ),
            targets.map { it.className + it.methodName?.let { name -> ".$name" }.orEmpty() },
        )
        // the ranges point at the names, that is where the gutter icons and "go to test" land
        assertEquals(listOf("Adds", "Divides", "Skipped", "Inner", "CalculatorTests", "Nested"), targets.map { it.nameRange.substring(testSource) })

        val method = targets.first { it.methodName == "Divides" }
        assertEquals("FullyQualifiedName~Calc.Tests.CalculatorTests.Divides", method.filter)
        assertEquals("CalculatorTests.Divides", method.displayName)
        assertEquals("FullyQualifiedName~Calc.Tests.CalculatorTests.", targets.first { it.methodName == null }.filter)

        assertEquals(emptyList<Any>(), TestDiscovery.targets("class A { void Fact() { var x = items[Fact(1)]; } }"))
        // block namespaces nest
        assertEquals(listOf("A.B.T.M", "A.B.T"), TestDiscovery.targets("namespace A { namespace B { class T { [Test] public void M() {} } } }").map { it.className + it.methodName?.let { n -> ".$n" }.orEmpty() })
    }

    fun testTrxReport() {
        val results = TrxParser.parse(trx)
        assertEquals(listOf("Divides(a: 4, b: 2, r: 2)", "Fails", "Skipped"), results.map { it.displayName })
        assertEquals(listOf(TestOutcome.PASSED, TestOutcome.FAILED, TestOutcome.SKIPPED), results.map { it.outcome })
        assertEquals(listOf(0L, 1500L, 1L), results.map { it.durationMs })
        assertEquals("Calc.Tests.CalculatorTests.Divides", results[0].fullyQualifiedName)
        assertEquals("Assert.Equal() Failure: Values differ\nExpected: 5\nActual:   4", results[1].message)
        assertTrue(results[1].stackTrace!!.contains("UnitTest1.cs:line 6"))
        assertEquals("hello from test", results[1].stdOut)
        assertEquals("later", results[2].message)
        assertEquals(emptyList<Any>(), TrxParser.parse("<TestRun"))
    }

    fun testServiceMessages() {
        val messages = TrxEventsConverter.serviceMessages(TrxParser.parse(trx), "C:\\src\\Calc.Tests")
        assertEquals(
            listOf("testSuiteStarted", "testStarted", "testFinished", "testStarted", "testStdOut", "testFailed", "testFinished", "testStarted", "testIgnored", "testFinished", "testSuiteFinished"),
            messages.map { it.substringAfter("##teamcity[").substringBefore(' ') },
        )
        assertTrue(messages[0], messages[0].contains("name='Calc.Tests.CalculatorTests'") && messages[0].contains("dotnet-test://C:/src/Calc.Tests||Calc.Tests.CalculatorTests||'"))
        // "|" is the escape character of service messages, so it is doubled on the wire
        assertTrue(messages[1], messages[1].contains("||Calc.Tests.CalculatorTests||Divides'"))
        assertTrue(messages[5], messages[5].contains("Values differ|nExpected: 5") && messages[5].contains("details='"))
        assertTrue(messages[6], messages[6].contains("duration='1500'"))
    }

    fun testLocatorAndContextConfigurations() {
        myFixture.addFileToProject(
            "Calc.Tests/Calc.Tests.csproj",
            """<Project Sdk="Microsoft.NET.Sdk"><ItemGroup><PackageReference Include="Microsoft.NET.Test.Sdk" Version="17.0.0"/></ItemGroup></Project>""",
        )
        myFixture.addFileToProject("Calc.Tests/bin/Debug/Copy.cs", testSource)
        val file = myFixture.addFileToProject("Calc.Tests/Unit/CalculatorTests.cs", testSource)
        val root = file.virtualFile.parent.parent

        val (found, offset) = DotNetTestLocator.findSource(root, "Calc.Tests.CalculatorTests", "Divides")!!
        assertEquals("Calc.Tests/Unit/CalculatorTests.cs", found.path.substringAfter("/src/"))
        assertEquals(testSource.indexOf("Divides"), offset)
        // a test that is not in the sources (inherited) leads to its class
        assertEquals(testSource.indexOf("CalculatorTests"), DotNetTestLocator.findSource(root, "Calc.Tests.CalculatorTests", "Inherited")!!.second)

        fun configurationAt(text: String): DotNetRunConfiguration {
            val element = file.findElementAt(testSource.indexOf(text))!!
            return ConfigurationContext(element).configuration!!.configuration as DotNetRunConfiguration
        }
        val method = configurationAt("Divides")
        assertEquals("CalculatorTests.Divides", method.name)
        assertEquals(DotNetCommand.TEST, method.options.command)
        assertEquals("FullyQualifiedName~Calc.Tests.CalculatorTests.Divides", method.options.testFilter)
        assertEquals("FullyQualifiedName~Calc.Tests.CalculatorTests.", configurationAt("CalculatorTests").options.testFilter)
        // elsewhere in the file: all tests of the project
        assertNull(configurationAt("Helper").options.testFilter)

        // ▶ at the two classes with tests and at the four test methods
        myFixture.configureFromExistingVirtualFile(file.virtualFile)
        assertEquals(6, myFixture.findAllGutters().size)
    }

    fun testTestCommandLine() {
        val settings = com.intellij.execution.RunManager.getInstance(project).createConfiguration("t", io.github.dotnetsupport.run.DotNetConfigurationType.instance.factory)
        val configuration = settings.configuration as DotNetRunConfiguration
        configuration.options.apply {
            projectPath = "C:/src/Calc.Tests/Calc.Tests.csproj"
            command = DotNetCommand.TEST
            testFilter = "FullyQualifiedName~Calc.Tests.CalculatorTests."
            collectCoverage = true
            programArguments = "--no-build"
        }
        if (io.github.dotnetsupport.cli.DotNetCli.findExecutable() == null) return
        val results = File("C:/tmp/results")
        assertEquals(
            listOf(
                "test", "C:/src/Calc.Tests/Calc.Tests.csproj", "-c", "Debug", "--filter", "FullyQualifiedName~Calc.Tests.CalculatorTests.",
                "--logger", "trx;LogFileName=results.trx", "--results-directory", results.path, "--collect:XPlat Code Coverage", "--no-build",
            ),
            configuration.buildCommandLine(results).parametersList.list,
        )
    }

    fun testPassedTestsAreShownByDefault() {
        val settings = com.intellij.execution.RunManager.getInstance(project).createConfiguration("t", io.github.dotnetsupport.run.DotNetConfigurationType.instance.factory)
        val properties = io.github.dotnetsupport.testing.DotNetTestConsoleProperties(
            settings.configuration as DotNetRunConfiguration, com.intellij.execution.executors.DefaultRunExecutor.getRunExecutorInstance(), File("results"),
        )
        // otherwise a run where everything passes shows an empty tree
        assertFalse(com.intellij.execution.testframework.TestConsoleProperties.HIDE_PASSED_TESTS.value(properties))
        assertFalse(com.intellij.execution.testframework.TestConsoleProperties.HIDE_IGNORED_TEST.value(properties))
    }

    fun testCobertura() {
        // trimmed copy of a coverlet report: the file name is relative to <source>
        val xml = """
            <?xml version="1.0" encoding="utf-8"?>
            <coverage line-rate="0.83" branch-rate="0.5" version="1.9">
              <sources><source>C:\</source></sources>
              <packages>
                <package name="Calc">
                  <classes>
                    <class name="Calc.Calculator" filename="src\Calc\Class1.cs">
                      <methods>
                        <method name="Add"><lines><line number="4" hits="2" branch="False" /></lines></method>
                      </methods>
                      <lines>
                        <line number="4" hits="2" branch="False" />
                        <line number="7" hits="2" branch="True" condition-coverage="50% (1/2)" />
                        <line number="10" hits="0" branch="False" />
                      </lines>
                    </class>
                    <class name="Calc.Calculator/&lt;&gt;c" filename="src\Calc\Class1.cs">
                      <lines><line number="10" hits="3" branch="False" /><line number="12" hits="0" branch="False" /></lines>
                    </class>
                  </classes>
                </package>
              </packages>
            </coverage>
        """.trimIndent()

        val report = CoberturaParser.parse(xml) { it == "C:/src/Calc/Class1.cs" }
        val file = report.files.single()
        assertEquals("C:/src/Calc/Class1.cs", file.path)
        // lines of the methods are not counted twice; classes of one file (a lambda class here) are merged
        assertEquals(mapOf(4 to 2, 7 to 2, 10 to 3, 12 to 0), file.lines.mapValues { it.value.hits })
        assertEquals(3 to 4, file.coveredLines to file.totalLines)
        assertEquals(1 to 2, file.coveredBranches to file.totalBranches)
        assertTrue(file.lines.getValue(7).isPartial)
        assertFalse(file.lines.getValue(4).isPartial)

        val merged = report.merge(CoberturaParser.parse(xml) { it == "C:/src/Calc/Class1.cs" })
        assertEquals(4, merged.files.single().lines.getValue(4).hits)
        assertSame(io.github.dotnetsupport.coverage.CoverageReport.EMPTY, CoberturaParser.parse("<coverage"))
    }

    fun testCoverageStripesInEditor() {
        val file = myFixture.configureByText("Calculator.cs", "class Calculator\n{\n    int Add(int a, int b) => a + b;\n    int Unused() => 0;\n}\n")
        val path = file.virtualFile.path
        val xml = """<coverage><packages><package><classes><class filename="$path"><lines>
            <line number="3" hits="5" branch="False" /><line number="4" hits="0" branch="False" /><line number="99" hits="1" branch="False" />
            </lines></class></classes></package></packages></coverage>"""
        val service = DotNetCoverageService.getInstance(project)
        service.show(CoberturaParser.parse(xml) { true }, "run")

        fun stripes(): List<RangeHighlighter> = myFixture.editor.markupModel.allHighlighters.filter { it.lineMarkerRenderer != null }
        // line 99 is beyond the file: the sources have changed since the run
        assertEquals(listOf(2, 3), stripes().map { myFixture.editor.document.getLineNumber(it.startOffset) }.sorted())
        assertEquals(2 to 3, service.coverageOf(path)!!.let { it.coveredLines to it.totalLines })

        service.clear()
        assertEquals(0, stripes().size)
    }
}
