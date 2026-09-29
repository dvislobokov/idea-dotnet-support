package io.github.dotnetsupport

import io.github.dotnetsupport.msbuild.MsBuildProject
import io.github.dotnetsupport.msbuild.TestFramework
import io.github.dotnetsupport.testing.TestMode
import io.github.dotnetsupport.testing.TestingPlatform
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Tests on Microsoft.Testing.Platform: how they are recognized and how `dotnet test` is called for them. */
class TestingPlatformTest {
    private fun project(body: String) = MsBuildProject.parse("<Project Sdk=\"Microsoft.NET.Sdk\">$body</Project>")

    @Test
    fun `projects are recognized by their runner properties, packages and SDK`() {
        val vstest = project("""<ItemGroup><PackageReference Include="Microsoft.NET.Test.Sdk" Version="17.12.0" /><PackageReference Include="xunit" Version="2.9.2" /></ItemGroup>""")
        assertTrue(vstest.isTestProject); assertFalse(vstest.usesTestingPlatform); assertEquals(TestFramework.XUNIT, vstest.testFramework)
        assertEquals(TestMode.VSTEST, TestingPlatform.mode(vstest, runnerConfigured = false))

        val mstest = project("""<PropertyGroup><EnableMSTestRunner>true</EnableMSTestRunner><TestingPlatformDotnetTestSupport>true</TestingPlatformDotnetTestSupport></PropertyGroup><ItemGroup><PackageReference Include="MSTest" Version="3.8.0" /></ItemGroup>""")
        assertTrue(mstest.usesTestingPlatform); assertTrue(mstest.testingPlatformDotnetTestSupport); assertEquals(TestFramework.MSTEST, mstest.testFramework)
        assertEquals(TestMode.TESTING_PLATFORM_AFTER_SEPARATOR, TestingPlatform.mode(mstest, runnerConfigured = false))
        assertEquals(TestMode.TESTING_PLATFORM_RUNNER, TestingPlatform.mode(mstest, runnerConfigured = true))

        val xunit3 = project("""<PropertyGroup><UseMicrosoftTestingPlatformRunner>true</UseMicrosoftTestingPlatformRunner></PropertyGroup><ItemGroup><PackageReference Include="xunit.v3" Version="1.0.0" /></ItemGroup>""")
        assertTrue(xunit3.isTestProject); assertTrue(xunit3.usesTestingPlatform); assertEquals(TestFramework.XUNIT, xunit3.testFramework)
        // the runner without TestingPlatformDotnetTestSupport: `dotnet test` still goes through the VSTest bridge
        assertEquals(TestMode.VSTEST, TestingPlatform.mode(xunit3, runnerConfigured = false))

        val tunit = project("""<ItemGroup><PackageReference Include="TUnit" Version="0.25.0" /></ItemGroup>""")
        assertTrue(tunit.isTestProject); assertTrue(tunit.usesTestingPlatform); assertEquals(TestFramework.TUNIT, tunit.testFramework)
        val mstestSdk = MsBuildProject.parse("""<Project Sdk="MSTest.Sdk/3.8.0" />""")
        assertTrue(mstestSdk.isTestProject); assertTrue(mstestSdk.usesTestingPlatform); assertEquals(TestFramework.MSTEST, mstestSdk.testFramework)
        assertNull(project("").testFramework)
    }

    @Test
    fun `runner opt-in is read from global json and dotnet config`() {
        assertTrue(TestingPlatform.isRunnerInGlobalJson("""{ "sdk": { "version": "10.0.100" }, "test": { "runner": "Microsoft.Testing.Platform" } }"""))
        assertFalse(TestingPlatform.isRunnerInGlobalJson("""{ "sdk": { "version": "10.0.100" } }"""))
        assertFalse(TestingPlatform.isRunnerInGlobalJson("not json"))
        assertTrue(TestingPlatform.isRunnerInDotnetConfig("[dotnet.test.runner]\nname = \"Microsoft.Testing.Platform\"\n"))
        assertFalse(TestingPlatform.isRunnerInDotnetConfig("[other]\nname = \"Microsoft.Testing.Platform\"\n"))
    }

    @Test
    fun `arguments of every mode`() {
        val results = File("C:/tmp/results")
        val selected = listOf("-c", "Debug")
        assertEquals(
            listOf("test", "T.csproj", "-c", "Debug", "--filter", "FullyQualifiedName~A.B", "--logger", "trx;LogFileName=results.trx", "--results-directory", results.path, "--collect:XPlat Code Coverage", "--blame"),
            TestingPlatform.arguments(TestMode.VSTEST, TestFramework.XUNIT, "T.csproj", selected, "FullyQualifiedName~A.B", results, coverage = true, extra = listOf("--blame")),
        )
        assertEquals(
            listOf("test", "T.csproj", "-c", "Debug", "--", "--report-trx", "--report-trx-filename", "results.trx", "--results-directory", results.path, "--filter", "FullyQualifiedName~A.B"),
            TestingPlatform.arguments(TestMode.TESTING_PLATFORM_AFTER_SEPARATOR, TestFramework.MSTEST, "T.csproj", selected, "FullyQualifiedName~A.B", results, coverage = false, extra = emptyList()),
        )
        assertEquals(
            listOf("test", "--project", "T.csproj", "-c", "Debug", "--results-directory", results.path, "--report-xunit-trx", "--report-xunit-trx-filename", "results.trx",
                "--filter-method", "A.B.C", "--filter-class", "A.D", "--coverage", "--coverage-output-format", "cobertura", "--coverage-output", "coverage.cobertura.xml"),
            TestingPlatform.arguments(TestMode.TESTING_PLATFORM_RUNNER, TestFramework.XUNIT, "T.csproj", selected, "FullyQualifiedName~A.B.C|FullyQualifiedName~A.D.", results, coverage = true, extra = emptyList()),
        )
    }

    @Test
    fun `filters of the frameworks`() {
        assertEquals(listOf("A.B.C" to false, "A.D" to true), TestingPlatform.targets("FullyQualifiedName~A.B.C|FullyQualifiedName~A.D."))
        assertEquals(listOf("A.Divides(a: 4)" to false), TestingPlatform.targets("""FullyQualifiedName~A.Divides\(a: 4\)"""))
        assertEquals(listOf("--filter", "FullyQualifiedName~A.B.C"), TestingPlatform.filterOptions(TestFramework.MSTEST, "FullyQualifiedName~A.B.C"))
        assertEquals(listOf("--treenode-filter", "/*/*/Calc/Adds"), TestingPlatform.filterOptions(TestFramework.TUNIT, "FullyQualifiedName~Shop.Tests.Calc.Adds"))
        assertEquals(listOf("--treenode-filter", "/*/*/Calc/*"), TestingPlatform.filterOptions(TestFramework.TUNIT, "FullyQualifiedName~Shop.Tests.Calc."))
        assertEquals(listOf("--treenode-filter", "/*/*/(Calc|Orders)/(Adds|Totals)"), TestingPlatform.filterOptions(TestFramework.TUNIT, "FullyQualifiedName~Shop.Calc.Adds|FullyQualifiedName~Shop.Orders.Totals"))
        // a hand-written VSTest expression that names no test: passed on as it is
        assertEquals(listOf("--filter", "Category=Slow"), TestingPlatform.filterOptions(TestFramework.TUNIT, "Category=Slow"))
    }
}
