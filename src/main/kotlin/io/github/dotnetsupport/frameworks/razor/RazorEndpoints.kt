package io.github.dotnetsupport.frameworks.razor

import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile
import io.github.dotnetsupport.endpoints.Endpoint
import io.github.dotnetsupport.endpoints.EndpointSource

/**
 * The routes of Razor in the Endpoints tool window (RAZOR_PLAN.md, R9): a routable component's `@page "/counter"` (every one of them), a
 * Razor Page's route — the path of the `.cshtml` under `Pages/` (`Pages/Products/Index.cshtml` → `/Products`), with the template of its
 * `@page "{id:int}"` appended, or that template itself when it starts with `/` — as ASP.NET Core routes them. A view of MVC (no `@page`)
 * is no endpoint of its own.
 */
class RazorEndpoints : EndpointSource {
    override fun handles(file: VirtualFile): Boolean = file.extension.equals("razor", ignoreCase = true) || file.extension.equals("cshtml", ignoreCase = true)

    override fun endpoints(file: VirtualFile, text: CharSequence, projectDirectory: VirtualFile): List<Endpoint> {
        val component = file.nameWithoutExtension
        if (file.extension.equals("razor", ignoreCase = true)) {
            return PAGE.findAll(text).mapNotNull { m ->
                val route = m.groups[1] ?: return@mapNotNull null
                Endpoint("GET", route.value, route.range.first, component)
            }.toList()
        }
        // a Razor Page: `@page` among the first directives
        val directive = PAGE_OF_PAGE.find(text) ?: return emptyList()
        val template = directive.groups[1]?.value
        val relative = VfsUtilCore.getRelativePath(file, projectDirectory, '/') ?: return emptyList()
        val route = pageRoute(relative, template) ?: return emptyList()
        return listOf(Endpoint("GET", route, directive.groups[1]?.range?.first ?: directive.range.first, component))
    }

    companion object {
        private val PAGE = Regex("""(?m)^[ \t]*@page[ \t]+"([^"\r\n]*)"""")
        private val PAGE_OF_PAGE = Regex("""(?m)^[ \t]*@page(?:[ \t]+"([^"\r\n]*)")?[ \t]*$""")

        /**
         * The route of the Razor Page at [relativePath] (in its project) with the template of its `@page`: null outside `Pages/` (an Area's
         * `Areas/<area>/Pages/…` is `/<area>/…`). `Index` is the folder's route.
         */
        fun pageRoute(relativePath: String, template: String?): String? {
            val parts = relativePath.removeSuffix(".cshtml").split('/')
            val path = when {
                parts.firstOrNull() == "Pages" -> parts.drop(1)
                parts.size > 3 && parts[0] == "Areas" && parts[2] == "Pages" -> listOf(parts[1]) + parts.drop(3)
                else -> return null
            }.let { if (it.lastOrNull().equals("Index", ignoreCase = true)) it.dropLast(1) else it }
            if (template != null && template.startsWith("/")) return template
            val base = "/" + path.joinToString("/")
            if (template.isNullOrEmpty()) return base
            return base.trimEnd('/') + "/" + template.trimStart('/')
        }
    }
}
