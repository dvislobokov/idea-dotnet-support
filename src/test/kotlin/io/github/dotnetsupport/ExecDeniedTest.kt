package io.github.dotnetsupport

import com.intellij.execution.ExecutionException
import io.github.dotnetsupport.cli.ExecDenied
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class ExecDeniedTest {
    // as GIGA IDE on Linux logged it (jspawnhelper, the message of the system in Russian), wrapped by GeneralCommandLine
    private val jspawn = ExecutionException(
        "Cannot run program \"/home/u/.dotnet/tools/dotnet-debugger\" (in directory \"/home/u/.dotnet/tools\"): Exec failed, error: 1 (Операция не позволена) ",
        IOException("Cannot run program \"/home/u/.dotnet/tools/dotnet-debugger\" (in directory \"/home/u/.dotnet/tools\"): Exec failed, error: 1 (Операция не позволена) "),
    )

    @Test
    fun `refusals of the system are recognized`() {
        assertTrue(ExecDenied.matches(jspawn, windows = false))
        assertTrue(ExecDenied.matches(IOException("Cannot run program \"/opt/x/tool\": error=13, Permission denied"), windows = false))
        assertEquals("/home/u/.dotnet/tools/dotnet-debugger", ExecDenied.program(jspawn))
    }

    @Test
    fun `other failures are not`() {
        assertFalse(ExecDenied.matches(IOException("Cannot run program \"dotnet\": error=2, No such file or directory"), windows = false))
        assertFalse(ExecDenied.matches(IOException("Cannot run program \"/opt/x/tool\": error=13, Permission denied"), windows = true))
        assertFalse(ExecDenied.matches(IOException("error=13"), windows = false))
    }

    @Test
    fun `the advice depends on the execute bit`() {
        assertTrue("chmod +x /opt/x/tool" in ExecDenied.explanation("/opt/x/tool", executeBit = false))
        val noexec = ExecDenied.explanation("/home/u/.dotnet/tools/dotnet-debugger", executeBit = true)
        assertTrue(noexec, "noexec" in noexec && "findmnt -T /home/u/.dotnet/tools" in noexec)
    }
}
