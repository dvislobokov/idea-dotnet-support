package io.github.dotnetsupport.lang

import com.intellij.navigation.ChooseByNameContributorEx
import com.intellij.navigation.NavigationItem
import com.intellij.openapi.project.DumbAware
import com.intellij.psi.PsiManager
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.impl.source.PsiFileWithStubSupport
import com.intellij.psi.stubs.StubIndex
import com.intellij.psi.stubs.StubUpdatingIndex
import com.intellij.util.Processor
import com.intellij.util.indexing.DataIndexer
import com.intellij.util.indexing.DefaultFileTypeSpecificInputFilter
import com.intellij.util.indexing.FileBasedIndex
import com.intellij.util.indexing.FileContent
import com.intellij.util.indexing.FindSymbolParameters
import com.intellij.util.indexing.ID
import com.intellij.util.indexing.IdFilter
import com.intellij.util.indexing.ScalarIndexExtension
import com.intellij.util.io.EnumeratorStringDescriptor
import com.intellij.util.io.KeyDescriptor
import io.github.dotnetsupport.csharp.lang.psi.CSharpElement
import io.github.dotnetsupport.csharp.lang.psi.stubs.CSharpStubIndexKeys
import io.github.dotnetsupport.lsp.RoslynServerStatus
import java.util.IdentityHashMap

/**
 * Names of the types and members of every C# file of the heuristic tree (SYNTAX_TREE = ROSLYN), for Go to Class and Go to Symbol. The key
 * carries the kind (`T:OrderService`, `M:Total`), so that one index serves both; the declarations themselves are found again in the file.
 * With the native tree the stub indexes of csharp-psi serve them (CSHARP_PSI_MIGRATION.md, step 8) and this index is empty: a file is not
 * parsed twice while it is indexed.
 */
class CSharpDeclarationIndex : ScalarIndexExtension<String>() {
    override fun getName(): ID<String, Void> = NAME
    override fun getKeyDescriptor(): KeyDescriptor<String> = EnumeratorStringDescriptor.INSTANCE
    override fun getVersion(): Int = VERSION
    override fun dependsOnFileContent(): Boolean = true
    override fun getInputFilter(): FileBasedIndex.InputFilter = DefaultFileTypeSpecificInputFilter(CSharpFileType)

    override fun getIndexer(): DataIndexer<String, Void, FileContent> = DataIndexer { content ->
        if (CSharpSyntaxTrees.nativeTree()) emptyMap()
        else CSharpSyntaxModel.current.declarations(content.contentAsText).all().filter { it.kind != DeclarationKind.NAMESPACE }.associate { key(it.kind.isType, it.name) to null }
    }

    companion object {
        val NAME: ID<String, Void> = ID.create("dotnet.csharp.declarations")

        // bump when the heuristic model starts to see declarations differently; 2: the native model (one key per field declarator); 3: the heuristic tree only
        private const val VERSION = 3
        const val TYPE_PREFIX = "T:"
        const val MEMBER_PREFIX = "M:"

        fun key(isType: Boolean, name: String): String = (if (isType) TYPE_PREFIX else MEMBER_PREFIX) + name

        /**
         * Re-indexes every C# file for the tree the switch now gives: this index and the stubs (the platform builds stubs of a C# file only
         * while the registered parser definition gives the native file element type). The version of an index cannot follow a setting. To
         * be called when the switch moves (`CSharpSyntaxTreeSwitch`).
         */
        fun requestRebuild() {
            FileBasedIndex.getInstance().requestRebuild(NAME)
            FileBasedIndex.getInstance().requestRebuild(StubUpdatingIndex.INDEX_ID)
        }
    }
}

/**
 * Go to Class / Go to Symbol. With the native tree from the stub indexes of csharp-psi (types by name, members by name): the items are the
 * stub-based declarations, shown from their stubs, so the files they are in are not parsed (`NativeCSharpPresentationProvider`); with the
 * heuristic tree from [CSharpDeclarationIndex], the declarations found again in the file. Where the language server is ready and knows the
 * file, it answers the same question through `workspace/symbol` of the platform, and both answers were shown as two rows of one type: those
 * files are left to it (`RoslynServerStatus.covers`). Everything else — while the server loads, a project that is in no solution, a loose
 * file — stays here.
 */
abstract class CSharpGotoContributor(private val types: Boolean, private val members: Boolean) : ChooseByNameContributorEx, DumbAware {
    private val stubKeys = listOfNotNull(CSharpStubIndexKeys.TYPE_NAMES.takeIf { types }, CSharpStubIndexKeys.MEMBER_NAMES.takeIf { members })

    override fun processNames(processor: Processor<in String>, scope: GlobalSearchScope, filter: IdFilter?) {
        if (CSharpSyntaxTrees.nativeTree()) {
            for (key in stubKeys) if (!StubIndex.getInstance().processAllKeys(key, processor, scope, filter)) return
            return
        }
        FileBasedIndex.getInstance().processAllKeys(CSharpDeclarationIndex.NAME, { key ->
            val isType = key.startsWith(CSharpDeclarationIndex.TYPE_PREFIX)
            if (if (isType) types else members) processor.process(key.substring(CSharpDeclarationIndex.TYPE_PREFIX.length)) else true
        }, scope, filter)
    }

    override fun processElementsWithName(name: String, processor: Processor<in NavigationItem>, parameters: FindSymbolParameters) {
        val project = parameters.project
        if (CSharpSyntaxTrees.nativeTree()) {
            val found = ArrayList<CSharpElement>()
            for (key in stubKeys) StubIndex.getInstance().processElements(key, name, project, parameters.searchScope, CSharpElement::class.java) { element ->
                val file = element.containingFile?.virtualFile
                if (file == null || !RoslynServerStatus.covers(project, file)) found += element
                true
            }
            for (element in inTextOrder(found)) if (!processor.process(element as NavigationItem)) return
            return
        }
        val index = FileBasedIndex.getInstance()
        val keys = listOfNotNull(CSharpDeclarationIndex.key(true, name).takeIf { types }, CSharpDeclarationIndex.key(false, name).takeIf { members })
        val files = keys.flatMapTo(LinkedHashSet()) { index.getContainingFiles(CSharpDeclarationIndex.NAME, it, parameters.searchScope) }
        val psiManager = PsiManager.getInstance(project)
        for (file in files) {
            if (RoslynServerStatus.covers(project, file)) continue
            val psiFile = psiManager.findFile(file) ?: continue
            if (!process(psiFile, name, processor)) return
        }
    }

    /**
     * Types and members of one name (Go to Symbol asks both indexes) as the heuristic path lists them: by file, in the order of the stubs (that
     * of the text) within a file. The stubbed spine is the stub tree's while the AST is not loaded.
     */
    private fun inTextOrder(elements: List<CSharpElement>): List<CSharpElement> {
        if (stubKeys.size < 2) return elements
        val byFile = LinkedHashMap<PsiFile?, MutableList<CSharpElement>>()
        for (element in elements) byFile.getOrPut(element.containingFile) { ArrayList() } += element
        return byFile.flatMap { (file, list) ->
            if (list.size < 2) return@flatMap list
            val spine = (file as? PsiFileWithStubSupport)?.stubbedSpine ?: return@flatMap list
            val order = IdentityHashMap<PsiElement, Int>().also { order -> for (i in 0 until spine.stubCount) spine.getStubPsi(i)?.let { order[it] = i } }
            list.sortedBy { order[it] ?: Int.MAX_VALUE }
        }
    }

    /** The declarations named [name] under [parent], in the order of the text; false when [processor] wants no more. */
    private fun process(parent: PsiElement, name: String, processor: Processor<in NavigationItem>): Boolean {
        val model = CSharpSyntaxModel.current
        for (element in model.childDeclarations(parent)) {
            val declaration = model.declarationOf(element)
            val matches = declaration != null && declaration.name == name && declaration.kind != DeclarationKind.NAMESPACE && (if (declaration.kind.isType) types else members)
            if (matches && !processor.process(element)) return false
            if (!process(element, name, processor)) return false
        }
        return true
    }
}

/** Go to Class: classes, structs, interfaces, enums, records, delegates. */
class CSharpGotoClassContributor : CSharpGotoContributor(types = true, members = false)

/** Go to Symbol: the types and their members. */
class CSharpGotoSymbolContributor : CSharpGotoContributor(types = true, members = true)
