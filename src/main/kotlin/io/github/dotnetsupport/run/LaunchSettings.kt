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

    /** Names of the profiles `dotnet run --launch-profile` accepts, i.e. the ones with `"commandName": "Project"`. */
    fun projectProfiles(json: String): List<String> {
        val root = try {
            // launchSettings.json is JSONC: the lenient reader takes the comments, trailing commas have to go.
            val text = json.replace(TRAILING_COMMA, "$1")
            JsonParser.parseReader(JsonReader(StringReader(text)).apply { isLenient = true }) as? JsonObject
        } catch (_: Exception) {
            null
        }
        val profiles = root?.get("profiles") as? JsonObject ?: return emptyList()
        return profiles.entrySet()
            .filter { (_, profile) -> (profile as? JsonObject)?.get("commandName")?.takeIf { it.isJsonPrimitive }?.asString == "Project" }
            .map { it.key }
    }

    fun projectProfiles(projectFile: VirtualFile): List<String> {
        val settings = projectFile.parent?.findFileByRelativePath("Properties/launchSettings.json") ?: return emptyList()
        return try {
            projectProfiles(VfsUtilCore.loadText(settings))
        } catch (_: IOException) {
            emptyList()
        }
    }

    fun projectProfiles(projectFile: File): List<String> {
        val settings = File(projectFile.parentFile, "Properties/launchSettings.json")
        return if (settings.isFile) projectProfiles(settings.readText()) else emptyList()
    }
}
