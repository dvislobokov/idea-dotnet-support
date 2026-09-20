package io.github.dotnetsupport.nuget

import com.intellij.credentialStore.CredentialAttributes
import com.intellij.credentialStore.Credentials
import com.intellij.credentialStore.generateServiceName
import com.intellij.ide.passwordSafe.PasswordSafe
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.ValidationInfo
import com.intellij.openapi.util.SystemInfo
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBPasswordField
import com.intellij.ui.components.JBTextField
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.panel
import java.awt.Dimension
import javax.swing.JComponent

/** What the source dialog edits. [password] is null when it is left as it is. */
class NuGetSourceSettings(
    val name: String,
    val url: String,
    val user: String?,
    val password: String?,
    val isEnabled: Boolean,
    val allowInsecureConnections: Boolean,
    val disableTlsCertificateValidation: Boolean,
) {
    /** `dotnet nuget add source` / `update source` for these settings; the flags the CLI has no option for are written by [NuGetConfigEditor]. */
    fun cliArguments(isNew: Boolean): List<String> = buildList {
        addAll(if (isNew) listOf("add", "source", url, "--name", name) else listOf("update", "source", name, "--source", url))
        if (!user.isNullOrEmpty() && !password.isNullOrEmpty()) {
            addAll(listOf("--username", user, "--password", password))
            // nuget.config passwords are encrypted with DPAPI, which exists on Windows only
            if (!SystemInfo.isWindows) add("--store-password-in-clear-text")
        }
        if (allowInsecureConnections) add("--allow-insecure-connections")
    }
}

/**
 * Attributes of a `<packageSources>` entry the CLI cannot set (`disableTLSCertificateValidation`) or cannot unset
 * (`allowInsecureConnections`). Works on the text so that the rest of the file, comments included, stays as it is.
 */
object NuGetConfigEditor {
    const val DISABLE_TLS = "disableTLSCertificateValidation"
    const val ALLOW_INSECURE = "allowInsecureConnections"

    private fun entry(sourceName: String) = Regex("""<add\s+(?=[^>]*\bkey\s*=\s*"${Regex.escape(escapeXml(sourceName))}")[^>]*?(/?)>""")

    private val SECTION = Regex("""<(/?)(packageSources|disabledPackageSources)\b""")

    /** Whether the source is declared in this config file. */
    fun declares(config: String, sourceName: String): Boolean = packageSourceEntry(config, sourceName) != null

    fun flag(config: String, sourceName: String, attribute: String): Boolean =
        packageSourceEntry(config, sourceName)?.value?.let { Regex("""\b$attribute\s*=\s*"true"""", RegexOption.IGNORE_CASE).containsMatchIn(it) } == true

    fun setFlag(config: String, sourceName: String, attribute: String, value: Boolean): String {
        val match = packageSourceEntry(config, sourceName) ?: return config
        var tag = match.value.replace(Regex("""\s+$attribute\s*=\s*"[^"]*"""", RegexOption.IGNORE_CASE), "")
        if (value) {
            val selfClosing = tag.endsWith("/>")
            tag = tag.removeSuffix(if (selfClosing) "/>" else ">").trimEnd() + " $attribute=\"true\"" + if (selfClosing) " />" else ">"
        }
        return config.replaceRange(match.range, tag)
    }

    /** The same `<add key="name">` appears in `<disabledPackageSources>` too: only the one inside `<packageSources>` counts. */
    private fun packageSourceEntry(config: String, sourceName: String): MatchResult? =
        entry(sourceName).findAll(config).firstOrNull { match ->
            SECTION.findAll(config.substring(0, match.range.first)).lastOrNull()?.let { it.groupValues[1].isEmpty() && it.groupValues[2] == "packageSources" } == true
        }

    private fun escapeXml(value: String): String = value.replace("&", "&amp;").replace("\"", "&quot;").replace("<", "&lt;").replace(">", "&gt;")
}

/**
 * Credentials of private feeds for the requests the plugin makes itself (search, versions, icons). The CLI does not
 * use them: it reads `nuget.config`, where `dotnet nuget add source --password` stores its own encrypted copy.
 */
object NuGetCredentialStore {
    private fun attributes(sourceUrl: String) = CredentialAttributes(generateServiceName("DotNet NuGet Source", sourceUrl.trimEnd('/').lowercase()))

    /** Slow: the password storage may ask for a master password. Not for EDT. */
    fun get(sourceUrl: String): Credentials? = PasswordSafe.instance.get(attributes(sourceUrl))?.takeIf { !it.userName.isNullOrEmpty() }

    fun set(sourceUrl: String, user: String?, password: String?) =
        PasswordSafe.instance.set(attributes(sourceUrl), if (user.isNullOrEmpty() || password.isNullOrEmpty()) null else Credentials(user, password))
}

/** "New feed" / "Edit feed", with the same fields as in Rider. */
class NuGetSourceDialog(project: Project, private val existing: NuGetSource?, private val otherNames: Set<String>, flags: Pair<Boolean, Boolean>) : DialogWrapper(project) {
    private val nameField = JBTextField(existing?.name ?: "New feed")
    private val urlField = JBTextField(existing?.url ?: "https://")
    private val userField = JBTextField()
    private val passwordField = JBPasswordField()
    private val enabled = JBCheckBox("", existing?.isEnabled ?: true)
    private val allowInsecure = JBCheckBox("", flags.first)
    private val disableTls = JBCheckBox("", flags.second)

    val settings: NuGetSourceSettings
        get() = NuGetSourceSettings(
            nameField.text.trim(), urlField.text.trim(),
            user = userField.text.trim().ifEmpty { null },
            password = String(passwordField.password).ifEmpty { null },
            isEnabled = enabled.isSelected,
            allowInsecureConnections = allowInsecure.isSelected,
            disableTlsCertificateValidation = disableTls.isSelected,
        )

    init {
        title = if (existing == null) "New NuGet Feed" else "Editing: ${existing.name}"
        // the CLI identifies a source by its name: renaming would be "remove and add", losing the stored credentials
        nameField.isEditable = existing == null
        init()
    }

    override fun getPreferredFocusedComponent(): JComponent = if (existing == null) nameField else urlField

    override fun createCenterPanel(): JComponent = panel {
        row("Name:") { cell(nameField).align(AlignX.FILL) }
        row("URL:") { cell(urlField).align(AlignX.FILL).comment("A V3 feed (<code>.../index.json</code>) or a folder with <code>.nupkg</code> files") }
        row("User:") { cell(userField).align(AlignX.FILL) }
        row("Password:") {
            cell(passwordField).align(AlignX.FILL)
                .comment(if (existing == null) "" else "Leave the user and the password empty to keep the stored credentials")
        }
        row("Enabled:") { cell(enabled) }
        row("Allow insecure connections:") { cell(allowInsecure).comment("HTTP feeds") }
        row("Disable TLS certificate validation:") { cell(disableTls) }
        row {
            val storage = if (SystemInfo.isWindows) "<b>nuget.config</b> (encrypted for your Windows account)" else "<b>nuget.config</b> in <b>clear text</b>: password encryption is Windows-only"
            comment("Credentials are saved to $storage, where the <code>dotnet</code> CLI reads them, and to the IDE password storage for the package search.")
        }
    }.apply { preferredSize = Dimension(560, preferredSize.height) }

    override fun doValidate(): ValidationInfo? {
        val current = settings
        return when {
            current.name.isEmpty() -> ValidationInfo("Specify the name of the feed", nameField)
            current.name.lowercase() in otherNames -> ValidationInfo("A feed with this name already exists", nameField)
            current.url.isEmpty() || current.url == "https://" -> ValidationInfo("Specify the URL or the folder of the feed", urlField)
            current.url.startsWith("http://") && !current.allowInsecureConnections -> ValidationInfo("An HTTP feed needs \"Allow insecure connections\"", allowInsecure)
            (current.user == null) != (current.password == null) -> ValidationInfo("Specify both the user and the password, or neither", if (current.user == null) userField else passwordField)
            else -> null
        }
    }
}
