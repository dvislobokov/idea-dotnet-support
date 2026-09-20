package io.github.dotnetsupport.run

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.stream.JsonReader
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile
import java.io.File
import java.io.IOException
import java.io.StringReader

object LaunchSettings {
    private val TRAILING_COMMA = Regex(""",(\s*[}\]])""")

    class Profile(val name: String, val launchBrowser: Boolean, val launchUrl: String?, val applicationUrl: String? = null) {
        /** `https://localhost:7001;http://localhost:5000` lists every address the profile listens on. */
        val applicationUrls: List<String> get() = applicationUrl.orEmpty().split(';').map { it.trim().trimEnd('/') }.filter { it.isNotEmpty() }
    }

    /** Profiles `dotnet run --launch-profile` accepts, i.e. the ones with `"commandName": "Project"`. */
    fun profiles(json: String): List<Profile> {
        val root = try {
            // launchSettings.json is JSONC: the lenient reader takes the comments, trailing commas have to go.
            val text = json.replace(TRAILING_COMMA, "$1")
            JsonParser.parseReader(JsonReader(StringReader(text)).apply { isLenient = true }) as? JsonObject
        } catch (_: Exception) {
            null
        }
        val profiles = root?.get("profiles") as? JsonObject ?: return emptyList()
        return profiles.entrySet().mapNotNull { (name, value) ->
            val profile = value as? JsonObject ?: return@mapNotNull null
            fun primitive(key: String) = profile.get(key)?.takeIf { it.isJsonPrimitive }?.asJsonPrimitive
            if (primitive("commandName")?.asString != "Project") return@mapNotNull null
            Profile(name, launchBrowser = primitive("launchBrowser")?.let { it.isBoolean && it.asBoolean } == true, launchUrl = primitive("launchUrl")?.asString, applicationUrl = primitive("applicationUrl")?.asString)
        }
    }

    fun projectProfiles(json: String): List<String> = profiles(json).map { it.name }

    fun profiles(projectFile: VirtualFile): List<Profile> {
        val settings = projectFile.parent?.findFileByRelativePath("Properties/launchSettings.json") ?: return emptyList()
        return try {
            profiles(VfsUtilCore.loadText(settings))
        } catch (_: IOException) {
            emptyList()
        }
    }

    fun profiles(projectFile: File): List<Profile> {
        val settings = File(projectFile.parentFile, "Properties/launchSettings.json")
        return if (settings.isFile) profiles(settings.readText()) else emptyList()
    }

    fun projectProfiles(projectFile: VirtualFile): List<String> = profiles(projectFile).map { it.name }

    fun projectProfiles(projectFile: File): List<String> = profiles(projectFile).map { it.name }
}
