package io.github.dotnetsupport

import io.github.dotnetsupport.run.TestingPlatformDebug
import junit.framework.TestCase

/** Debug of tests on Microsoft.Testing.Platform: which process `dotnet test` starts is the test application that waits for the debugger. */
class TestingPlatformDebugTest : TestCase() {
    private val names = setOf("Shop.Tests")

    fun testTheApplicationIsKnownByThePlatformsOptions() {
        // SDK 8 / 9, as seen live: `<app>.exe --internal-msbuild-node testingplatform.pipe.<id>`
        assertTrue(TestingPlatformDebug.isTestApplication("C:\\src\\Shop.Tests\\bin\\Debug\\net9.0\\Other.exe --internal-msbuild-node testingplatform.pipe.301cdc4a", names))
        assertTrue("SDK 10", TestingPlatformDebug.isTestApplication("/src/bin/Other --server dotnettestcli --dotnet-test-pipe /tmp/pipe", names))
    }

    fun testOrByTheNameOfItsProgram() {
        assertTrue(TestingPlatformDebug.isTestApplication("\"C:\\src\\bin\\Shop.Tests.exe\" --filter-uid 1", names))
        assertTrue(TestingPlatformDebug.isTestApplication("/usr/share/dotnet/dotnet exec /src/bin/Shop.Tests.dll", names))
        assertTrue(TestingPlatformDebug.isTestApplication("/src/bin/Shop.Tests", names))
    }

    fun testTheBuildAndTheOtherProcessesOfDotnetTestAreNot() {
        assertFalse(TestingPlatformDebug.isTestApplication("dotnet.exe C:\\sdk\\MSBuild.dll /nodemode:1 /nodeReuse:false", names))
        assertFalse(TestingPlatformDebug.isTestApplication("/usr/share/dotnet/dotnet test /src/Shop.Tests/Shop.Tests.csproj", names))
        assertFalse("another assembly whose name starts the same", TestingPlatformDebug.isTestApplication("/src/bin/Shop.Tests.Helpers.dll", names))
        assertFalse("the build server", TestingPlatformDebug.isTestApplication("dotnet VBCSCompiler.dll -pipename:abc", names))
    }
}
