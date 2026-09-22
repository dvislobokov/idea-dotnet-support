package io.github.dotnetsupport.lang

import com.intellij.application.options.CodeStyleAbstractConfigurable
import com.intellij.application.options.CodeStyleAbstractPanel
import com.intellij.application.options.IndentOptionsEditor
import com.intellij.application.options.SmartIndentOptionsEditor
import com.intellij.application.options.TabbedLanguageCodeStylePanel
import com.intellij.lang.Language
import com.intellij.psi.codeStyle.CodeStyleConfigurable
import com.intellij.psi.codeStyle.CodeStyleSettings
import com.intellij.psi.codeStyle.CommonCodeStyleSettings
import com.intellij.psi.codeStyle.LanguageCodeStyleSettingsProvider

/**
 * Settings | Editor | Code Style | C#. The indents are real: the editor indents with them, and the EditorConfig support of
 * the IDE overrides them from `indent_style` / `indent_size` of the repository. Nothing else: the rest of what Rider has here
 * needs a C# formatter inside the IDE, and here the code is formatted by CSharpier or `dotnet format` from `.editorconfig`.
 */
class CSharpCodeStyleSettingsProvider : LanguageCodeStyleSettingsProvider() {
    override fun getLanguage(): Language = CSharpLanguage
    override fun getCodeSample(settingsType: SettingsType): String = SAMPLE
    override fun getIndentOptionsEditor(): IndentOptionsEditor = SmartIndentOptionsEditor()

    override fun customizeDefaults(commonSettings: CommonCodeStyleSettings, indentOptions: CommonCodeStyleSettings.IndentOptions) {
        indentOptions.INDENT_SIZE = 4
        indentOptions.TAB_SIZE = 4
        // "Line continuation indent multiplier: 1" of Rider
        indentOptions.CONTINUATION_INDENT_SIZE = 4
    }

    override fun createConfigurable(baseSettings: CodeStyleSettings, modelSettings: CodeStyleSettings): CodeStyleConfigurable =
        object : CodeStyleAbstractConfigurable(baseSettings, modelSettings, "C#") {
            override fun createPanel(settings: CodeStyleSettings): CodeStyleAbstractPanel = object : TabbedLanguageCodeStylePanel(CSharpLanguage, currentSettings, settings) {
                // the other standard tabs (Spaces, Wrapping, Blank Lines) would promise a formatter
                override fun initTabs(settings: CodeStyleSettings) = addIndentOptionsTab(settings)
            }
        }

    private companion object {
        val SAMPLE = """
            namespace Shop;

            public class OrderService
            {
                public decimal Total(IEnumerable<OrderLine> lines, decimal discount)
                {
                    var total = lines
                        .Where(line => line.Quantity > 0)
                        .Sum(line => line.Price * line.Quantity);
                    if (discount > 0)
                    {
                        total -= total * discount;
                    }
                    return total;
                }
            }
        """.trimIndent()
    }
}
