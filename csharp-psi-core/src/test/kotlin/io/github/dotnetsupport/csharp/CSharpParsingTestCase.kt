package io.github.dotnetsupport.csharp

import com.intellij.openapi.util.io.FileUtil
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiErrorElement
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.testFramework.ParsingTestCase
import com.intellij.psi.PsiFile
import io.github.dotnetsupport.csharp.lang.CSharpLanguageLevel
import io.github.dotnetsupport.csharp.lang.CSharpLanguageVersion
import io.github.dotnetsupport.csharp.lang.CSharpParserDefinition
import io.github.dotnetsupport.csharp.lang.CSharpPreprocessorSymbols
import java.io.File

/**
 * Base class for parser golden tests. Sources live in `testData/<dataPath>/<TestName>.cs`, the expected PSI dump
 * (with ranges, whitespace included) in `<TestName>.txt` next to it.
 *
 * With `-Dcsharppsi.updateGoldens=true` the golden is (re)written from the actual tree and the test passes; review the
 * golden diff by hand. Without the flag a missing golden is created and the test fails, so that new goldens are always
 * reviewed.
 */
abstract class CSharpParsingTestCase(dataPath: String = "parser") :
    ParsingTestCase(dataPath, "cs", CSharpParserDefinition()) {

    override fun getTestDataPath(): String = CSharpTestUtil.testDataPath()

    override fun skipSpaces(): Boolean = false

    override fun includeRanges(): Boolean = true

    /**
     * Parses `<TestName>.cs` and compares the PSI dump with the golden (always); with [checkErrors] also asserts there
     * are no error elements. Replaces the platform's `doTest(checkResult)`.
     */
    @Suppress("PARAMETER_NAME_CHANGED_ON_OVERRIDE")
    override fun doTest(checkErrors: Boolean) {
        val name = getTestName(false)
        val text = loadFile("$name.$myFileExt")
        myFile = createPsiFile(name, text)
        ensureParsed(myFile)
        assertEquals("PSI text differs from the source text", text, myFile.text)

        val actual = toParseTreeText(myFile, skipSpaces(), includeRanges()).trim()
        val golden = File(myFullDataPath, "$myFilePrefix$name.txt")

        if (CSharpTestUtil.updateGoldens || !golden.exists()) {
            val existed = golden.exists()
            FileUtil.writeToFile(golden, actual)
            if (!CSharpTestUtil.updateGoldens && !existed) {
                fail("Golden file did not exist and was created: ${golden.path}. Review it and re-run.")
            }
        } else {
            assertSameLinesWithFile(golden.path, actual)
        }

        if (checkErrors) ensureNoErrorElements()
    }

    /**
     * Files of parser tests are lexed with no `#if` symbols, as the oracle (`roslyndump` without `--define`) and the
     * coverage check (`CSharpLexer()`), and parsed at [languageVersion] (`Preview`, the oracle's default): the tree is
     * parsed lazily, after the keys are set.
     */
    override fun createFile(name: String, text: String): PsiFile =
        super.createFile(name, text).also {
            it.putUserData(CSharpPreprocessorSymbols.KEY, emptySet())
            it.putUserData(CSharpLanguageLevel.KEY, languageVersion)
        }

    /** The language version of [createFile]'s files (`roslyndump`'s default without `--langversion`). */
    protected open val languageVersion: CSharpLanguageVersion get() = CSharpLanguageVersion.Preview

    /** True if [element] contains a [PsiErrorElement]. */
    protected fun hasErrorElements(element: PsiElement): Boolean =
        PsiTreeUtil.findChildOfType(element, PsiErrorElement::class.java) != null
}
