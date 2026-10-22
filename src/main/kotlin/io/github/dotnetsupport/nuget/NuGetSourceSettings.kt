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
import io.github.dotnetsupport.cli.PluginLog
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
    /**
     * `dotnet nuget add source` / `update source` for these settings; the flags the CLI has no option for are written by [NuGetConfigEditor].
     * The user and the password go to `nuget.config` only when they cannot go to the keychain of the OS ([NuGetKeychain]).
     */
    fun cliArguments(isNew: Boolean, credentialsInConfig: Boolean): List<String> = buildList {
        addAll(if (isNew) listOf("add", "source", url, "--name", name) else listOf("update", "source", name, "--source", url))
        if (credentialsInConfig && !user.isNullOrEmpty() && !password.isNullOrEmpty()) {
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

    /** A source's entry of `<packageSourceCredentials>` with a `ClearTextPassword`. */
    data class CredentialEntry(val sourceName: String, val user: String, val password: String)

    private val CREDENTIALS_SECTION = Regex("""(?s)<packageSourceCredentials\b[^>]*>(.*?)</packageSourceCredentials\s*>""")
    private val ELEMENT = Regex("""(?s)<([\w.\-]+)\b[^>]*?(?:/>|>(.*?)</\1\s*>)""")
    private val ADD = Regex("""<add\b([^>]*?)/?>""")
    private val ATTRIBUTE = Regex("""([\w:]+)\s*=\s*"([^"]*)"""")

    /**
     * The users and passwords kept in clear text in this config. A value with `%NAME%` in it is a reference to an environment variable (as
     * CI writes them), not a password, and is left out; so is an encrypted `Password` (Windows), which is no clear text.
     */
    fun clearTextCredentials(config: String): List<CredentialEntry> =
        CREDENTIALS_SECTION.findAll(config).flatMap { section -> ELEMENT.findAll(section.groupValues[1]) }.mapNotNull { element ->
            val values = ADD.findAll(element.groupValues[2]).associate { add ->
                val attributes = ATTRIBUTE.findAll(add.groupValues[1]).associate { it.groupValues[1].lowercase() to unescapeXml(it.groupValues[2]) }
                (attributes["key"] ?: "").lowercase() to (attributes["value"] ?: "")
            }
            val user = values["username"] ?: return@mapNotNull null
            val password = values["cleartextpassword"]?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            if (ENVIRONMENT_REFERENCE.containsMatchIn(password)) null else CredentialEntry(decodeName(element.groupValues[1]), user, password)
        }.toList()

    private val ENVIRONMENT_REFERENCE = Regex("""%[^%\s]+%""")

    /**
     * The config without the credentials of [sourceName] (its element in `<packageSourceCredentials>`, with the indentation before it), and
     * without the section when nothing is left in it. The rest of the file stays as it is.
     */
    fun removeCredentials(config: String, sourceName: String): String {
        val name = encodeName(sourceName)
        val section = CREDENTIALS_SECTION.findAll(config).firstOrNull { s -> ELEMENT.findAll(s.groupValues[1]).any { it.groupValues[1].equals(name, ignoreCase = true) } }
            ?: return config
        val body = section.groups[1]!!
        val element = ELEMENT.findAll(body.value).first { it.groupValues[1].equals(name, ignoreCase = true) }
        val start = body.range.first + element.range.first
        val lineStart = config.lastIndexOf('\n', start - 1).let { if (it >= 0 && config.substring(it + 1, start).isBlank()) it else start }
        val without = config.removeRange(lineStart, body.range.first + element.range.last + 1)
        // the section left with whitespace only goes too
        // the section starts where it did: what was removed is inside it
        val emptied = CREDENTIALS_SECTION.find(without, section.range.first)?.takeIf { it.groupValues[1].isBlank() } ?: return without
        val sectionStart = without.lastIndexOf('\n', emptied.range.first - 1).let { if (it >= 0 && without.substring(it + 1, emptied.range.first).isBlank()) it else emptied.range.first }
        return without.removeRange(sectionStart, emptied.range.last + 1)
    }

    /** The element name NuGet gives a source in `<packageSourceCredentials>` (`XmlConvert.EncodeLocalName`): `My Feed` → `My_x0020_Feed`. */
    fun encodeName(name: String): String = buildString {
        name.forEachIndexed { i, c ->
            val valid = c.isLetter() || c == '_' || i > 0 && (c.isDigit() || c == '.' || c == '-')
            if (valid) append(c) else append("_x%04X_".format(c.code))
        }
    }

    private fun decodeName(name: String): String = Regex("_x([0-9A-Fa-f]{4})_").replace(name) { it.groupValues[1].toInt(16).toChar().toString() }

    private fun unescapeXml(value: String): String = Regex("&(#x[0-9A-Fa-f]+|#[0-9]+|amp|lt|gt|quot|apos);").replace(value) {
        when (val entity = it.groupValues[1]) {
            "amp" -> "&"; "lt" -> "<"; "gt" -> ">"; "quot" -> "\""; "apos" -> "'"
            else -> (if (entity.startsWith("#x")) entity.drop(2).toInt(16) else entity.drop(1).toInt()).toChar().toString()
        }
    }
}

/**
 * Credentials of private feeds for the requests the plugin makes itself (search, versions, icons). Where [NuGetKeychain] works they are
 * the ones of the keychain of the OS, which the CLI gets through the credential provider of the plugin; elsewhere they are a copy in the
 * password storage of the IDE, and the CLI reads its own from `nuget.config`. A copy stored there by an older version is still read.
 *
 * The storage is asked only about a source that needs it ([NuGetCredentialPolicy]): asked on every request to nuget.org, it
 * failed on Linux without a keychain (libsecret / KWallet) with an "IDE error occurred" blaming the plugin (DEV_JOURNEY 3.2).
 */
object NuGetCredentialStore {
    private const val KNOWN_KEY = "io.github.dotnetsupport.nuget.sourcesWithCredentials"

    private fun attributes(sourceUrl: String) = CredentialAttributes(generateServiceName("DotNet NuGet Source", sourceUrl.trimEnd('/').lowercase()))

    private fun known(): Set<String> =
        com.intellij.ide.util.PropertiesComponent.getInstance().getList(KNOWN_KEY).orEmpty().toSet()

    private fun setKnown(sourceUrl: String, has: Boolean) {
        val key = NuGetCredentialPolicy.key(sourceUrl)
        val updated = if (has) known() + key else known() - key
        com.intellij.ide.util.PropertiesComponent.getInstance().setList(KNOWN_KEY, updated.sorted())
    }

    val policy = NuGetCredentialPolicy(lookup = { lookup(it) }, known = ::known)

    private fun lookup(sourceUrl: String): Credentials? {
        val keychain = if (NuGetKeychain.isReady()) runCatching { NuGetKeychain.get(sourceUrl) }
            .onFailure { PluginLog.warn(NuGetClient.LOG_CATEGORY, "the keychain of the OS for $sourceUrl", it) }.getOrNull() else null
        return keychain ?: PasswordSafe.instance.get(attributes(sourceUrl))?.takeIf { c -> !c.userName.isNullOrEmpty() }
    }

    /** Slow when the storage is asked: it may ask for a master password. Not for EDT. Null without asking for a source that needs no credentials. */
    fun get(sourceUrl: String): Credentials? = policy.get(sourceUrl)

    /** What [get] would return, without ever asking the storage: for the journal. */
    fun cached(sourceUrl: String): Credentials? = policy.cached(sourceUrl)

    /** The feed answered 401 / 403: from now on the storage is asked about it. */
    fun unauthorized(sourceUrl: String) = policy.unauthorized(sourceUrl)

    /**
     * Stores (or, without a user and a password, forgets) the credentials of [sourceUrl]: in the keychain of the OS when [keychain], where
     * the CLI finds them too, else in the password storage of the IDE. Not for EDT. Throws [NuGetKeychainException] when the keychain refuses.
     */
    fun set(sourceUrl: String, user: String?, password: String?, keychain: Boolean = false) {
        val credentials = if (user.isNullOrEmpty() || password.isNullOrEmpty()) null else Credentials(user, password)
        // nothing stored and nothing to store: the storage is not touched (a new feed without a password)
        if (credentials == null && NuGetCredentialPolicy.key(sourceUrl) !in known() && policy.cached(sourceUrl) == null) return policy.forget(sourceUrl)
        if (keychain) {
            if (credentials != null) NuGetKeychain.store(sourceUrl, user!!, password!!) else NuGetKeychain.erase(sourceUrl)
            // the copy of an older version must not outlive the one in the keychain
            if (NuGetCredentialPolicy.key(sourceUrl) in known()) PasswordSafe.instance.set(attributes(sourceUrl), null)
        } else {
            PasswordSafe.instance.set(attributes(sourceUrl), credentials)
        }
        setKnown(sourceUrl, credentials != null)
        policy.remember(sourceUrl, credentials)
    }

    /** Whether the plugin keeps credentials of any source: then the provider is kept up to date in NuGet's folder at the start. */
    fun hasAny(): Boolean = known().isNotEmpty()
}

/**
 * When the password storage is asked about a feed, as a credential provider of NuGet is: for a source the plugin stored credentials for
 * ([known]), or one that has answered 401 / 403 in this session; never for nuget.org, which is anonymous. Answers, empty ones too, are
 * cached: one question per source and session.
 */
class NuGetCredentialPolicy(private val lookup: (String) -> Credentials?, private val known: () -> Set<String>) {
    private object None

    private val answers = java.util.concurrent.ConcurrentHashMap<String, Any>()
    private val unauthorized = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    fun needsCredentials(sourceUrl: String): Boolean {
        val key = key(sourceUrl)
        return !isAnonymous(sourceUrl) && (key in unauthorized || key in known())
    }

    fun get(sourceUrl: String): Credentials? {
        if (!needsCredentials(sourceUrl)) return null
        val key = key(sourceUrl)
        val answer = answers.getOrPut(key) { runCatching { lookup(sourceUrl) }.getOrNull() ?: None }
        return answer as? Credentials
    }

    fun cached(sourceUrl: String): Credentials? = answers[key(sourceUrl)] as? Credentials

    fun unauthorized(sourceUrl: String) {
        val key = key(sourceUrl)
        // a 401 after an empty answer: the user may have stored the credentials since; asked once more
        if (unauthorized.add(key) || answers[key] === None) answers.remove(key)
    }

    fun remember(sourceUrl: String, credentials: Credentials?) {
        answers[key(sourceUrl)] = credentials ?: None
    }

    fun forget(sourceUrl: String) {
        answers.remove(key(sourceUrl))
    }

    companion object {
        private val ANONYMOUS_HOSTS = setOf("api.nuget.org", "www.nuget.org", "nuget.org")

        fun key(sourceUrl: String): String = sourceUrl.trim().trimEnd('/').lowercase()

        /** nuget.org: public, never asks for credentials. */
        fun isAnonymous(sourceUrl: String): Boolean =
            runCatching { java.net.URI(sourceUrl.trim()).host?.lowercase() }.getOrNull() in ANONYMOUS_HOSTS
    }
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
            val fallback = if (SystemInfo.isWindows) "to <b>nuget.config</b>, encrypted for your Windows account" else "to <b>nuget.config</b> in clear text, after asking"
            comment("Credentials are saved to the keychain of the system, not to nuget.config: every <code>dotnet</code> command finds them there, in the IDE " +
                "and in a terminal, through the NuGet credential provider the IDE installs. Where there is no keychain, they go $fallback.")
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
