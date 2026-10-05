package io.github.dotnetsupport.lang

import com.intellij.lang.injection.MultiHostInjector
import com.intellij.lang.injection.MultiHostRegistrar
import com.intellij.psi.PsiElement
import org.intellij.lang.regexp.DefaultRegExpPropertiesProvider
import org.intellij.lang.regexp.RegExpLanguage
import org.intellij.lang.regexp.RegExpLanguageHost
import org.intellij.lang.regexp.psi.RegExpChar
import org.intellij.lang.regexp.psi.RegExpElement
import org.intellij.lang.regexp.psi.RegExpGroup
import org.intellij.lang.regexp.psi.RegExpNamedGroupRef
import java.util.EnumSet

/**
 * Injects the platform's RegExp language into the string literals [CSharpRegexPlaces] finds: its colors, completion (`\d`, `(?<name>`),
 * brace matching, inspections and Check RegExp come with it. Registered in META-INF/dotnet-regexp.xml, loaded where the IDE has the module
 * of RegExp (every IDE of the platform has it). A multi-line raw string is injected line by line without its indentation.
 */
class CSharpRegexInjector : MultiHostInjector {
    override fun elementsToInjectIn(): List<Class<out PsiElement>> = listOf(CSharpStringLiteralLeaf::class.java)

    override fun getLanguagesToInject(registrar: MultiHostRegistrar, context: PsiElement) {
        val host = context as? CSharpStringLiteralLeaf ?: return
        val shape = CSharpStringLiterals.shape(host.text) ?: return
        if (shape.contentEnd <= shape.contentStart || !CSharpRegexPlaces.isRegex(host)) return
        registrar.startInjecting(RegExpLanguage.INSTANCE)
        for (range in shape.lines) registrar.addPlace(null, null, host, range)
        registrar.doneInjecting()
    }
}

/** The .NET flavour of regular expressions for the RegExp language in a C# string: named groups `(?<n>)` and `(?'n')`, `\k<n>`, no possessive quantifiers. */
class CSharpRegExpHost : RegExpLanguageHost {
    private val properties = DefaultRegExpPropertiesProvider.getInstance()

    override fun characterNeedsEscaping(c: Char, isInClass: Boolean): Boolean = false
    override fun supportsPerl5EmbeddedComments(): Boolean = true
    override fun supportsPossessiveQuantifiers(): Boolean = false
    override fun supportsPythonConditionalRefs(): Boolean = false
    override fun supportsNamedGroupSyntax(group: RegExpGroup): Boolean = group.type == RegExpGroup.Type.NAMED_GROUP || group.type == RegExpGroup.Type.QUOTED_NAMED_GROUP
    override fun supportsNamedGroupRefSyntax(ref: RegExpNamedGroupRef): Boolean = !ref.isPythonNamedGroupRef
    override fun getSupportedNamedGroupTypes(context: RegExpElement?): EnumSet<RegExpGroup.Type> = EnumSet.of(RegExpGroup.Type.NAMED_GROUP, RegExpGroup.Type.QUOTED_NAMED_GROUP)
    override fun supportsExtendedHexCharacter(regExpChar: RegExpChar?): Boolean = false
    override fun supportConditionalCondition(condition: org.intellij.lang.regexp.psi.RegExpAtom?): Boolean = true
    override fun supportsLookbehind(group: RegExpGroup): RegExpLanguageHost.Lookbehind = RegExpLanguageHost.Lookbehind.FULL
    override fun isValidCategory(category: String): Boolean = properties.isValidCategory(category) || category.startsWith("Is")
    override fun getAllKnownProperties(): Array<Array<String>> = properties.allKnownProperties
    override fun getPropertyDescription(name: String?): String? = properties.getPropertyDescription(name)
    override fun getKnownCharacterClasses(): Array<Array<String>> = properties.knownCharacterClasses
}
