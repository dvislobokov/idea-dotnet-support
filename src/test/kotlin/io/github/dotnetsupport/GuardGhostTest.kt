package io.github.dotnetsupport

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import io.github.dotnetsupport.index.AssemblyDocs
import io.github.dotnetsupport.index.AssemblyIndex
import io.github.dotnetsupport.index.AssemblyIndexSet
import io.github.dotnetsupport.lang.CSharpFeature
import io.github.dotnetsupport.lang.CSharpFeatureSource
import io.github.dotnetsupport.lang.CSharpFile
import io.github.dotnetsupport.lang.CSharpSyntaxTrees
import io.github.dotnetsupport.lang.NativeCSharpTypingGhost
import io.github.dotnetsupport.lang.semantic.CSharpSemanticEnvironment
import io.github.dotnetsupport.lsp.RoslynLanguageServerSettings
import io.github.dotnetsupport.suggest.SuggestionRules

/**
 * The guard rules of the gray text (0.1.163, `NativeCSharpGuardGhost.kt`): what an empty guard `if` does (`throw new ArgumentNullException`,
 * `return`, `continue`), what an empty `catch` does (`throw;`, `_logger.LogError`), the type of a parameter from its first letters.
 */
class GuardGhostTest : BasePlatformTestCase() {
    private val settings get() = RoslynLanguageServerSettings.getInstance()

    override fun setUp() {
        super.setUp()
        CSharpSyntaxTrees.forceNativeTreeForTests(true)
        CSharpSemanticEnvironment.setAssembliesForTests { ASSEMBLIES }
        settings.setSource(CSharpFeature.COMPLETION, CSharpFeatureSource.NATIVE)
    }

    override fun tearDown() {
        try {
            settings.state.features = mutableMapOf()
            CSharpSemanticEnvironment.setAssembliesForTests(null)
            CSharpSyntaxTrees.forceNativeTreeForTests(null)
        } catch (e: Throwable) {
            addSuppressedException(e)
        } finally {
            super.tearDown()
        }
    }

    /** A class with [members] above a method of [signature] whose body is [body]. */
    private fun code(signature: String, body: String, members: String = ""): String = """
using System;
using System.Collections.Generic;
using System.Threading.Tasks;
using Microsoft.Extensions.Logging;

namespace Shop;

public class User { public string Name { get; set; } = ""; }
public class UserDto { }
public enum OperationType { Insert, Update }

public class Service
{
$members
    public $signature
    {
$body
    }
}
"""

    private fun ghost(text: String): NativeCSharpTypingGhost.Suggestion? {
        myFixture.configureByText("Guard${counter++}.cs", text)
        return NativeCSharpTypingGhost.suggestion(myFixture.file as CSharpFile, myFixture.editor.document.charsSequence, myFixture.caretOffset)
    }

    private fun gray(text: String): String? = ghost(text)?.text

    private fun guard(condition: String, before: String = ""): String = "$before        if ($condition)\n        {\n            <caret>\n        }"

    // ---- 1. the empty block of a guard `if`

    fun testThrowForAParameterGuardFirstInTheBody() {
        val throws = "throw new ArgumentNullException(nameof(user));"
        assertEquals(throws, gray(code("void Save(User user)", guard("user == null"))))
        assertEquals(throws, gray(code("void Save(User user)", guard("null == user"))))
        assertEquals(throws, gray(code("void Save(User user)", guard("user is null"))))
        assertEquals(throws, gray(code("Service(User user)", guard("user == null"))))
        assertEquals("throw new ArgumentNullException(nameof(name));", gray(code("void Save(string name)", guard("string.IsNullOrEmpty(name)"))))
        // the second guard of the body is a guard still; the brace on the line of the `if` too
        assertEquals(throws, gray(code("void Save(User user, string name)", guard("user == null", "        if (name == null) throw new ArgumentNullException(nameof(name));\n"))))
        assertEquals(throws, gray(code("void Save(User user)", "        if (user == null) {\n            <caret>\n        }")))
    }

    fun testReturnByTheResultAfterOtherStatements() {
        val late = "        var count = 0;\n"
        assertEquals("return;", gray(code("void Save(User user)", guard("user == null", late))))
        assertEquals("return;", gray(code("async Task SaveAsync(User user)", guard("user == null", late))))
        assertEquals("return false;", gray(code("bool Save(User user)", guard("user == null", late))))
        assertEquals("return null;", gray(code("User? Find(User user)", guard("user == null", late))))
        assertEquals("return null;", gray(code("async Task<User?> FindAsync(User user)", guard("user == null", late))))
        // `!cond` guards nothing by name: what the function gives back decides
        assertEquals("return;", gray(code("void Save(bool ok)", guard("!ok"))))
        assertEquals("return false;", gray(code("bool Save(bool ok)", guard("!ok"))))
        // a local is no parameter: no `throw` for it, the result decides
        assertEquals("return;", gray(code("void Save()", guard("user == null", "        User? user = null;\n"))))
        // a value result: `return 0;`, `return -1;`, a throw — as likely
        assertNull(gray(code("int Count(User user)", guard("user == null", late))))
    }

    fun testContinueInALoop() {
        val loop = "        foreach (var user in users)\n        {\n            if (user == null)\n            {\n                <caret>\n            }\n        }"
        assertEquals("continue;", gray(code("void Save(List<User> users)", loop)))
        assertEquals("continue;", gray(code("int Count(List<User> users)", loop)))
    }

    fun testNoGuardTextElsewhere() {
        // a statement in the block already; an `else`; a `while`; a line typed on
        assertNull(gray(code("void Save(User user)", "        if (user == null)\n        {\n            return;\n            <caret>\n        }")))
        assertNull(gray(code("void Save(User user)", "        if (user == null)\n        {\n        }\n        else\n        {\n            <caret>\n        }")))
        assertNull(gray(code("void Save(User user)", "        while (user == null)\n        {\n            <caret>\n        }")))
        // no guard in the condition: the block may do anything
        assertNull(gray(code("void Save(User user)", guard("true"))))
        assertNull(gray(code("void Save(User user)", guard("user.Name.Length > 0"))))
        // `||` of guards is a guard
        assertEquals("throw new ArgumentNullException(nameof(user));", gray(code("void Save(User user, string name)", guard("user == null || name == null"))))
        assertNull(gray(code("void Save(User user)", "        if (user == null)\n        {\n            thr<caret>\n        }")))
    }

    // ---- 2. the empty block of a `catch`

    fun testThrowInACatch() {
        assertEquals("throw;", gray(code("void Save(User user)", "        try\n        {\n            Save(user);\n        }\n        catch (Exception e)\n        {\n            <caret>\n        }")))
        assertEquals("throw;", gray(code("void Save(User user)", "        try\n        {\n            Save(user);\n        }\n        catch\n        {\n            <caret>\n        }")))
        assertEquals("throw;", gray(code("void Save(User user)", "        try { Save(user); } catch (Exception e) {\n            <caret>\n        }")))
    }

    fun testLogErrorWithALogger() {
        val body = "        try\n        {\n            Save(user);\n        }\n        catch (Exception ex)\n        {\n            <caret>\n        }"
        assertEquals("_logger.LogError(ex, \"Save failed\");", gray(code("void Save(User user)", body, "    private readonly ILogger<Service> _logger;")))
        assertEquals("Logger.LogError(ex, \"Save failed\");", gray(code("void Save(User user)", body, "    public ILogger Logger { get; }")))
        // no exception variable to log: the rethrow
        val bare = "        try\n        {\n            Save(user);\n        }\n        catch\n        {\n            <caret>\n        }"
        assertEquals("throw;", gray(code("void Save(User user)", bare, "    private readonly ILogger<Service> _logger;")))
    }

    fun testNoCatchTextOnceTheBlockHasAStatement() {
        assertNull(gray(code("void Save(User user)", "        try\n        {\n            Save(user);\n        }\n        catch (Exception e)\n        {\n            Console.WriteLine(e);\n            <caret>\n        }")))
        // the `try` block is no catch
        assertNull(gray(code("void Save(User user)", "        try\n        {\n            <caret>\n        }\n        catch (Exception e)\n        {\n        }")))
    }

    // ---- 3. the type of a parameter from its first letters

    fun testParameterTypeByPrefix() {
        assertEquals("ype operationType", gray(code("void Save(OperationT<caret>)", "")))
        assertEquals("ype operationType", gray(code("void Save(string name, OperationT<caret>)", "")))
        assertEquals("ype operationType", gray(code("void Save(OperationT<caret>, int count)", "")))
        assertEquals("ype operationType", gray(code("Service(OperationT<caret>)", "")))
        // the whole name typed: the parameter's name
        assertEquals(" operationType", gray(code("void Save(OperationType<caret>)", "")))
        // a name taken by another parameter
        assertEquals("ype operationType1", gray(code("void Save(OperationType operationType, OperationT<caret>)", "")))
        // a type of the assemblies
        assertEquals("an timeSpan", gray(code("void Save(TimeSp<caret>)", "")))
    }

    fun testNoParameterTypeWhenTwoTypesBeginSoOrElsewhere() {
        // `OperationType` and `OperatingSystem` of System; `User` and `UserDto`; `DateTime`, `DateTimeOffset`, `DateTimeKind`
        assertNull(gray(code("void Save(Oper<caret>)", "")))
        assertNull(gray(code("void Save(Us<caret>)", "")))
        assertNull(gray(code("void Save(DateT<caret>)", "")))
        // a name after the type, a small first letter, an argument of a call
        // after the type the word is the name: that is the rule of the name after a type, not this one
        assertNotSame(SuggestionRules.PARAMETER_TYPE, ghost(code("void Save(OperationType Ope<caret>)", ""))?.rule)
        assertNotSame(SuggestionRules.PARAMETER_TYPE, ghost(code("void Save(OperationType ope<caret>)", ""))?.rule)
        assertNull(gray(code("void Save(User user)", "        Save(OperationT<caret>)")))
    }

    companion object {
        private var counter = 0

        private fun bytes(name: String): ByteArray? = GuardGhostTest::class.java.getResourceAsStream("/index/$name")?.use { it.readBytes() }
        private fun fixture(name: String): AssemblyIndex = AssemblyIndex.read(bytes("$name.dnix")!!, bytes("$name.dnxd")?.let(AssemblyDocs::read))

        val ASSEMBLIES: AssemblyIndexSet by lazy {
            AssemblyIndexSet(listOf("IndexFixture", "System.Runtime", "System.Console", "System.Linq", "System.Collections").map(::fixture))
        }
    }
}
