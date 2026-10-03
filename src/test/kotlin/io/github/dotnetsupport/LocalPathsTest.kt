package io.github.dotnetsupport

import io.github.dotnetsupport.cli.LocalPaths
import junit.framework.TestCase

/** Paths from files of a project are not followed to the network: that would hand the NTLM hash of the user to any host. */
class LocalPathsTest : TestCase() {
    fun testOnlyDriveLetterPathsAreLocalOnWindows() {
        assertTrue(LocalPaths.isLocal("""C:\Users\me\.nuget\packages\""", windows = true))
        assertTrue(LocalPaths.isLocal("d:/packages", windows = true))
        for (network in listOf("""\\host\share""", "//host/share", """/\host\share""", """\/host\share""", """\\?\UNC\host\share""", """\\.\pipe\x""", "relative", "C:", "/home/me"))
            assertFalse(network, LocalPaths.isLocal(network, windows = true))
    }

    fun testOnlyRootedPathsAreLocalElsewhere() {
        assertTrue(LocalPaths.isLocal("/home/me/.nuget/packages/", windows = false))
        assertFalse(LocalPaths.isLocal("//host/share", windows = false))
        assertFalse(LocalPaths.isLocal("relative", windows = false))
    }

    fun testTheNetworkPackagesFolderOfTheUserIsRead() {
        assertTrue(LocalPaths.isReadablePackagesFolder("""\\corp\nuget\packages\""", nugetPackages = """\\corp\nuget\packages"""))
        assertFalse(LocalPaths.isReadablePackagesFolder("""\\attacker\share\""", nugetPackages = """\\corp\nuget\packages"""))
        assertFalse(LocalPaths.isReadablePackagesFolder("""\\attacker\share\""", nugetPackages = null))
    }
}
