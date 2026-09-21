package io.github.dotnetsupport.lang

import com.intellij.navigation.ChooseByNameContributorEx
import com.intellij.navigation.NavigationItem
import com.intellij.openapi.project.DumbAware
import com.intellij.psi.PsiManager
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.util.PsiTreeUtil
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

/**
 * Names of the types and members of every C# file, for Go to Class and Go to Symbol. The key carries the kind
 * (`T:OrderService`, `M:Total`), so that one index serves both; the declarations themselves are found again in the file.
 */
class CSharpDeclarationIndex : ScalarIndexExtension<String>() {
    override fun getName(): ID<String, Void> = NAME
    override fun getKeyDescriptor(): KeyDescriptor<String> = EnumeratorStringDescriptor.INSTANCE
    override fun getVersion(): Int = VERSION
    override fun dependsOnFileContent(): Boolean = true
    override fun getInputFilter(): FileBasedIndex.InputFilter = DefaultFileTypeSpecificInputFilter(CSharpFileType)

    override fun getIndexer(): DataIndexer<String, Void, FileContent> = DataIndexer { content ->
        CSharpDeclarations.scan(content.contentAsText).all().filter { it.kind != DeclarationKind.NAMESPACE }.associate { key(it.kind.isType, it.name) to null }
    }

    companion object {
        val NAME: ID<String, Void> = ID.create("dotnet.csharp.declarations")

        // bump when CSharpDeclarations starts to see declarations differently
        private const val VERSION = 1
        const val TYPE_PREFIX = "T:"
        const val MEMBER_PREFIX = "M:"

        fun key(isType: Boolean, name: String): String = (if (isType) TYPE_PREFIX else MEMBER_PREFIX) + name
    }
}

abstract class CSharpGotoContributor(private val types: Boolean, private val members: Boolean) : ChooseByNameContributorEx, DumbAware {
    override fun processNames(processor: Processor<in String>, scope: GlobalSearchScope, filter: IdFilter?) {
        FileBasedIndex.getInstance().processAllKeys(CSharpDeclarationIndex.NAME, { key ->
            val isType = key.startsWith(CSharpDeclarationIndex.TYPE_PREFIX)
            if (if (isType) types else members) processor.process(key.substring(CSharpDeclarationIndex.TYPE_PREFIX.length)) else true
        }, scope, filter)
    }

    override fun processElementsWithName(name: String, processor: Processor<in NavigationItem>, parameters: FindSymbolParameters) {
        val index = FileBasedIndex.getInstance()
        val keys = listOfNotNull(CSharpDeclarationIndex.key(true, name).takeIf { types }, CSharpDeclarationIndex.key(false, name).takeIf { members })
        val files = keys.flatMapTo(LinkedHashSet()) { index.getContainingFiles(CSharpDeclarationIndex.NAME, it, parameters.searchScope) }
        val psiManager = PsiManager.getInstance(parameters.project)
        for (file in files) {
            val psiFile = psiManager.findFile(file) ?: continue
            for (declaration in PsiTreeUtil.findChildrenOfType(psiFile, CSharpDeclaration::class.java)) {
                val matches = declaration.name == name && declaration.kind != DeclarationKind.NAMESPACE && (if (declaration.kind.isType) types else members)
                if (matches && !processor.process(declaration)) return
            }
        }
    }
}

/** Go to Class: classes, structs, interfaces, enums, records, delegates. */
class CSharpGotoClassContributor : CSharpGotoContributor(types = true, members = false)

/** Go to Symbol: the types and their members. */
class CSharpGotoSymbolContributor : CSharpGotoContributor(types = true, members = true)
