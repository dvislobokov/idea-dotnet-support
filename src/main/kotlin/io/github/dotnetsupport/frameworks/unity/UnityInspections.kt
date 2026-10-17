package io.github.dotnetsupport.frameworks.unity

import com.intellij.codeInspection.LocalInspectionTool
import com.intellij.codeInspection.LocalQuickFix
import com.intellij.codeInspection.ProblemDescriptor
import com.intellij.codeInspection.ProblemHighlightType
import com.intellij.codeInspection.ProblemsHolder
import com.intellij.openapi.editor.Document
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiElementVisitor
import com.intellij.psi.PsiFile
import com.intellij.psi.util.PsiTreeUtil
import io.github.dotnetsupport.csharp.lang.psi.CSharpAnonymousFunctionExpression
import io.github.dotnetsupport.csharp.lang.psi.CSharpAssignmentExpression
import io.github.dotnetsupport.csharp.lang.psi.CSharpAttribute
import io.github.dotnetsupport.csharp.lang.psi.CSharpAttributeList
import io.github.dotnetsupport.csharp.lang.psi.CSharpBaseFieldDeclaration
import io.github.dotnetsupport.csharp.lang.psi.CSharpBaseTypeDeclaration
import io.github.dotnetsupport.csharp.lang.psi.CSharpBinaryExpression
import io.github.dotnetsupport.csharp.lang.psi.CSharpConditionalAccessExpression
import io.github.dotnetsupport.csharp.lang.psi.CSharpExpression
import io.github.dotnetsupport.csharp.lang.psi.CSharpGenericName
import io.github.dotnetsupport.csharp.lang.psi.CSharpInvocationExpression
import io.github.dotnetsupport.csharp.lang.psi.CSharpLiteralExpression
import io.github.dotnetsupport.csharp.lang.psi.CSharpLocalFunctionStatement
import io.github.dotnetsupport.csharp.lang.psi.CSharpMemberAccessExpression
import io.github.dotnetsupport.csharp.lang.psi.CSharpMethodDeclaration
import io.github.dotnetsupport.csharp.lang.psi.CSharpParameter
import io.github.dotnetsupport.csharp.lang.psi.CSharpSimpleName
import io.github.dotnetsupport.csharp.lang.psi.CSharpThisExpression
import io.github.dotnetsupport.lang.CSharpFile
import io.github.dotnetsupport.lang.semantic.CSharpSemanticSession

/**
 * The inspections of Unity (UNITY_PLAN.md, "плюшки", as Rider's Unity support has them), in the files of a Unity project only. Each is
 * one `LocalInspectionTool` (Settings | Editor | Inspections | C# | Unity), the semantics of the plugin decides the types.
 */
internal object UnityInspectionSupport {
    fun isUnityFile(file: PsiFile): Boolean = file is CSharpFile && file.originalFile.virtualFile?.let(UnityProjects::rootOf) != null

    /** The method [element] is in when no lambda or local function is between them. */
    fun enclosingMethod(element: PsiElement): CSharpMethodDeclaration? {
        var current: PsiElement? = element.parent
        while (current != null && current !is PsiFile) {
            when (current) {
                is CSharpMethodDeclaration -> return current
                is CSharpAnonymousFunctionExpression, is CSharpLocalFunctionStatement, is CSharpBaseTypeDeclaration -> return null
            }
            current = current.parent
        }
        return null
    }

    fun typeOf(method: CSharpMethodDeclaration): CSharpBaseTypeDeclaration? = PsiTreeUtil.getParentOfType(method, CSharpBaseTypeDeclaration::class.java)

    /** The name a call calls: `GetComponent` of `GetComponent<Rigidbody>()`, `gameObject.GetComponent<T>()`, `Find` of `GameObject.Find("x")`. */
    fun calledName(call: CSharpInvocationExpression): CSharpSimpleName? = when (val callee = call.expression) {
        is CSharpSimpleName -> callee
        is CSharpMemberAccessExpression -> callee.nameElement
        else -> null
    }

    fun receiver(call: CSharpInvocationExpression): CSharpExpression? = (call.expression as? CSharpMemberAccessExpression)?.expression

    fun attributeNames(declaration: io.github.dotnetsupport.csharp.lang.psi.CSharpMemberDeclaration): List<CSharpAttribute> =
        declaration.attributeLists.flatMap { PsiTreeUtil.getChildrenOfTypeAsList(it, CSharpAttribute::class.java) }

    fun shortName(attribute: CSharpAttribute): String = attribute.nameElement?.text?.substringAfterLast('.')?.removeSuffix("Attribute")?.trim().orEmpty()

    /** Edits through the document of the file: the fixes write text, the PSI follows on commit. */
    fun edit(project: Project, element: PsiElement, change: (Document) -> Unit) {
        val file = element.containingFile ?: return
        val manager = PsiDocumentManager.getInstance(project)
        val document = manager.getDocument(file) ?: return
        manager.doPostponedOperationsAndUnblockDocument(document)
        change(document)
        manager.commitDocument(document)
    }

    /** The whitespace a line starts with. */
    fun indentOf(document: Document, offset: Int): String {
        val line = document.getLineNumber(offset)
        val start = document.getLineStartOffset(line)
        val text = document.charsSequence
        var end = start
        while (end < text.length && (text[end] == ' ' || text[end] == '\t')) end++
        return text.subSequence(start, end).toString()
    }
}

/** `?.`, `??` and `??=` on a `UnityEngine.Object` skip Unity's own null check: a destroyed object is not null to them (Rider: "Possible unintended bypass of lifetime check"). */
class UnityObjectNullPropagationInspection : LocalInspectionTool() {
    override fun buildVisitor(holder: ProblemsHolder, isOnTheFly: Boolean): PsiElementVisitor {
        val file = holder.file as? CSharpFile ?: return PsiElementVisitor.EMPTY_VISITOR
        if (!UnityInspectionSupport.isUnityFile(file)) return PsiElementVisitor.EMPTY_VISITOR
        val session = CSharpSemanticSession(file.project)
        val resolver by lazy { session.resolver(file) }
        fun unityObject(expression: CSharpExpression?): Boolean =
            expression != null && UnityTypes.derivesFrom(runCatching { resolver.typeOf(expression) }.getOrNull(), file, setOf(UnityTypes.UNITY_OBJECT), session) != null
        return object : PsiElementVisitor() {
            override fun visitElement(element: PsiElement) {
                val (operand, operator) = when (element) {
                    is CSharpConditionalAccessExpression -> element.expression to element.operatorToken
                    is CSharpBinaryExpression -> if (element.operatorToken?.text == "??") element.left to element.operatorToken else return
                    is CSharpAssignmentExpression -> if (element.operatorToken?.text == "??=") element.left to element.operatorToken else return
                    else -> return
                }
                if (operator == null || !unityObject(operand)) return
                // the token of `a?.b` is `?` alone: the dot belongs to the member access after it
                val shown = if (element is CSharpConditionalAccessExpression) "?." else operator.text
                holder.registerProblem(operator, "'$shown' on a type derived from 'UnityEngine.Object' bypasses the lifetime check of the Unity engine object: a destroyed object is not null here")
            }
        }
    }
}

/** `tag == "Player"` allocates a string every call; `CompareTag("Player")` does not (Rider: "Explicit string comparison is inefficient, use CompareTag"). */
class UnityCompareTagInspection : LocalInspectionTool() {
    override fun buildVisitor(holder: ProblemsHolder, isOnTheFly: Boolean): PsiElementVisitor {
        val file = holder.file as? CSharpFile ?: return PsiElementVisitor.EMPTY_VISITOR
        if (!UnityInspectionSupport.isUnityFile(file)) return PsiElementVisitor.EMPTY_VISITOR
        return object : PsiElementVisitor() {
            override fun visitElement(element: PsiElement) {
                val binary = element as? CSharpBinaryExpression ?: return
                val operator = binary.operatorToken?.text ?: return
                if (operator != "==" && operator != "!=") return
                val (tag, literal) = pair(binary.left, binary.right) ?: pair(binary.right, binary.left) ?: return
                holder.registerProblem(binary, "Explicit string comparison of 'tag' is inefficient: use 'CompareTag'", ReplaceWithCompareTag(tag, literal, operator == "!="))
            }
        }
    }

    /** `x.tag` (or a bare `tag` of the component) and a string literal. */
    private fun pair(a: CSharpExpression?, b: CSharpExpression?): Pair<String, String>? {
        val literal = (b as? CSharpLiteralExpression)?.text?.takeIf { it.startsWith("\"") } ?: return null
        val receiver = when (a) {
            is CSharpMemberAccessExpression -> if (a.nameElement?.text == "tag") a.expression?.text ?: return null else return null
            is CSharpSimpleName -> if (a.text == "tag") "" else return null
            else -> return null
        }
        return receiver to literal
    }

    private class ReplaceWithCompareTag(private val receiver: String, private val literal: String, private val negated: Boolean) : LocalQuickFix {
        override fun getFamilyName(): String = "Use CompareTag"

        override fun applyFix(project: Project, descriptor: ProblemDescriptor) {
            val element = descriptor.psiElement ?: return
            val call = (if (receiver.isEmpty()) "" else "$receiver.") + "CompareTag($literal)"
            UnityInspectionSupport.edit(project, element) { it.replaceString(element.textRange.startOffset, element.textRange.endOffset, if (negated) "!$call" else call) }
        }
    }
}

/** `[SerializeField]` on a `static`, `const` or `readonly` field: Unity serializes none of them (Rider: "Redundant 'SerializeField' attribute"). */
class UnityRedundantSerializeFieldInspection : LocalInspectionTool() {
    override fun buildVisitor(holder: ProblemsHolder, isOnTheFly: Boolean): PsiElementVisitor {
        val file = holder.file as? CSharpFile ?: return PsiElementVisitor.EMPTY_VISITOR
        if (!UnityInspectionSupport.isUnityFile(file)) return PsiElementVisitor.EMPTY_VISITOR
        return object : PsiElementVisitor() {
            override fun visitElement(element: PsiElement) {
                val field = element as? CSharpBaseFieldDeclaration ?: return
                val attribute = UnityInspectionSupport.attributeNames(field).firstOrNull { UnityInspectionSupport.shortName(it) == "SerializeField" } ?: return
                val modifier = field.modifiers.map { it.text }.firstOrNull { it == "static" || it == "const" || it == "readonly" } ?: return
                holder.registerProblem(attribute, "Redundant 'SerializeField': Unity does not serialize a $modifier field", ProblemHighlightType.LIKE_UNUSED_SYMBOL, RemoveAttribute())
            }
        }
    }

    private class RemoveAttribute : LocalQuickFix {
        override fun getFamilyName(): String = "Remove the attribute"

        override fun applyFix(project: Project, descriptor: ProblemDescriptor) {
            val attribute = descriptor.psiElement as? CSharpAttribute ?: return
            val list = attribute.parent as? CSharpAttributeList ?: return
            val alone = PsiTreeUtil.getChildrenOfTypeAsList(list, CSharpAttribute::class.java).size == 1
            UnityInspectionSupport.edit(project, attribute) { document ->
                if (alone) {
                    // `[SerializeField] ` with the space after it, or the whole line it stands on
                    var end = list.textRange.endOffset
                    val text = document.charsSequence
                    while (end < text.length && (text[end] == ' ' || text[end] == '\t')) end++
                    if (end < text.length && text[end] == '\n') {
                        val lineStart = document.getLineStartOffset(document.getLineNumber(list.textRange.startOffset))
                        if (text.subSequence(lineStart, list.textRange.startOffset).isBlank()) {
                            document.deleteString(lineStart, end + 1)
                            return@edit
                        }
                    }
                    document.deleteString(list.textRange.startOffset, end)
                } else {
                    // `[SerializeField, Range(0, 1)]`: the attribute and the comma beside it
                    val text = document.charsSequence
                    var start = attribute.textRange.startOffset
                    var end = attribute.textRange.endOffset
                    var after = end
                    while (after < text.length && text[after].isWhitespace()) after++
                    if (after < text.length && text[after] == ',') {
                        end = after + 1
                        while (end < text.length && text[end] == ' ') end++
                    } else {
                        while (start > 0 && text[start - 1].isWhitespace()) start--
                        if (start > 0 && text[start - 1] == ',') start--
                    }
                    document.deleteString(start, end)
                }
            }
        }
    }
}

/**
 * A method of a Unity class that looks like an event function and is not one: the name in another case (`update`, `onTriggerEnter`), or
 * the parameters of another type (`OnTriggerEnter(Collision other)`) — Unity never calls it (Rider: "Incorrect method signature").
 */
class UnityEventFunctionSignatureInspection : LocalInspectionTool() {
    override fun buildVisitor(holder: ProblemsHolder, isOnTheFly: Boolean): PsiElementVisitor {
        val file = holder.file as? CSharpFile ?: return PsiElementVisitor.EMPTY_VISITOR
        if (!UnityInspectionSupport.isUnityFile(file)) return PsiElementVisitor.EMPTY_VISITOR
        return object : PsiElementVisitor() {
            override fun visitElement(element: PsiElement) {
                val method = element as? CSharpMethodDeclaration ?: return
                if (method.modifiers.any { it.text == "override" }) return
                val identifier = method.identifier ?: return
                val type = UnityInspectionSupport.typeOf(method) ?: return
                val base = UnityTypes.unityBase(type) ?: return
                val messages = UnityMessages.of(base)
                val name = identifier.text
                if (name !in messages) {
                    val meant = messages.firstOrNull { it.equals(name, ignoreCase = true) } ?: return
                    holder.registerProblem(identifier, "'$name' is not an event function: Unity calls '$meant'", RenameTo(meant))
                    return
                }
                val expected = UnityMessages.signature(name).parameters
                val declared = method.parameterList?.parameters.orEmpty().filterIsInstance<CSharpParameter>()
                if (declared.isEmpty()) return // Unity calls most messages without their argument as well (`OnCollisionEnter()`)
                val expectedTypes = expected.split(',').map { it.trim().substringBeforeLast(' ').substringAfterLast('.') }.filter { it.isNotEmpty() }
                val declaredTypes = declared.map { it.type?.text?.substringAfterLast('.').orEmpty() }
                if (declaredTypes == expectedTypes) return
                val list = method.parameterList ?: return
                holder.registerProblem(list, "Incorrect signature of the event function '$name': Unity calls it with ($expected)", ReplaceParameters("($expected)"))
            }
        }
    }

    private class RenameTo(private val name: String) : LocalQuickFix {
        override fun getFamilyName(): String = "Rename to '$name'"
        override fun applyFix(project: Project, descriptor: ProblemDescriptor) {
            val identifier = descriptor.psiElement ?: return
            UnityInspectionSupport.edit(project, identifier) { it.replaceString(identifier.textRange.startOffset, identifier.textRange.endOffset, name) }
        }
    }

    private class ReplaceParameters(private val parameters: String) : LocalQuickFix {
        override fun getFamilyName(): String = "Change the signature to $parameters"
        override fun applyFix(project: Project, descriptor: ProblemDescriptor) {
            val list = descriptor.psiElement ?: return
            UnityInspectionSupport.edit(project, list) { it.replaceString(list.textRange.startOffset, list.textRange.endOffset, parameters) }
        }
    }
}

/**
 * A lookup that costs every frame in `Update`, `FixedUpdate`, `LateUpdate` or `OnGUI` of a component: `GetComponent`, `Find*`, `SendMessage`
 * (Rider's "Performance-critical context"). The fix for `GetComponent<T>()` of the component itself: a field set in `Awake`.
 */
class UnityExpensiveCallInFrameInspection : LocalInspectionTool() {
    override fun buildVisitor(holder: ProblemsHolder, isOnTheFly: Boolean): PsiElementVisitor {
        val file = holder.file as? CSharpFile ?: return PsiElementVisitor.EMPTY_VISITOR
        if (!UnityInspectionSupport.isUnityFile(file)) return PsiElementVisitor.EMPTY_VISITOR
        return object : PsiElementVisitor() {
            override fun visitElement(element: PsiElement) {
                val call = element as? CSharpInvocationExpression ?: return
                val name = UnityInspectionSupport.calledName(call) ?: return
                val called = name.identifier?.text ?: return
                val receiver = UnityInspectionSupport.receiver(call)?.text
                val expensive = called in LOOKUPS || called in FIND && receiver == "GameObject"
                if (!expensive) return
                val method = UnityInspectionSupport.enclosingMethod(call) ?: return
                val frame = method.identifier?.text?.takeIf { it in HOT } ?: return
                if (method.modifiers.any { it.text == "static" }) return
                val type = UnityInspectionSupport.typeOf(method) ?: return
                if (UnityTypes.unityBase(type) != "UnityEngine.MonoBehaviour") return
                val cacheable = (name as? CSharpGenericName)?.takeIf { called == "GetComponent" && call.argumentList?.arguments.isNullOrEmpty() && (receiver == null || receiver == "this") }
                    ?.typeArgumentList?.arguments?.singleOrNull()?.text
                val fixes = if (cacheable != null) arrayOf<LocalQuickFix>(CacheInAwake(cacheable, call.text)) else emptyArray()
                holder.registerProblem(name, "Expensive '$called' in '$frame', called every frame: look it up once (Awake, Start) and keep it", ProblemHighlightType.WEAK_WARNING, *fixes)
            }
        }
    }

    /** `GetComponent<Rigidbody>()` in `Update` → `private Rigidbody _rigidbody;`, `_rigidbody = GetComponent<Rigidbody>();` in `Awake`, `_rigidbody` in `Update`. */
    private class CacheInAwake(private val type: String, private val callText: String) : LocalQuickFix {
        override fun getFamilyName(): String = "Introduce a field and initialize it in 'Awake'"

        override fun applyFix(project: Project, descriptor: ProblemDescriptor) {
            val name = descriptor.psiElement ?: return
            val method = UnityInspectionSupport.enclosingMethod(name) ?: return
            val owner = UnityInspectionSupport.typeOf(method) ?: return
            val members = PsiTreeUtil.findChildrenOfType(owner, CSharpMethodDeclaration::class.java).filter { UnityInspectionSupport.typeOf(it) == owner }
            val fields = PsiTreeUtil.findChildrenOfType(owner, io.github.dotnetsupport.csharp.lang.psi.CSharpVariableDeclarator::class.java).mapNotNullTo(HashSet()) { it.identifier?.text }
            val simple = type.substringAfterLast('.').substringBefore('<')
            val base = "_" + simple.replaceFirstChar { it.lowercaseChar() }
            val field = generateSequence(0) { it + 1 }.map { if (it == 0) base else "$base$it" }.first { it !in fields }
            val awake = members.firstOrNull { it.identifier?.text == "Awake" }
            val calls = PsiTreeUtil.findChildrenOfType(method, CSharpInvocationExpression::class.java).filter { it.text == callText && UnityInspectionSupport.enclosingMethod(it) == method }
            UnityInspectionSupport.edit(project, name) { document ->
                val methodStart = method.textRange.startOffset
                val indent = UnityInspectionSupport.indentOf(document, methodStart)
                val lineStart = document.getLineStartOffset(document.getLineNumber(methodStart))
                val inner = "$indent    "
                val assignment = "$field = $callText;"
                // from the end of the text to its start: the offsets before an edit stay valid
                val edits = ArrayList<Pair<TextRange, String>>()
                for (call in calls) edits += call.textRange to field
                val awakeBody = awake?.body
                if (awakeBody != null) {
                    val brace = awakeBody.textRange.startOffset + 1
                    edits += TextRange(brace, brace) to "\n$inner$assignment"
                    edits += TextRange(lineStart, lineStart) to "${indent}private $type $field;\n\n"
                } else {
                    edits += TextRange(lineStart, lineStart) to "${indent}private $type $field;\n\n${indent}private void Awake()\n$indent{\n$inner$assignment\n$indent}\n\n"
                }
                for ((range, text) in edits.sortedByDescending { it.first.startOffset }) document.replaceString(range.startOffset, range.endOffset, text)
            }
        }
    }

    private companion object {
        /** The frame methods of a `MonoBehaviour`: called once a frame or more. */
        val HOT = setOf("Update", "FixedUpdate", "LateUpdate", "OnGUI")
        val LOOKUPS = setOf(
            "GetComponent", "GetComponents", "GetComponentInChildren", "GetComponentsInChildren", "GetComponentInParent", "GetComponentsInParent",
            "FindObjectOfType", "FindObjectsOfType", "FindFirstObjectByType", "FindAnyObjectByType", "FindObjectsByType",
            "FindWithTag", "FindGameObjectWithTag", "FindGameObjectsWithTag", "SendMessage", "SendMessageUpwards", "BroadcastMessage", "AddComponent",
        )
        /** `GameObject.Find`: by name through the whole scene. */
        val FIND = setOf("Find")
    }
}

/**
 * `GetComponent<T>` of a `T` that is neither a `Component` nor an interface: compiles, and throws `ArgumentException` at run time;
 * `AddComponent<T>` of a non-component the same (Rider: "Type parameter should be derived from 'Component' or be an interface").
 */
class UnityGetComponentTypeInspection : LocalInspectionTool() {
    override fun buildVisitor(holder: ProblemsHolder, isOnTheFly: Boolean): PsiElementVisitor {
        val file = holder.file as? CSharpFile ?: return PsiElementVisitor.EMPTY_VISITOR
        if (!UnityInspectionSupport.isUnityFile(file)) return PsiElementVisitor.EMPTY_VISITOR
        val session = CSharpSemanticSession(file.project)
        val resolver by lazy { session.resolver(file) }
        return object : PsiElementVisitor() {
            override fun visitElement(element: PsiElement) {
                val call = element as? CSharpInvocationExpression ?: return
                val name = UnityInspectionSupport.calledName(call) as? CSharpGenericName ?: return
                val called = name.identifier?.text ?: return
                if (called !in COMPONENT_LOOKUPS && called != "AddComponent") return
                val argument = name.typeArgumentList?.arguments?.singleOrNull() ?: return
                val type = runCatching { resolver.resolveType(argument) }.getOrNull() ?: return
                if (type is io.github.dotnetsupport.lang.semantic.SemanticType.Parameter) return
                if (called != "AddComponent" && UnityTypes.isInterface(type)) return
                if (UnityTypes.derivesFrom(type, file, setOf(UnityTypes.COMPONENT), session) != null) return
                val requirement = if (called == "AddComponent") "a 'Component'" else "a 'Component' or an interface"
                holder.registerProblem(argument, "'${argument.text}' is not $requirement: '$called' throws ArgumentException at run time", ProblemHighlightType.GENERIC_ERROR_OR_WARNING)
            }
        }
    }

    private companion object {
        val COMPONENT_LOOKUPS = setOf(
            "GetComponent", "GetComponents", "GetComponentInChildren", "GetComponentsInChildren", "GetComponentInParent", "GetComponentsInParent", "TryGetComponent",
        )
    }
}
