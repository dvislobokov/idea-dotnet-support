package io.github.dotnetsupport.solution

import com.intellij.openapi.util.JDOMUtil
import org.jdom.Element

object SolutionParser {
    private const val SOLUTION_FOLDER_TYPE = "2150E333-8FDC-42A3-9474-1A3956D46DE8"

    // Project("{type}") = "Name", "path\Name.csproj", "{id}"
    private val PROJECT_LINE =
        Regex("""^Project\("\{([^}]+)}"\)\s*=\s*"([^"]*)"\s*,\s*"([^"]*)"\s*,\s*"\{([^}]+)}"""")
    private val NESTING_LINE = Regex("""^\{([^}]+)}\s*=\s*\{([^}]+)}""")

    fun parse(text: CharSequence, extension: String?): Solution =
        if (extension.equals("slnx", ignoreCase = true)) parseSlnx(text) else parseSln(text)

    fun parseSln(text: CharSequence): Solution {
        val folders = LinkedHashMap<String, SlnFolder>()
        val projects = LinkedHashMap<String, SlnProject>()
        val parents = HashMap<String, String>()

        var currentFolder: SlnFolder? = null
        var inSolutionItems = false
        var inNestedProjects = false
        var inConfigurations = false
        val configurations = LinkedHashSet<String>()

        for (rawLine in text.lineSequence()) {
            val line = rawLine.trim()
            val projectMatch = PROJECT_LINE.find(line)
            when {
                projectMatch != null -> {
                    val (type, name, path, rawId) = projectMatch.destructured
                    val id = rawId.uppercase()
                    if (type.equals(SOLUTION_FOLDER_TYPE, ignoreCase = true)) {
                        currentFolder = SlnFolder(name, id).also { folders[id] = it }
                    } else {
                        currentFolder = null
                        projects[id] = SlnProject(name, normalizePath(path), id)
                    }
                }
                line == "EndProject" -> currentFolder = null
                line.startsWith("ProjectSection(SolutionItems)") -> inSolutionItems = true
                line == "EndProjectSection" -> inSolutionItems = false
                line.startsWith("GlobalSection(NestedProjects)") -> inNestedProjects = true
                line.startsWith("GlobalSection(SolutionConfigurationPlatforms)") -> inConfigurations = true
                line == "EndGlobalSection" -> {
                    inNestedProjects = false
                    inConfigurations = false
                }
                // Debug|Any CPU = Debug|Any CPU
                inConfigurations -> line.substringBefore('|').trim().takeIf { it.isNotEmpty() }?.let(configurations::add)
                inSolutionItems -> {
                    val file = line.substringBefore('=').trim()
                    if (file.isNotEmpty()) currentFolder?.files?.add(normalizePath(file))
                }
                inNestedProjects -> NESTING_LINE.find(line)?.let {
                    parents[it.groupValues[1].uppercase()] = it.groupValues[2].uppercase()
                }
            }
        }

        val root = SlnFolder("", Solution.ROOT_ID)
        for (folder in folders.values) (folders[parents[folder.id]] ?: root).folders += folder
        for (project in projects.values) (folders[parents[project.id]] ?: root).projects += project
        return Solution(root, configurations.toList())
    }

    fun parseSlnx(text: CharSequence): Solution {
        val root = SlnFolder("", Solution.ROOT_ID)
        val xml = try {
            JDOMUtil.load(text)
        } catch (_: Exception) {
            return Solution(root) // the file is being edited and is not well-formed at the moment
        }

        readSlnxItems(xml, root)
        for (folderElement in xml.getChildren("Folder")) {
            // Folders are declared flat, nesting is encoded in the name: "/src/Libraries/"
            val segments = folderElement.getAttributeValue("Name").orEmpty().split('/').filter { it.isNotBlank() }
            var folder = root
            var id = "/"
            for (segment in segments) {
                id += "$segment/"
                folder = folder.folders.find { it.name == segment }
                    ?: SlnFolder(segment, id).also { folder.folders += it }
            }
            if (folder !== root) readSlnxItems(folderElement, folder)
        }
        // <Configurations><BuildType Name="Debug" /></Configurations>; absent when the defaults are used
        val configurations = xml.getChild("Configurations")?.getChildren("BuildType").orEmpty().mapNotNull { it.getAttributeValue("Name") }
        return Solution(root, configurations)
    }

    private fun readSlnxItems(element: Element, folder: SlnFolder) {
        for (projectElement in element.getChildren("Project")) {
            val path = normalizePath(projectElement.getAttributeValue("Path") ?: continue)
            val name = projectElement.getAttributeValue("DisplayName")
                ?: path.substringAfterLast('/').substringBeforeLast('.')
            folder.projects += SlnProject(name, path, path)
        }
        for (fileElement in element.getChildren("File")) {
            folder.files += normalizePath(fileElement.getAttributeValue("Path") ?: continue)
        }
    }

    private fun normalizePath(path: String): String = path.trim().replace('\\', '/')
}
