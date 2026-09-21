package io.github.dotnetsupport.lang

import com.intellij.application.options.CodeStyleAbstractConfigurable
import com.intellij.application.options.CodeStyleAbstractPanel
import com.intellij.application.options.IndentOptionsEditor
import com.intellij.application.options.SmartIndentOptionsEditor
import com.intellij.application.options.TabbedLanguageCodeStylePanel
import com.intellij.lang.Language
import com.intellij.openapi.ui.ComboBox
import com.intellij.psi.codeStyle.CodeStyleConfigurable
import com.intellij.psi.codeStyle.CodeStyleSettings
import com.intellij.psi.codeStyle.CommonCodeStyleSettings
import com.intellij.psi.codeStyle.LanguageCodeStyleSettingsProvider
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBUI
import io.github.dotnetsupport.settings.Unavailable
import java.awt.FlowLayout
import javax.swing.JComponent
import javax.swing.JPanel

/**
 * Settings | Editor | Code Style | C#. The indents are real: the editor indents with them, and the EditorConfig support of
 * the IDE overrides them from `indent_style` / `indent_size` of the repository. The rest of Rider's first tab is listed
 * locked: it needs a C# formatter inside the IDE, and here the code is formatted by CSharpier or `dotnet format`.
 */
class CSharpCodeStyleSettingsProvider : LanguageCodeStyleSettingsProvider() {
    override fun getLanguage(): Language = CSharpLanguage
    override fun getCodeSample(settingsType: SettingsType): String = SAMPLE
    override fun getIndentOptionsEditor(): IndentOptionsEditor = CSharpIndentOptionsEditor()

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

/** The indent options of the platform, then what Rider has on the same tab, locked. */
class CSharpIndentOptionsEditor : SmartIndentOptionsEditor() {
    override fun addComponents() {
        super.addComponents()
        add(header("Nested statements"))
        for (keyword in listOf("using", "fixed", "lock", "for", "foreach", "while")) add(locked(JBCheckBox("Indent nested '$keyword' statements")))
        add(header("Parenthesis"))
        add(locked(JBCheckBox("Use line continuation indent inside parentheses", true)))
        for (where in listOf("method declarations'", "primary constructor declarations'", "method calls'")) {
            add(locked(JBLabel("Indent $where parenthesis"), ComboBox(arrayOf("Inside parenthesis (BSD/K&R style)"))))
        }
        add(header("Other tabs of Rider"))
        add(locked(JBLabel("Naming, Syntax Style, Braces Layout, Blank Lines, Line Breaks and Wrapping, Spaces, Null Checking, XML Documentation, File Layout: in .editorconfig")))
    }

    private fun header(text: String): JComponent = JBLabel(text).apply { border = JBUI.Borders.emptyTop(10); font = JBUI.Fonts.label().asBold() }

    private fun locked(vararg components: JComponent): JComponent = JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(6), 0)).apply {
        components.forEach { it.isEnabled = false; it.toolTipText = Unavailable.NO_FORMATTER; add(it) }
        add(JBLabel(Unavailable.ICON).apply { toolTipText = Unavailable.NO_FORMATTER })
    }
}
