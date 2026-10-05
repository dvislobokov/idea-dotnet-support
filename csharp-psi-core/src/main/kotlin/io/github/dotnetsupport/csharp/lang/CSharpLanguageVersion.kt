// Ported from Roslyn: src/Compilers/CSharp/Portable/LanguageVersion.cs (`LanguageVersion`, `LanguageVersionFacts.TryParse`,
// `MapSpecifiedToEffectiveVersion`, `ToDisplayString`), Errors/MessageID.cs (`RequiredVersion` of the features the
// parser reads) and CSharpParseOptions.cs (`IsFeatureEnabled`), roslynCommit 35d9211b841e7613c1d2f8f5af6d628ace696c4c.
// Roslyn: Copyright (c) .NET Foundation and Contributors, MIT License (NOTICE.md).
package io.github.dotnetsupport.csharp.lang

import com.intellij.lang.PsiBuilder
import com.intellij.openapi.util.Key
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiFile
import com.intellij.testFramework.LightVirtualFile

/**
 * Roslyn's `LanguageVersion`, with its numeric values (the order of the effective versions is the order of [value]).
 * [Default], [Latest] and [LatestMajor] are *specified* versions: [effective] maps them as Roslyn's
 * `MapSpecifiedToEffectiveVersion` does at `roslynCommit` (all three to [CSharp14]).
 */
enum class CSharpLanguageVersion(val value: Int, val displayString: String) {
    CSharp1(1, "1"),
    CSharp2(2, "2"),
    CSharp3(3, "3"),
    CSharp4(4, "4"),
    CSharp5(5, "5"),
    CSharp6(6, "6"),
    CSharp7(7, "7.0"),
    CSharp7_1(701, "7.1"),
    CSharp7_2(702, "7.2"),
    CSharp7_3(703, "7.3"),
    CSharp8(800, "8.0"),
    CSharp9(900, "9.0"),
    CSharp10(1000, "10.0"),
    CSharp11(1100, "11.0"),
    CSharp12(1200, "12.0"),
    CSharp13(1300, "13.0"),
    CSharp14(1400, "14.0"),
    LatestMajor(Int.MAX_VALUE - 2, "latestmajor"),
    Preview(Int.MAX_VALUE - 1, "preview"),
    Latest(Int.MAX_VALUE, "latest"),
    Default(0, "default"),
    ;

    /** `MapSpecifiedToEffectiveVersion`: `Latest`, `Default`, `LatestMajor` → the current version ([CSharp14]). */
    fun effective(): CSharpLanguageVersion = when (this) {
        Latest, Default, LatestMajor -> CURRENT
        else -> this
    }

    /** `CSharpParseOptions.IsFeatureEnabled`: the effective version is at least the feature's required version. */
    fun isFeatureEnabled(feature: CSharpFeature): Boolean = effective().value >= feature.requiredVersion.value

    companion object {
        /** `LanguageVersionFacts.CurrentVersion` at `roslynCommit`. */
        @JvmField val CURRENT: CSharpLanguageVersion = CSharp14

        /** The effective versions Roslyn accepts (`IsValid`): `CSharp1` .. `CSharp14`, `Preview`. */
        @JvmStatic
        val effectiveVersions: List<CSharpLanguageVersion> get() = entries.filter { it.effective() == it }

        /**
         * `LanguageVersionFacts.TryParse`: the `LangVersion` strings of MSBuild and `csc -langversion` (case-insensitive;
         * `1`/`1.0`/`iso-1` .. `14`/`14.0`, `7.1`..`7.3`, `default`, `latest`, `latestmajor`, `preview`); null input is
         * [Default], an unknown string is null.
         */
        @JvmStatic
        fun parse(text: String?): CSharpLanguageVersion? {
            if (text == null) return Default
            return when (text.trim().lowercase()) {
                "default" -> Default
                "latest" -> Latest
                "latestmajor" -> LatestMajor
                "preview" -> Preview
                "1", "1.0", "iso-1" -> CSharp1
                "2", "2.0", "iso-2" -> CSharp2
                "3", "3.0" -> CSharp3
                "4", "4.0" -> CSharp4
                "5", "5.0" -> CSharp5
                "6", "6.0" -> CSharp6
                "7", "7.0" -> CSharp7
                "7.1" -> CSharp7_1
                "7.2" -> CSharp7_2
                "7.3" -> CSharp7_3
                "8", "8.0" -> CSharp8
                "9", "9.0" -> CSharp9
                "10", "10.0" -> CSharp10
                "11", "11.0" -> CSharp11
                "12", "12.0" -> CSharp12
                "13", "13.0" -> CSharp13
                "14", "14.0" -> CSharp14
                else -> null
            }
        }
    }
}

/**
 * The language features whose availability the syntax layer reads (`MessageID.IDS_Feature*` with their
 * `RequiredVersion()` at `roslynCommit`). Only features that change the tree or a counted parser error are listed; the
 * list of every check and what was ported is in docs/csharp-psi/GRAMMAR.md, "Language version".
 */
enum class CSharpFeature(val requiredVersion: CSharpLanguageVersion) {
    /** `IDS_FeatureGenerics`: `CheckFeatureAvailability` on `<` of a type argument list (`ParseTypeArgumentList`). */
    Generics(CSharpLanguageVersion.CSharp2),

    /** `IDS_FeatureBinaryLiteral`: lexer (`ScanNumericLiteral`), a diagnostic on the token. */
    BinaryLiteral(CSharpLanguageVersion.CSharp7),

    /** `IDS_FeatureDigitSeparator`: lexer (`ScanNumericLiteral`). */
    DigitSeparator(CSharpLanguageVersion.CSharp7),

    /** `IDS_FeatureLeadingDigitSeparator`: lexer (`ScanNumericLiteral`, `0x_1`). */
    LeadingDigitSeparator(CSharpLanguageVersion.CSharp7_2),

    /** `IDS_FeatureExpressionBodiedAccessor`: chooses an error code only (`EatAccessorSemicolon`, `ParseMethodOrAccessorBodyBlock`). */
    ExpressionBodiedAccessor(CSharpLanguageVersion.CSharp7),

    /** `IDS_FeatureRecords`: `record` as a type declaration start (`IsTypeDeclarationStart`, `IsPartialType`, `ParseModifiers`). */
    Records(CSharpLanguageVersion.CSharp9),

    /** `IDS_FeatureFileTypes`: `file` always a modifier (`ParseModifiers.parseAsModifier`). */
    FileTypes(CSharpLanguageVersion.CSharp11),

    /** `IDS_FeatureRequiredMembers`: `required` always a modifier (`ParseModifiers.parseAsModifier`). */
    RequiredMembers(CSharpLanguageVersion.CSharp11),

    /** `IDS_FeatureStringEscapeCharacter`: lexer (`ScanEscapeSequence`, `\e`). */
    StringEscapeCharacter(CSharpLanguageVersion.CSharp13),

    /** `IDS_FeatureFieldKeyword`: `field` in an accessor is a `FieldExpression` (`IsCurrentTokenFieldInKeywordContext`). */
    FieldKeyword(CSharpLanguageVersion.CSharp14),

    /** `IDS_FeatureSimpleLambdaParameterModifiers`: `scoped` always a modifier on a lambda parameter (`IsDefiniteScopedModifier`). */
    SimpleLambdaParameterModifiers(CSharpLanguageVersion.CSharp14),

    /** `IDS_FeaturePartialEventsAndConstructors`: `partial C(` is a partial constructor (`IsPartialMember`). */
    PartialEventsAndConstructors(CSharpLanguageVersion.CSharp14),

    /** `IDS_FeatureExtensions`: `extension` starts an extension block without `<` after it (`IsExtensionContainerStart`). */
    Extensions(CSharpLanguageVersion.CSharp14),

    /** `IDS_FeatureUnions`: `union` as a type declaration start (as [Records]). */
    Unions(CSharpLanguageVersion.Preview),

    /** `IDS_FeatureClosedClasses`: `closed` always a modifier (`ParseModifiers.parseAsModifier`). */
    ClosedClasses(CSharpLanguageVersion.Preview),

    /** `IDS_FeatureUnsafeEvolution`: `safe` always a modifier outside accessors (`ParseModifiers.parseAsModifier`). */
    UnsafeEvolution(CSharpLanguageVersion.Preview),
}

/**
 * The language version a C# file is parsed with (Roslyn's `CSharpParseOptions.LanguageVersion`), kept the way
 * [CSharpPreprocessorSymbols] keeps the `#if` symbols: [KEY] on the `PsiFile`, its `VirtualFile` or the files it is a
 * copy of, else [IDE_DEFAULT] ([forFile]). The project model will set it per project from `LangVersion` and the target
 * framework's default (`net4x` → 7.3, `net10.0` → 14).
 *
 * The parser takes it from its [PsiBuilder] ([forBuilder]: [KEY] on the builder, else [IDE_DEFAULT]): the file element
 * type puts `forFile(file)` on the builder before parsing. **Any other parse of a part of a file (a lazy or reparseable
 * body) must do the same**, `builder.putUserData(CSharpLanguageLevel.KEY, CSharpLanguageLevel.forFile(file))`, or pass
 * the version to `LanguageParser(builder, languageVersion)`: the version is part of the context a body reparse restores.
 *
 * Changing the version of an open file does not reparse it by itself. Gates and tests that compare with `roslyndump`
 * (which parses with `--langversion`, `preview` by default) set [KEY] explicitly.
 */
object CSharpLanguageLevel {
    @JvmField val KEY: Key<CSharpLanguageVersion> = Key.create("csharp.languageVersion")

    /**
     * `Default` (effective [CSharpLanguageVersion.CSharp14]): what the .NET SDK uses for `net10.0`, the target framework
     * [CSharpPreprocessorSymbols.IDE_DEFAULT] assumes. Preview-only syntax (`union`, `closed`, `safe` as modifiers) is
     * therefore not parsed as such by default.
     */
    @JvmField val IDE_DEFAULT: CSharpLanguageVersion = CSharpLanguageVersion.Default

    /**
     * The version of [file]: [KEY] on the file or its virtual file, then on the file it is a copy of (a reparse after an
     * edit parses a copy whose virtual file is a `LightVirtualFile` with the original as `originalFile`, and whose
     * [PsiFile.getOriginalFile] is the original), else [IDE_DEFAULT]. The same walk as [CSharpPreprocessorSymbols.forFile].
     */
    @JvmStatic
    fun forFile(file: PsiFile?): CSharpLanguageVersion {
        var psi = file
        val seen = HashSet<Any>()
        while (psi != null && seen.add(psi)) {
            psi.getUserData(KEY)?.let { return it }
            // the file being indexed (stubs, step 8): see CSharpPreprocessorSymbols.forFile
            psi.virtualFile?.getUserData(KEY)?.let { return it }
            var virtualFile: VirtualFile? = psi.viewProvider.virtualFile
            while (virtualFile != null && seen.add(virtualFile)) {
                virtualFile.getUserData(KEY)?.let { return it }
                virtualFile = (virtualFile as? LightVirtualFile)?.originalFile
            }
            psi = psi.originalFile
        }
        return IDE_DEFAULT
    }

    /** The version a parser over [builder] uses: [KEY] on the builder, else [IDE_DEFAULT]. */
    @JvmStatic
    fun forBuilder(builder: PsiBuilder): CSharpLanguageVersion = builder.getUserData(KEY) ?: IDE_DEFAULT
}
