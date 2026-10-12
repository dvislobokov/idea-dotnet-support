package io.github.dotnetsupport.csharp.lang

import io.github.dotnetsupport.csharp.CSharpParsingTestCase

/**
 * Checks the golden harness itself on a minimal file through the parser of [CSharpParserDefinition]. The golden
 * changes with the parser: regenerate with `-Dcsharppsi.updateGoldens=true` and review.
 */
class CSharpParsingHarnessTest : CSharpParsingTestCase() {
    fun testBootstrap() = doTest(false)
}
