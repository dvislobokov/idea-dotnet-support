package io.github.dotnetsupport

import com.intellij.execution.configurations.GeneralCommandLine
import io.github.dotnetsupport.cli.DotNetCli
import io.github.dotnetsupport.nuget.NuGetConfigEditor
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
        assertEquals(listOf("add", "source", "https://a/index.json", "--name", "Feed"), anonymous.cliArguments(isNew = true))

        val private = NuGetSourceSettings("Feed", "http://a/index.json", "me", "pw", isEnabled = false, allowInsecureConnections = true, disableTlsCertificateValidation = false)
        val arguments = private.cliArguments(isNew = false)
        assertEquals(listOf("update", "source", "Feed", "--source", "http://a/index.json", "--username", "me", "--password", "pw"), arguments.take(9))
        assertEquals("--allow-insecure-connections", arguments.last())
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
