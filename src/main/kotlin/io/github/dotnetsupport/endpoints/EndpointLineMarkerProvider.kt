package io.github.dotnetsupport.endpoints

import com.intellij.codeInsight.daemon.LineMarkerInfo
import com.intellij.codeInsight.daemon.LineMarkerProviderDescriptor
import com.intellij.icons.AllIcons
import com.intellij.openapi.editor.markup.GutterIconRenderer
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.util.CachedValueProvider
import com.intellij.psi.util.CachedValuesManager
import io.github.dotnetsupport.lang.CSharpFile
import io.github.dotnetsupport.msbuild.DotNetProjects
import javax.swing.Icon

/** Endpoints of a file, scanned once per change of the file rather than once per token the gutter asks about. */
internal fun endpointsOf(file: PsiFile): List<Endpoint> = CachedValuesManager.getCachedValue(file) {
    CachedValueProvider.Result.create(EndpointScanner.scan(file.viewProvider.contents), file)
}

/** A globe in the gutter next to every route; a click opens (creating it when needed) the request in the `.http` file of the project. */
class EndpointLineMarkerProvider : LineMarkerProviderDescriptor() {
    override fun getName(): String = "ASP.NET endpoint"
    override fun getIcon(): Icon = AllIcons.General.Web

    override fun getLineMarkerInfo(element: PsiElement): LineMarkerInfo<*>? {
        if (element.firstChild != null) return null // markers belong to leaves
        val file = element.containingFile as? CSharpFile ?: return null
        val offset = element.textRange.startOffset
        val endpoints = endpointsOf(file).filter { it.offset == offset }
        val endpoint = endpoints.firstOrNull() ?: return null

        val tooltip = endpoints.joinToString("<br>") { "${it.method} ${it.route}" } + "<br><i>Click to open the HTTP request</i>"
        return LineMarkerInfo(
            element, element.textRange, AllIcons.General.Web, { tooltip },
            { _, leaf ->
                val virtualFile = leaf.containingFile.virtualFile
                DotNetProjects.findOwningProject(virtualFile)?.let { projectFile ->
                    EndpointRequests.openRequest(leaf.project, EndpointsOfProject(projectFile.nameWithoutExtension, projectFile, emptyList()), endpoint)
                }
            },
            GutterIconRenderer.Alignment.LEFT, { "${endpoint.method} ${endpoint.route}" },
        )
    }
}
