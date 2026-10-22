package io.github.dotnetsupport

import com.intellij.execution.configurations.GeneralCommandLine
import io.github.dotnetsupport.cli.DotNetCli
import io.github.dotnetsupport.nuget.NuGetConfigEditor
import io.github.dotnetsupport.nuget.NuGetKeychain
import io.github.dotnetsupport.nuget.NuGetSourceSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NuGetSourcesTest {
    private val config = """
        <?xml version="1.0" encoding="utf-8"?>
        <configuration>
          <packageSources>
            <!-- the public feed -->
            <add key="nuget.org" value="https://api.nuget.org/v3/index.json" protocolVersion="3" />
            <add key="Company &amp; Co" value="http://pkgs.example/v3/index.json" allowInsecureConnections="true" />
          </packageSources>
          <disabledPackageSources>
            <add key="nuget.org" value="true" />
          </disabledPackageSources>
          <packageSourceCredentials>
            <Company_x0020__x0026__x0020_Co>
              <add key="Username" value="me" />
            </Company_x0020__x0026__x0020_Co>
          </packageSourceCredentials>
        </configuration>
    """.trimIndent()

    @Test
    fun `passwords never show up in logs and progress texts`() {
        val command = GeneralCommandLine("C:/Program Files/dotnet/dotnet.exe", "nuget", "add", "source", "https://pkgs.example/index.json", "--name", "My Feed", "--username", "me", "--password", "s3cret")
        assertEquals("dotnet nuget add source https://pkgs.example/index.json --name \"My Feed\" --username me --password ********", DotNetCli.displayString(command))
        assertEquals("dotnet nuget push a.nupkg -k ********", DotNetCli.displayString(GeneralCommandLine("dotnet", "nuget", "push", "a.nupkg", "-k", "KEY")))
        assertFalse(DotNetCli.displayString(command).contains("s3cret"))
    }

    @Test
    fun `cli arguments of the feed dialog`() {
        val anonymous = NuGetSourceSettings("Feed", "https://a/index.json", null, null, isEnabled = true, allowInsecureConnections = false, disableTlsCertificateValidation = true)
        assertEquals(listOf("add", "source", "https://a/index.json", "--name", "Feed"), anonymous.cliArguments(isNew = true, credentialsInConfig = true))

        val private = NuGetSourceSettings("Feed", "http://a/index.json", "me", "pw", isEnabled = false, allowInsecureConnections = true, disableTlsCertificateValidation = false)
        val arguments = private.cliArguments(isNew = false, credentialsInConfig = true)
        assertEquals(listOf("update", "source", "Feed", "--source", "http://a/index.json", "--username", "me", "--password", "pw"), arguments.take(9))
        assertEquals("--allow-insecure-connections", arguments.last())
        // the password in the keychain: nuget.config gets the source only
        assertEquals(listOf("update", "source", "Feed", "--source", "http://a/index.json", "--allow-insecure-connections"),
            private.cliArguments(isNew = false, credentialsInConfig = false))
    }

    private val credentials = """
        <?xml version="1.0" encoding="utf-8"?>
        <configuration>
          <packageSources>
            <add key="My Feed" value="https://pkgs.example/v3/index.json" />
            <add key="ci" value="https://ci.example/v3/index.json" />
            <add key="win" value="https://win.example/v3/index.json" />
          </packageSources>
          <packageSourceCredentials>
            <!-- written by dotnet nuget add source -->
            <My_x0020_Feed>
              <add key="Username" value="me" />
              <add value="p&amp;ss &quot;word&quot;" key="ClearTextPassword" />
            </My_x0020_Feed>
            <ci>
              <add key="Username" value="build" />
              <add key="ClearTextPassword" value="%FEED_TOKEN%" />
            </ci>
            <win>
              <add key="Username" value="me" />
              <add key="Password" value="AQAAANCMnd8BFdERjHoAwE" />
            </win>
          </packageSourceCredentials>
        </configuration>
    """.trimIndent()

    @Test
    fun `clear-text passwords are found, references to the environment and encrypted ones are not`() {
        assertEquals(listOf(NuGetConfigEditor.CredentialEntry("My Feed", "me", "p&ss \"word\"")), NuGetConfigEditor.clearTextCredentials(credentials))
        assertEquals(emptyList<NuGetConfigEditor.CredentialEntry>(), NuGetConfigEditor.clearTextCredentials(config))
        assertEquals("My_x0020_Feed", NuGetConfigEditor.encodeName("My Feed"))
        assertEquals("Company_x0020__x0026__x0020_Co", NuGetConfigEditor.encodeName("Company & Co"))
    }

    @Test
    fun `credentials of one source are taken out, the rest of the file stays`() {
        val without = NuGetConfigEditor.removeCredentials(credentials, "My Feed")
        assertEquals(credentials.replace("    <My_x0020_Feed>\n      <add key=\"Username\" value=\"me\" />\n" +
            "      <add value=\"p&amp;ss &quot;word&quot;\" key=\"ClearTextPassword\" />\n    </My_x0020_Feed>\n", ""), without)
        assertEquals(credentials, NuGetConfigEditor.removeCredentials(credentials, "missing"))
        // the last one out takes the section with it
        val last = NuGetConfigEditor.removeCredentials(config, "Company & Co")
        assertFalse(last, last.contains("packageSourceCredentials"))
        assertTrue(last, last.endsWith("  </disabledPackageSources>\n</configuration>"))
    }

    @Test
    fun `the credential provider is installed once per build and not where NuGet is told to look elsewhere`() {
        val root = java.nio.file.Files.createTempDirectory("keychain").toFile()
        try {
            val bin = java.io.File(root, "abc123-net10.0/bin").apply { mkdirs() }
            java.io.File(bin, "${NuGetKeychain.ASSEMBLY}.dll").writeText("one")
            java.io.File(bin, "${NuGetKeychain.ASSEMBLY}.runtimeconfig.json").writeText("{}")
            val target = java.io.File(root, "plugins/netcore/${NuGetKeychain.ASSEMBLY}")
            val dll = NuGetKeychain.install(bin, target)
            assertEquals(java.io.File(target, "${NuGetKeychain.ASSEMBLY}.dll"), dll)
            assertEquals("one", dll.readText())
            assertTrue(java.io.File(target, "${NuGetKeychain.ASSEMBLY}.runtimeconfig.json").isFile)
            // the same build: nothing is copied again (a restore running may hold the dll)
            java.io.File(bin, "${NuGetKeychain.ASSEMBLY}.dll").writeText("two")
            assertEquals("one", NuGetKeychain.install(bin, target).readText())
            val next = java.io.File(root, "def456-net10.0/bin").apply { mkdirs() }
            java.io.File(next, "${NuGetKeychain.ASSEMBLY}.dll").writeText("three")
            assertEquals("three", NuGetKeychain.install(next, target).readText())
        } finally {
            root.deleteRecursively()
        }
        assertEquals(null, NuGetKeychain.pluginPathsOverride(mapOf("PATH" to "/usr/bin", "NUGET_PLUGIN_PATHS" to " ")))
        assertEquals("NUGET_NETCORE_PLUGIN_PATHS" to "/a.dll", NuGetKeychain.pluginPathsOverride(mapOf("NUGET_PLUGIN_PATHS" to "/b.dll", "NUGET_NETCORE_PLUGIN_PATHS" to "/a.dll")))
    }

    @Test
    fun `flags are read from the package source entry`() {
        assertTrue(NuGetConfigEditor.declares(config, "nuget.org"))
        assertTrue(NuGetConfigEditor.declares(config, "Company & Co"))
        assertFalse(NuGetConfigEditor.declares(config, "Username"))
        assertFalse(NuGetConfigEditor.declares(config, "missing"))

        assertTrue(NuGetConfigEditor.flag(config, "Company & Co", NuGetConfigEditor.ALLOW_INSECURE))
        assertFalse(NuGetConfigEditor.flag(config, "Company & Co", NuGetConfigEditor.DISABLE_TLS))
        // `value="true"` of the disabled section is not a flag of the source
        assertFalse(NuGetConfigEditor.flag(config, "nuget.org", NuGetConfigEditor.ALLOW_INSECURE))
    }

    @Test
    fun `flags are set and removed without touching the rest of the file`() {
        val withTls = NuGetConfigEditor.setFlag(config, "nuget.org", NuGetConfigEditor.DISABLE_TLS, true)
        assertTrue(withTls.contains("""<add key="nuget.org" value="https://api.nuget.org/v3/index.json" protocolVersion="3" disableTLSCertificateValidation="true" />"""))
        assertTrue(NuGetConfigEditor.flag(withTls, "nuget.org", NuGetConfigEditor.DISABLE_TLS))
        // only that line has changed: the comment, the disabled section and the credentials are as they were
        assertEquals(config.lines().size, withTls.lines().size)
        assertEquals(1, config.lines().zip(withTls.lines()).count { (a, b) -> a != b })

        assertEquals(config, NuGetConfigEditor.setFlag(withTls, "nuget.org", NuGetConfigEditor.DISABLE_TLS, false))
        // setting twice does not duplicate the attribute
        assertEquals(withTls, NuGetConfigEditor.setFlag(withTls, "nuget.org", NuGetConfigEditor.DISABLE_TLS, true))

        val secured = NuGetConfigEditor.setFlag(config, "Company & Co", NuGetConfigEditor.ALLOW_INSECURE, false)
        assertTrue(secured.contains("""<add key="Company &amp; Co" value="http://pkgs.example/v3/index.json" />"""))
        assertEquals(config, NuGetConfigEditor.setFlag(config, "missing", NuGetConfigEditor.DISABLE_TLS, true))
    }
}
