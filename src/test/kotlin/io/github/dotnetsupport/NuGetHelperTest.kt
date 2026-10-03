package io.github.dotnetsupport

import com.google.gson.JsonElement
import com.google.gson.JsonParser
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import io.github.dotnetsupport.cli.HelperException
import io.github.dotnetsupport.msbuild.PackagesConfigEntry
import io.github.dotnetsupport.nuget.NuGetClient
import io.github.dotnetsupport.nuget.NuGetHelperResponses
import io.github.dotnetsupport.nuget.NuGetHelperResponses.RestoredPackage.State
import io.github.dotnetsupport.nuget.NuGetNetwork
import io.github.dotnetsupport.nuget.NuGetPackageInfo
import io.github.dotnetsupport.nuget.PackagesFolder
import java.io.File
import java.nio.file.Files

/**
 * The NuGet client of DotNetHelper: its answers parsed from a saved real run (`src/test/resources/nuget/helper`: nuget.org, a local
 * folder feed, a host that does not exist, a packages.config restore), the way [NuGetClient] falls back to it, the packages folder of
 * packages.config projects. The helper itself is never started here.
 */
class NuGetHelperTest : BasePlatformTestCase() {
    private fun saved(name: String): JsonElement =
        JsonParser.parseString(NuGetHelperTest::class.java.getResourceAsStream("/nuget/helper/$name")!!.use { it.readBytes().toString(Charsets.UTF_8) })

    fun testSourcesOfTheSavedRun() {
        val sources = NuGetHelperResponses.parseSources(saved("sources.json"))
        assertEquals(listOf("nuget.org", "local"), sources.map { it.name })
        assertEquals("https://api.nuget.org/v3/index.json", sources[0].url)
        assertEquals(3, sources[0].protocolVersion)
        assertTrue(sources[0].isHttp && !sources[0].isLocal)
        // a relative path of nuget.config comes resolved
        assertTrue(sources[1].url, sources[1].isLocal && sources[1].url.endsWith("dnh-test\\feed"))
    }

    fun testSearchOfTheSavedRun() {
        val answers = NuGetHelperResponses.parseSearch(saved("search.json"))
        assertEquals(2, answers.size)
        val nugetOrg = answers[0]
        assertNull(nugetOrg.error)
        val packages = nugetOrg.result!!
        assertEquals(5, packages.size)
        val json = packages.first()
        assertEquals("Newtonsoft.Json" to "13.0.4", json.id to json.version)
        assertEquals("James Newton-King", json.authors)
        assertTrue(json.isVerified)
        assertTrue(json.totalDownloads > 1_000_000_000)
        assertEquals("https://api.nuget.org/v3-flatcontainer/newtonsoft.json/13.0.4/icon", json.iconUrl)
        assertEquals(listOf("json"), json.tags)
        assertEquals(53, json.versions.size)
        assertEquals("13.0.4", json.versions.last())
        // the local folder feed: the package that is in it, with the one version it has
        val local = answers[1].result!!.single()
        assertEquals(listOf("13.0.3"), local.versions)
        assertEquals("Newtonsoft.Json" to "13.0.3", local.id to local.version)
    }

    fun testFailedSourceOfTheSavedRun() {
        val failed = NuGetHelperResponses.parseSearch(saved("search-failed.json")).single()
        assertNull(failed.result)
        assertTrue(failed.error!!, failed.error!!.startsWith("Unable to load the service index for source https://no-such-host.invalid/v3/index.json."))
        assertTrue(failed.elapsedMs > 0)
    }

    fun testVersionsOfTheSavedRun() {
        val answers = NuGetHelperResponses.parseVersions(saved("versions.json"))
        val nugetOrg = answers[0].result!!
        assertEquals(185, nugetOrg.size)
        assertEquals("4.4.0", nugetOrg.last())
        assertTrue(nugetOrg.none { '-' in it }) // asked without prerelease
        assertEquals(listOf("4.0.0"), answers[1].result)
    }

    fun testRestoreOfTheSavedRun() {
        val restore = NuGetHelperResponses.parseRestore(saved("restore.json"))!!
        assertTrue(restore.packagesDirectory, restore.packagesDirectory.endsWith("sln\\packages"))
        assertEquals(listOf(State.RESTORED, State.RESTORED, State.RESTORED, State.FAILED), restore.packages.map { it.state })
        assertEquals("No.Such.Package.Xyz", restore.failed.single().id)
        assertEquals(
            listOf(
                "  Newtonsoft.Json 13.0.3: restored from https://api.nuget.org/v3/index.json",
                "  Serilog 2.8.0: restored from https://api.nuget.org/v3/index.json",
                "  Dapper 2.1.35: restored from https://api.nuget.org/v3/index.json",
                "  No.Such.Package.Xyz 1.0.0: failed: not found in nuget.org, local",
            ),
            restore.lines(),
        )
        assertNull(NuGetHelperResponses.parseRestore(null))
    }

    fun testRouteFailuresGoToTheHelper() {
        assertTrue(NuGetNetwork.isRouteFailure(java.net.UnknownHostException("sberosc.example")))
        assertTrue(NuGetNetwork.isRouteFailure(RuntimeException("x", javax.net.ssl.SSLHandshakeException("PKIX path building failed"))))
        assertTrue(NuGetNetwork.isRouteFailure(java.net.ConnectException("Connection refused")))
        assertTrue(NuGetNetwork.isRouteFailure(java.io.IOException("Unable to tunnel through proxy. Proxy returns \"HTTP/1.1 407\"")))
        assertTrue(NuGetNetwork.isRouteFailure(com.intellij.util.io.HttpRequests.HttpStatusException("Unauthorized", 401, "https://a/query")))
        // the feed answered: the helper would get the same
        assertFalse(NuGetNetwork.isRouteFailure(com.intellij.util.io.HttpRequests.HttpStatusException("Not found", 404, "https://a/query")))
        assertFalse(NuGetNetwork.isRouteFailure(com.intellij.util.io.HttpRequests.HttpStatusException("Server error", 500, "https://a/query")))
        assertFalse(NuGetNetwork.isRouteFailure(IllegalStateException("not JSON")))
    }

    /** A helper that answers for the feeds it is given, and counts what it is asked. */
    private class FakeHelper(val failWith: String? = null) : NuGetClient.NuGetFallback {
        val asked = ArrayList<String>()

        override fun search(source: String, query: String, includePrerelease: Boolean, take: Int, packageType: String?): List<NuGetPackageInfo> {
            asked += "search $source"
            failWith?.let { throw HelperException(it) }
            return listOf(NuGetPackageInfo("Corp.Logging", "2.0.0", "", "", 0, null, false, listOf("1.0.0", "2.0.0")))
        }

        override fun versions(source: String, packageId: String): List<String> {
            asked += "versions $source"
            failWith?.let { throw HelperException(it) }
            return listOf("1.0.0", "2.0.0")
        }
    }

    fun testFeedTheIdeCannotReachGoesThroughTheHelper() {
        val events = ArrayList<Pair<String, Boolean>>()
        val requested = ArrayList<String>()
        val helper = FakeHelper()
        val client = NuGetClient(onEvent = { text, isError -> events += text to isError }, route = { _, _ -> "direct, test" }, fallback = helper) { url, _ ->
            requested += url
            when {
                url == "https://a/index.json" -> """{"resources":[{"@id":"https://a/query","@type":"SearchQueryService"},{"@id":"https://a/flat/","@type":"PackageBaseAddress/3.0.0"}]}"""
                url.startsWith("https://a/query") -> """{"data":[{"id":"Serilog","version":"4.0.0"}]}"""
                url.startsWith("https://notfound/") -> throw com.intellij.util.io.HttpRequests.HttpStatusException("Not found", 404, url)
                else -> throw java.net.UnknownHostException("corp.example")
            }
        }
        val corp = "https://corp.example/v3/index.json"
        val local = "C:\\feeds\\local"
        val sources = listOf("https://a/index.json", corp, "https://notfound/index.json", local)

        val found = client.search("log", false, sources)
        assertEquals(listOf("Serilog", "Corp.Logging"), found.map { it.id })
        assertEquals(corp, found[1].source)
        // the corporate feed: the IDE first, then the helper; the local feed: the helper only; a 404 is the answer of the feed, not of the route
        assertEquals(listOf("search $corp", "search $local"), helper.asked)
        assertTrue(requested.none { it.startsWith("C:") })
        val lines = events.map { it.first }
        assertTrue(lines.toString(), lines.any { it.startsWith("search \"log\" in $corp via the .NET helper: the IDE could not reach the feed: UnknownHostException: corp.example") })
        assertTrue(lines.toString(), lines.any { it == "search \"log\" in $local via the .NET helper: a local folder feed" })
        assertTrue(lines.toString(), lines.any { it == "search \"log\" in $corp via the .NET helper: 1 packages, first Corp.Logging" })
        // the helper got there: the window does not count the feed as silent
        assertNull(client.lastFailure(corp))
        assertNotNull(client.lastFailure("https://notfound/index.json"))

        // for a while the feed goes to the helper straight away, without waiting for the IDE to fail again
        val before = requested.size
        assertEquals(listOf("1.0.0", "2.0.0"), client.versions("Corp.Logging", listOf(corp)))
        assertEquals(before, requested.size)
        assertTrue(events.map { it.first }.any { it.startsWith("versions of Corp.Logging in $corp via the .NET helper: the IDE failed to reach the feed 0 s ago") })
    }

    fun testHelperFailureIsSaidWithTheIdeFailure() {
        val events = ArrayList<Pair<String, Boolean>>()
        val helper = FakeHelper(failWith = "Unable to load the service index for source https://corp.example/v3/index.json. -> No such host is known.")
        val client = NuGetClient(onEvent = { text, isError -> events += text to isError }, route = { _, _ -> "direct, test" }, fallback = helper) { _, _ ->
            throw java.net.UnknownHostException("corp.example")
        }
        val corp = "https://corp.example/v3/index.json"
        assertEquals(emptyList<NuGetPackageInfo>(), client.search("log", false, listOf(corp)))
        val failure = client.lastFailure(corp)!!
        assertTrue(failure, failure.startsWith("UnknownHostException: corp.example") && "; the .NET helper: Unable to load the service index" in failure)
        assertTrue(events.filter { it.second }.map { it.first }.toString(), events.any { it.second && it.first.startsWith("search \"log\" in $corp via the .NET helper failed after") })
    }

    fun testWithoutTheHelperLocalFeedsAreSkipped() {
        val requested = ArrayList<String>()
        val client = NuGetClient { url, _ -> requested += url; error("unexpected") }
        assertEquals(emptyList<NuGetPackageInfo>(), client.search("x", false, listOf("/srv/feed")))
        assertEquals(emptyList<String>(), client.versions("x", listOf("/srv/feed")))
        assertEquals(emptyList<String>(), requested)
    }

    fun testPackagesFolder() {
        assertEquals("..\\lib\\packages", PackagesFolder.parseRepositoryPath(
            """<?xml version="1.0"?><configuration><config><add key="repositoryPath" value="..\lib\packages" /></config></configuration>"""))
        assertNull(PackagesFolder.parseRepositoryPath("""<configuration><packageSources><add key="repositoryPath" value="x" /></packageSources></configuration>"""))
        assertNull(PackagesFolder.parseRepositoryPath("<configuration"))

        val root = Files.createTempDirectory("packages-folder").toFile()
        try {
            val solution = File(root, "src/App").apply { mkdirs() }
            // nothing says: next to the solution
            assertEquals(File(solution, "packages"), PackagesFolder.of(solution, PackagesFolder.configFiles(solution, userConfig = null).filter { it.startsWith(root) }))
            // the nearest config that says, relative to that config
            File(root, "NuGet.Config").writeText("""<configuration><config><add key="repositoryPath" value="lib/packages" /></config></configuration>""")
            File(root, "src/nuget.config").writeText("""<configuration><packageSources><clear /></packageSources></configuration>""")
            val configs = PackagesFolder.configFiles(solution, userConfig = null).filter { it.startsWith(root) }
            assertEquals(listOf(File(root, "src/nuget.config"), File(root, "NuGet.Config")).map { it.absolutePath }, configs.map { it.absolutePath })
            assertEquals(File(root, "lib/packages").absoluteFile.normalize(), PackagesFolder.of(solution, configs).absoluteFile)
        } finally {
            root.deleteRecursively()
        }
    }

    fun testFolderNamesAndMissingPackages() {
        assertEquals(setOf("antlr.3.5.0.2"), PackagesFolder.folderNames("Antlr", "3.5.0.2"))
        assertEquals(setOf("x.1.0", "x.1.0.0", "x.1.0.0.0"), PackagesFolder.folderNames("X", "1.0"))
        assertEquals(setOf("x.2.0.0-beta.1", "x.2.0.0.0-beta.1"), PackagesFolder.folderNames("X", "2.0.0-beta.1"))

        val entries = listOf(PackagesConfigEntry("Newtonsoft.Json", "13.0.3", "net48"), PackagesConfigEntry("Antlr", "3.5.0.2", "net48"),
            PackagesConfigEntry("Old", "1.0", null), PackagesConfigEntry("Broken", null, null))
        assertEquals(listOf("Antlr", "Broken"), PackagesFolder.missing(entries, setOf("newtonsoft.json.13.0.3", "old.1.0.0", "readme.txt")).map { it.id })
    }
}
