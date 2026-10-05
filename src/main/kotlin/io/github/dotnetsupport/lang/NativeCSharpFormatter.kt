package io.github.dotnetsupport.lang

import com.intellij.formatting.Alignment
import com.intellij.formatting.Block
import com.intellij.formatting.ChildAttributes
import com.intellij.formatting.CustomFormattingModelBuilder
import com.intellij.formatting.FormattingContext
import com.intellij.formatting.FormattingModel
import com.intellij.formatting.FormattingModelProvider
import com.intellij.formatting.Indent
import com.intellij.formatting.Spacing
import com.intellij.formatting.Wrap
import com.intellij.lang.ASTNode
import com.intellij.openapi.util.TextRange
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.codeStyle.CommonCodeStyleSettings
import com.intellij.psi.formatter.DocumentBasedFormattingModel
import com.intellij.psi.tree.IElementType
import com.intellij.psi.tree.TokenSet
import io.github.dotnetsupport.csharp.lang.CSharpSyntaxFacts
import io.github.dotnetsupport.csharp.lang.SyntaxKind
import io.github.dotnetsupport.csharp.lang.lexer.CSharpDirectiveTokenType
import io.github.dotnetsupport.csharp.lang.lexer.CSharpTokenTypes
import io.github.dotnetsupport.csharp.lang.parser.CSharpBodyBlockType
import io.github.dotnetsupport.format.DotNetFormattingSettings
import io.github.dotnetsupport.format.FormatterChoice

/**
 * Reformat Code on csharp-psi's tree (CSHARP_PSI_MIGRATION.md, step 9, feature `FORMATTING`): what `dotnet format whitespace` does with
 * the options of `.editorconfig`, and nothing else — but for multi-line initializers, collection expressions, argument and parameter
 * lists, which `dotnet format` leaves as they are and which are laid out as in Rider (0.1.68, "Rider's lists" in [NativeCSharpLayout]).
 * A model of the platform formatter ([NativeCSharpFormattingModelBuilder]) rather than a
 * formatting service: Reformat Code, Reformat Selection, Code | Auto-Indent Lines and the indent of a paste come with it.
 *
 * It answers when the project's formatter is "Built-in" (chosen, or what "Auto" comes to with `FORMATTING` NATIVE, see
 * [DotNetFormattingSettings.resolve]) and the file has the native tree ([engaged]); for a file of the other tree `dotnet format` stands in.
 */
object NativeCSharpFormatting {
    fun engaged(file: PsiFile?): Boolean {
        if (file !is CSharpFile || file.compilationUnit == null) return false
        val settings = DotNetFormattingSettings.getInstance(file.project)
        if (settings.formatter.let { it != FormatterChoice.AUTO && it != FormatterChoice.BUILT_IN }) return false
        val virtualFile = file.originalFile.virtualFile
        return (if (virtualFile != null) settings.resolve(virtualFile) else settings.resolve(null)) == FormatterChoice.BUILT_IN
    }
}

/** `csharp_*` options of the `.editorconfig` files above a source file; the nearest file wins, `root = true` ends the search. */
object CSharpEditorConfig {
    private val OPTION = Regex("""^[ \t]*(csharp_\w+)[ \t]*=[ \t]*([^#;\r\n]*)""", RegexOption.MULTILINE)
    private val ROOT = Regex("""^\s*root\s*=\s*true""", setOf(RegexOption.MULTILINE, RegexOption.IGNORE_CASE))

    /** Sections are not told apart: `csharp_*` options are only ever written for C# files. */
    fun parse(editorConfig: String): Map<String, String> =
        OPTION.findAll(editorConfig).associate { it.groupValues[1].lowercase() to it.groupValues[2].trim().lowercase() }

    fun of(file: VirtualFile?): Map<String, String> {
        val result = HashMap<String, String>()
        var directory = file?.parent
        while (directory != null) {
            val text = directory.findChild(".editorconfig")?.let { runCatching { String(it.contentsToByteArray(), it.charset) }.getOrNull() }
            if (text != null) {
                parse(text).forEach { (name, value) -> result.putIfAbsent(name, value) }
                if (ROOT.containsMatchIn(text)) break
            }
            directory = directory.parent
        }
        return result
    }
}

/**
 * The options of the formatter: the indent of the code style (the platform has put `indent_size`, `indent_style`, `tab_width` of
 * `.editorconfig` into it) and the `csharp_*` formatting options of `.editorconfig`, with the defaults of Roslyn.
 */
class CSharpFormatOptions(
    val indentSize: Int = 4,
    val tabSize: Int = 4,
    private val editorConfig: Map<String, String> = emptyMap(),
    /** Rider's layout of initializers, collection expressions, argument and parameter lists ([NativeCSharpLayout]); false: exactly `dotnet format`. */
    val riderLists: Boolean = true,
) {
    // `true:warning` is the old form with a severity
    private fun value(name: String): String? = editorConfig[name]?.substringBefore(':')?.trim()?.takeIf { it.isNotEmpty() }

    /** An option written in `.editorconfig`, not a default: Rider's layout gives way to it. */
    fun isSet(name: String): Boolean = value(name) != null
    private fun flag(name: String, default: Boolean): Boolean = when (value(name)) { "true" -> true; "false" -> false; else -> default }
    private fun set(name: String, default: Set<String>): Set<String> = value(name)?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() }?.toSet() ?: default

    private val openBrace = set("csharp_new_line_before_open_brace", setOf("all"))

    /** `csharp_new_line_before_open_brace` for a kind of braces (`types`, `methods`, `control_blocks`, ...). */
    fun braceOnNewLine(category: String): Boolean = "all" in openBrace || category in openBrace

    val newLineBeforeElse = flag("csharp_new_line_before_else", true)
    val newLineBeforeCatch = flag("csharp_new_line_before_catch", true)
    val newLineBeforeFinally = flag("csharp_new_line_before_finally", true)
    val newLineBeforeMembersInObjectInitializers = flag("csharp_new_line_before_members_in_object_initializers", true)
    val newLineBeforeMembersInAnonymousTypes = flag("csharp_new_line_before_members_in_anonymous_types", true)
    val indentBlockContents = flag("csharp_indent_block_contents", true)
    val indentBraces = flag("csharp_indent_braces", false)
    val indentCaseContents = flag("csharp_indent_case_contents", true)
    val indentCaseContentsWhenBlock = flag("csharp_indent_case_contents_when_block", true)
    val indentSwitchLabels = flag("csharp_indent_switch_labels", true)
    val indentLabels = value("csharp_indent_labels") ?: "one_less_than_current"
    val preserveSingleLineStatements = flag("csharp_preserve_single_line_statements", true)
    val preserveSingleLineBlocks = flag("csharp_preserve_single_line_blocks", true)

    /** Rider's default layout of braces written on one line (`class A { void M() { } }` in full), unless `.editorconfig` says how. */
    val riderBlocks: Boolean get() = riderLists && !isSet("csharp_preserve_single_line_blocks")
    val spaceAfterCast = flag("csharp_space_after_cast", false)
    val spaceAfterControlKeyword = flag("csharp_space_after_keywords_in_control_flow_statements", true)
    val spaceAfterComma = flag("csharp_space_after_comma", true)
    val spaceBeforeComma = flag("csharp_space_before_comma", false)
    val spaceAfterDot = flag("csharp_space_after_dot", false)
    val spaceBeforeDot = flag("csharp_space_before_dot", false)
    val spaceAfterSemicolonInFor = flag("csharp_space_after_semicolon_in_for_statement", true)
    val spaceBeforeSemicolonInFor = flag("csharp_space_before_semicolon_in_for_statement", false)
    val spaceBeforeColonInInheritance = flag("csharp_space_before_colon_in_inheritance_clause", true)
    val spaceAfterColonInInheritance = flag("csharp_space_after_colon_in_inheritance_clause", true)

    /** `before_and_after`, `none` or `ignore`. */
    val binaryOperators = value("csharp_space_around_binary_operators") ?: "before_and_after"
    private val parentheses = set("csharp_space_between_parentheses", emptySet())
    val spaceInControlParentheses = "control_flow_statements" in parentheses
    val spaceInExpressionParentheses = "expressions" in parentheses
    val spaceInCastParentheses = "type_casts" in parentheses
    val spaceBeforeCallParenthesis = flag("csharp_space_between_method_call_name_and_opening_parenthesis", false)
    val spaceInCallParentheses = flag("csharp_space_between_method_call_parameter_list_parentheses", false)
    val spaceInEmptyCallParentheses = flag("csharp_space_between_method_call_empty_parameter_list_parentheses", false)
    val spaceBeforeDeclarationParenthesis = flag("csharp_space_between_method_declaration_name_and_open_parenthesis", false)
    val spaceInDeclarationParentheses = flag("csharp_space_between_method_declaration_parameter_list_parentheses", false)
    val spaceInEmptyDeclarationParentheses = flag("csharp_space_between_method_declaration_empty_parameter_list_parentheses", false)
    val spaceBeforeOpenSquareBracket = flag("csharp_space_before_open_square_brackets", false)
    val spaceInEmptySquareBrackets = flag("csharp_space_between_empty_square_brackets", false)
    val spaceInSquareBrackets = flag("csharp_space_between_square_brackets", false)

    companion object {
        /** The oracle's switch (CSharpFormatOracle): compare with `dotnet format` without Rider's lists. */
        @Volatile
        internal var dotnetFormatOnly = false

        fun of(file: PsiFile, indentOptions: CommonCodeStyleSettings.IndentOptions): CSharpFormatOptions =
            CSharpFormatOptions(indentOptions.INDENT_SIZE, indentOptions.TAB_SIZE, CSharpEditorConfig.of(file.originalFile.virtualFile), riderLists = !dotnetFormatOnly)
    }
}

/**
 * Where every piece of a C# file goes: the engine of the native formatter, Roslyn's rules for whitespace re-told on the native tree.
 *
 * The file is cut into [units]: code tokens (in an interpolated string the holes are code, its text is never touched), comments, a line of a doc
 * comment or of a multi-line comment, a preprocessor directive line, a disabled `#if` region (left exactly as it is). For the gap
 * before each unit it decides the line breaks (kept as they are; added where Roslyn adds them: around the braces of a multi-line
 * block, the members of a multi-line object initializer, `else` / `catch` / `finally`) and, on one line, the spaces (Roslyn's spacing
 * rules; a pair no rule knows keeps its spaces). A unit that starts a line gets a column: by the structure for statements, members,
 * braces, labels, comments; by its anchor for a continuation line, which keeps its offset from the start of its statement, as Roslyn's
 * anchors do; as it was inside collection initializers, which Roslyn does not indent. Multi-line initializers, collection expressions,
 * argument and parameter lists are laid out as Rider does instead ([CSharpFormatOptions.riderLists], see "Rider's lists" below).
 */
internal class NativeCSharpLayout(private val root: ASTNode, private val text: CharSequence, private val options: CSharpFormatOptions) {
    enum class Kind { CODE, COMMENT, COMMENT_TAIL, DOC, DIRECTIVE, REGION, DISABLED }

    class Unit(val start: Int, val end: Int, val kind: Kind, val node: ASTNode?, val head: Int = -1)

    val units = ArrayList<Unit>()
    private val codeStarts = ArrayList<Int>()
    private val codeUnits = ArrayList<Int>()
    private val unitAt = HashMap<Int, Int>()
    private val lists = HashMap<ASTNode, RiderList?>()
    private lateinit var lineStarts: IntArray
    private lateinit var startsLine: BooleanArray
    private lateinit var lineHead: IntArray
    private lateinit var column: IntArray
    private lateinit var gap: IntArray
    private lateinit var originalBreak: BooleanArray

    /** How the last [codeColumn] placed its unit: [STRUCTURE], [AS_IS] or the unit of the anchor it kept its offset from. */
    private var placedBy = STRUCTURE
    lateinit var spacings: Array<Spacing?>
        private set
    lateinit var indents: IntArray
        private set
    private val ind = options.indentSize

    init {
        collect(root)
        layOut()
    }

    // ---- units

    private var skipUntil = -1

    /** The unit being laid out. */
    private var current = -1

    private fun collect(node: ASTNode) {
        var child = node.firstChildNode
        while (child != null) {
            visit(child)
            child = child.treeNext
        }
    }

    private fun visit(node: ASTNode) {
        val start = node.startOffset
        val end = start + node.textLength
        if (node.textLength == 0 || end <= skipUntil) return
        val type = node.elementType
        when {
            type === SyntaxKind.WhitespaceTrivia || type === SyntaxKind.EndOfLineTrivia || isWhiteSpace(node) -> return
            type === SyntaxKind.SingleLineDocumentationCommentTrivia || type === SyntaxKind.MultiLineDocumentationCommentTrivia -> lines(start, end, Kind.DOC, node)
            type === SyntaxKind.MultiLineCommentTrivia -> lines(start, end, Kind.COMMENT, node)
            type === SyntaxKind.SingleLineCommentTrivia -> add(start, end, Kind.COMMENT, node)
            type === SyntaxKind.DisabledTextTrivia || type === SyntaxKind.ConflictMarkerTrivia -> {
                val trimmed = trimEnd(start, end)
                if (trimmed > start) add(start, trimmed, Kind.DISABLED, node)
            }
            type is CSharpDirectiveTokenType && type.roslynKind === SyntaxKind.HashToken -> {
                var lineEnd = start
                while (lineEnd < text.length && text[lineEnd] != '\n' && text[lineEnd] != '\r') lineEnd++
                val trimmed = trimEnd(start, lineEnd)
                val keyword = directiveKeyword(node)
                add(start, trimmed, if (keyword === SyntaxKind.RegionKeyword || keyword === SyntaxKind.EndRegionKeyword) Kind.REGION else Kind.DIRECTIVE, node)
                skipUntil = trimmed
            }
            type in CSharpTokenTypes.COMMENTS -> add(start, trimEnd(start, end), Kind.COMMENT, node)
            node.firstChildNode == null -> if (start >= skipUntil) add(start, end, Kind.CODE, node)
            else -> collect(node)
        }
    }

    private fun isWhiteSpace(node: ASTNode): Boolean = node is com.intellij.psi.PsiWhiteSpace

    private fun directiveKeyword(hash: ASTNode): IElementType? {
        var next = hash.treeNext
        while (next != null && (next.elementType === SyntaxKind.WhitespaceTrivia || next is com.intellij.psi.PsiWhiteSpace) && !next.textContains('\n')) next = next.treeNext
        return (next?.elementType as? CSharpDirectiveTokenType)?.roslynKind
    }

    private fun trimEnd(start: Int, end: Int): Int {
        var e = end
        while (e > start && text[e - 1].isWhitespace()) e--
        return e
    }

    private fun add(start: Int, end: Int, kind: Kind, node: ASTNode?, head: Int = -1) {
        if (end <= start) return
        if (kind == Kind.CODE) {
            codeStarts += start
            codeUnits += units.size
            unitAt[start] = units.size
        }
        units += Unit(start, end, kind, node, head)
    }

    /** A doc comment or a multi-line comment, line by line: the indent of the lines after the first one is whitespace the formatter may set. */
    private fun lines(start: Int, end: Int, kind: Kind, node: ASTNode) {
        var lineStart = start
        var first = -1
        while (lineStart < end) {
            var lineEnd = lineStart
            while (lineEnd < end && text[lineEnd] != '\n') lineEnd++
            var s = lineStart
            while (s < lineEnd && (text[s] == ' ' || text[s] == '\t')) s++
            val e = if (kind == Kind.DOC) lineEnd.let { if (it > s && text[it - 1] == '\r') it - 1 else it } else trimEnd(s, lineEnd)
            if (e > s) {
                if (first < 0) {
                    first = units.size
                    add(s, e, kind, node)
                } else add(s, e, if (kind == Kind.DOC) Kind.DOC else Kind.COMMENT_TAIL, node, first)
            }
            lineStart = lineEnd + 1
        }
    }

    // ---- lookups

    private fun kindOf(node: ASTNode?): IElementType? = node?.elementType?.let { if (CSharpBodyBlockType.isBlock(it)) SyntaxKind.Block else it }

    /** The unit of the first code token of [node], or -1. */
    private fun firstCode(node: ASTNode): Int {
        val start = node.startOffset
        val end = start + node.textLength
        var low = 0
        var high = codeStarts.size
        while (low < high) {
            val mid = (low + high) ushr 1
            if (codeStarts[mid] < start) low = mid + 1 else high = mid
        }
        return if (low < codeStarts.size && codeStarts[low] < end) codeUnits[low] else -1
    }

    private fun lastCode(node: ASTNode): Int {
        val start = node.startOffset
        val end = start + node.textLength
        var low = 0
        var high = codeStarts.size
        while (low < high) {
            val mid = (low + high) ushr 1
            if (codeStarts[mid] < end) low = mid + 1 else high = mid
        }
        return if (low > 0 && codeStarts[low - 1] >= start) codeUnits[low - 1] else -1
    }

    private fun lineOf(offset: Int): Int {
        var low = 0
        var high = lineStarts.size - 1
        while (low < high) {
            val mid = (low + high + 1) ushr 1
            if (lineStarts[mid] <= offset) low = mid else high = mid - 1
        }
        return low
    }

    private fun originalColumn(i: Int): Int {
        val start = units[i].start
        var lineStart = start
        while (lineStart > 0 && text[lineStart - 1] != '\n') lineStart--
        var col = 0
        for (k in lineStart until start) col = if (text[k] == '\t') (col / options.tabSize + 1) * options.tabSize else col + 1
        return col
    }

    private fun multiLine(open: Int, close: Int): Boolean = close >= 0 && open >= 0 && lineOf(units[open].start) != lineOf(units[close].start)

    private fun type(i: Int): IElementType? = units[i].node?.elementType

    private fun parentKind(i: Int): IElementType? = kindOf(units[i].node?.treeParent)

    private fun isCode(i: Int) = units[i].kind == Kind.CODE

    // ---- brace constructs

    private enum class Construct { BLOCK, TYPE, ACCESSORS, SWITCH, OBJECT, ANONYMOUS, SWITCH_EXPRESSION, COLLECTION, ENUM, COLLECTION_EXPRESSION }

    private fun construct(node: ASTNode?): Construct? = when (kindOf(node)) {
        SyntaxKind.Block -> Construct.BLOCK
        SyntaxKind.ClassDeclaration, SyntaxKind.StructDeclaration, SyntaxKind.InterfaceDeclaration, SyntaxKind.RecordDeclaration,
        SyntaxKind.RecordStructDeclaration, SyntaxKind.NamespaceDeclaration, SyntaxKind.ExtensionBlockDeclaration, SyntaxKind.UnionDeclaration -> Construct.TYPE
        SyntaxKind.EnumDeclaration -> Construct.ENUM
        SyntaxKind.AccessorList -> Construct.ACCESSORS
        SyntaxKind.SwitchStatement -> Construct.SWITCH
        SyntaxKind.ObjectInitializerExpression, SyntaxKind.WithInitializerExpression -> Construct.OBJECT
        SyntaxKind.AnonymousObjectCreationExpression -> Construct.ANONYMOUS
        SyntaxKind.SwitchExpression -> Construct.SWITCH_EXPRESSION
        SyntaxKind.CollectionInitializerExpression, SyntaxKind.ArrayInitializerExpression, SyntaxKind.ComplexElementInitializerExpression -> Construct.COLLECTION
        SyntaxKind.CollectionExpression -> Construct.COLLECTION_EXPRESSION
        else -> null
    }

    /** The construct whose brace unit [i] is, with the units of its two braces. */
    private class Braces(val node: ASTNode, val construct: Construct, val open: Int, val close: Int)

    private fun braces(i: Int): Braces? {
        val t = type(i)
        val opening = t === SyntaxKind.OpenBraceToken || t === SyntaxKind.OpenBracketToken
        if (!opening && t !== SyntaxKind.CloseBraceToken && t !== SyntaxKind.CloseBracketToken) return null
        val parent = units[i].node?.treeParent ?: return null
        val construct = construct(parent) ?: return null
        if ((t === SyntaxKind.OpenBracketToken || t === SyntaxKind.CloseBracketToken) != (construct == Construct.COLLECTION_EXPRESSION)) return null
        return bracesOf(parent, construct)
    }

    private fun bracesOf(node: ASTNode, construct: Construct = construct(node)!!): Braces? {
        val openType = if (construct == Construct.COLLECTION_EXPRESSION) SyntaxKind.OpenBracketToken else SyntaxKind.OpenBraceToken
        val closeType = if (construct == Construct.COLLECTION_EXPRESSION) SyntaxKind.CloseBracketToken else SyntaxKind.CloseBraceToken
        var open = -1
        var close = -1
        var child = node.firstChildNode
        while (child != null) {
            if (child.elementType === openType && open < 0) open = unitAt[child.startOffset] ?: -1
            if (child.elementType === closeType) close = unitAt[child.startOffset] ?: -1
            child = child.treeNext
        }
        return if (open < 0) null else Braces(node, construct, open, close)
    }

    /** The node whose first line the braces of [braces] are placed under. */
    private fun owner(braces: Braces): ASTNode? {
        val node = braces.node
        val parent = node.treeParent
        return when (braces.construct) {
            Construct.BLOCK -> if (blockIsStatement(node)) null else parent
            Construct.ACCESSORS -> parent
            Construct.OBJECT -> parent
            else -> node
        }
    }

    private fun blockIsStatement(block: ASTNode): Boolean = when (kindOf(block.treeParent)) {
        SyntaxKind.Block, SyntaxKind.SwitchSection, SyntaxKind.LabeledStatement, SyntaxKind.GlobalStatement -> true
        else -> false
    }

    /** `csharp_new_line_before_open_brace` category of a construct. */
    private fun category(braces: Braces): String = when (braces.construct) {
        Construct.TYPE, Construct.ENUM -> "types"
        Construct.ACCESSORS -> when (kindOf(braces.node.treeParent)) {
            SyntaxKind.IndexerDeclaration -> "indexers"
            SyntaxKind.EventDeclaration -> "events"
            else -> "properties"
        }
        Construct.OBJECT, Construct.COLLECTION -> "object_collection_array_initializers"
        Construct.ANONYMOUS -> "anonymous_types"
        Construct.BLOCK -> when (kindOf(braces.node.treeParent)) {
            SyntaxKind.MethodDeclaration, SyntaxKind.ConstructorDeclaration, SyntaxKind.DestructorDeclaration, SyntaxKind.OperatorDeclaration,
            SyntaxKind.ConversionOperatorDeclaration -> "methods"
            SyntaxKind.GetAccessorDeclaration, SyntaxKind.SetAccessorDeclaration, SyntaxKind.InitAccessorDeclaration, SyntaxKind.AddAccessorDeclaration,
            SyntaxKind.RemoveAccessorDeclaration, SyntaxKind.UnknownAccessorDeclaration -> "accessors"
            SyntaxKind.LocalFunctionStatement -> "local_functions"
            SyntaxKind.SimpleLambdaExpression, SyntaxKind.ParenthesizedLambdaExpression -> "lambdas"
            SyntaxKind.AnonymousMethodExpression -> "anonymous_methods"
            else -> "control_blocks"
        }
        else -> "control_blocks"
    }

    /** Braces whose line breaks Roslyn sets when they span lines; collection initializers and collection expressions are left alone. */
    private fun wraps(braces: Braces): Boolean = braces.construct != Construct.COLLECTION && braces.construct != Construct.COLLECTION_EXPRESSION &&
        (multiLine(braces.open, braces.close) || braces.construct == Construct.BLOCK && !options.preserveSingleLineBlocks && braces.close >= 0 || riderWraps(braces))

    /**
     * Rider's default style (DEV_JOURNEY 4.5, 0.1.100): the braces of a type, a namespace, a `switch` and the body of a method or of a
     * statement go to lines of their own even when the whole thing is on one line — `class A { void M() { x(); } }` is laid out in full.
     * What Rider keeps on one line stays: accessors (`{ get; set; }`, `get { return x; }`), lambdas and anonymous methods, enums,
     * initializers. `csharp_preserve_single_line_blocks` written in `.editorconfig` wins, as does `dotnet format` alone ([CSharpFormatOptions.riderLists]).
     */
    private fun riderWraps(braces: Braces): Boolean {
        if (!options.riderBlocks || braces.close < 0) return false
        return when (braces.construct) {
            Construct.TYPE, Construct.SWITCH -> true
            Construct.BLOCK -> when (kindOf(braces.node.treeParent)) {
                SyntaxKind.SimpleLambdaExpression, SyntaxKind.ParenthesizedLambdaExpression, SyntaxKind.AnonymousMethodExpression,
                SyntaxKind.GetAccessorDeclaration, SyntaxKind.SetAccessorDeclaration, SyntaxKind.InitAccessorDeclaration, SyntaxKind.AddAccessorDeclaration,
                SyntaxKind.RemoveAccessorDeclaration, SyntaxKind.UnknownAccessorDeclaration -> false
                else -> true
            }
            else -> false
        }
    }

    /** The `{` goes to a line of its own unless the whole construct, from its header (attributes aside) to `}`, is on one line. */
    private fun wrapsBefore(braces: Braces): Boolean {
        if (braces.construct == Construct.COLLECTION || braces.construct == Construct.COLLECTION_EXPRESSION || braces.close < 0) return false
        if (braces.construct == Construct.OBJECT && kindOf(braces.node.treeParent) !in CREATIONS) return false
        if (wraps(braces)) return true
        val owner = owner(braces) ?: return false
        // `Ctor(...)\n    : base(x) { }`: Roslyn looks at the line of the initializer
        val initializer = if (kindOf(owner) === SyntaxKind.ConstructorDeclaration) owner.getChildren(null).firstOrNull {
            it.elementType === SyntaxKind.BaseConstructorInitializer || it.elementType === SyntaxKind.ThisConstructorInitializer
        } else null
        val first = if (initializer != null) firstCode(initializer) else firstCodeAfterAttributes(owner)
        return first in 0 until braces.open && lineOf(units[first].start) != lineOf(units[braces.close].start)
    }

    private fun firstCodeAfterAttributes(node: ASTNode): Int {
        var child = node.firstChildNode
        while (child != null) {
            if (child.textLength > 0 && kindOf(child) !== SyntaxKind.AttributeList) {
                val first = firstCode(child)
                if (first >= 0) return first
            }
            child = child.treeNext
        }
        return firstCode(node)
    }

    /**
     * A multi-line collection initializer: Roslyn leaves everything inside it as it is (spaces, line breaks, indents); [before] counts
     * the gap before its `{` as inside, as Roslyn does for the spaces.
     */
    private fun frozen(a: Int, b: Int, before: Boolean = false): Boolean {
        if (options.riderLists) return false
        var node = units[b].node?.treeParent
        while (node != null && node.treeParent != null) {
            if (construct(node) == Construct.COLLECTION) {
                val braces = bracesOf(node, Construct.COLLECTION)
                if (braces != null && braces.close >= 0 && (if (before) b >= braces.open else a >= braces.open) && b <= braces.close && multiLine(braces.open, braces.close)) return true
            }
            node = node.treeParent
        }
        return false
    }

    /** The interpolated string unit [i] is a part of, or null. */
    private fun interpolatedString(i: Int): ASTNode? {
        var node = units[i].node?.treeParent
        while (node != null && node.treeParent != null) {
            if (node.elementType === SyntaxKind.InterpolatedStringExpression) return node
            node = node.treeParent
        }
        return null
    }

    /** The text of an interpolated string, its quotes and the braces of its holes: never spaced or broken by the formatter. */
    private fun isStringPiece(i: Int): Boolean {
        val type = type(i)
        if (type in STRING_PIECES) return true
        val parent = parentKind(i)
        return parent === SyntaxKind.InterpolationFormatClause || parent === SyntaxKind.InterpolationAlignmentClause ||
            parent === SyntaxKind.Interpolation && (type === SyntaxKind.OpenBraceToken || type === SyntaxKind.CloseBraceToken)
    }

    private fun isLambdaBody(braces: Braces): Boolean = braces.construct == Construct.BLOCK && kindOf(braces.node.treeParent).let {
        it === SyntaxKind.SimpleLambdaExpression || it === SyntaxKind.ParenthesizedLambdaExpression || it === SyntaxKind.AnonymousMethodExpression
    }

    // ---- Rider's lists
    //
    // Initializers (object, collection, array, anonymous object, `with`, complex elements), collection expressions, argument lists
    // (invocation, object creation, element access, attribute, constructor initializer) and parameter lists are laid out as Rider
    // (ReSharper 2026.2, `jb cleanupcode --profile="Built-in: Reformat Code"`, no settings layers) does by default, where `dotnet format`
    // leaves them as they are. Line breaks are kept, as Rider's "keep existing arrangement" does, except:
    //  - a multi-line initializer / collection expression gets its open brace on a line of its own (unless it starts an item of an
    //    outer list, `Sum([1,` and `Sum(\n[1,`; `csharp_new_line_before_open_brace` without `object_collection_array_initializers`
    //    pulls it up to the line before), its elements start on the line after it, its close brace on a line of its own;
    //  - when an element of a multi-line initializer spans lines, every element goes to a line of its own ("chop if multi-line");
    //    elements on one line otherwise stay together (`1, 2,\n3, 4`). `csharp_new_line_before_members_in_*` written in
    //    `.editorconfig` decide for object initializers and anonymous types instead.
    // Columns: the elements / arguments / parameters that start a line go one indent right of the list's base, the close brace or
    // parenthesis on a line of its own and the open brace go to the base. Rider does not align arguments with the first one
    // (`ALIGN_MULTILINE_ARGUMENT` is off): `Foo(1,\n2)` is `Foo(1,\n    2)` under the line of `Foo`. The base ([listBase]) is the column
    // of the nearest line start that belongs to the construct the list is in: the start of a node it is in, an operator token of
    // such a node (`\n.Bar(`, `\n? Foo(`), the first token inside the parentheses of `if` / `while` / `foreach` / `using` / ...
    // (Rider aligns there), or the column of the elements of an enclosing list that has elements on separate lines ("active":
    // `Foo(Foo(1,\n2),\n3)` puts `2` two indents in, `Foo(1, Foo(2,\n3))` one). A line inside an element that does not start one
    // keeps its offset from the element, as `dotnet format` keeps it from the statement.
    // Not taken from Rider (its other defaults, outside these lists): wrapping long lines at 120, chopping `?:` and `for`, single-line
    // lambda blocks, blank lines after blocks, comments at column 0 left there, aligning binary operands and conditions in `if (`.

    /** A list of Rider's layout: its node, its open and close tokens (units), braces or parentheses. */
    private class RiderList(val node: ASTNode, val open: Int, val close: Int, val braces: Boolean, val construct: Construct?) {
        var active: Boolean? = null
        var chopped: Boolean? = null
        var base = -1
    }

    private fun list(node: ASTNode?): RiderList? {
        if (!options.riderLists || node == null || node.firstChildNode == null) return null
        if (lists.containsKey(node)) return lists[node]
        return lists.getOrPut(node) {
            if (kindOf(node) in ARGUMENT_LISTS) {
                var open = -1
                var close = -1
                var child = node.firstChildNode
                while (child != null) {
                    val t = child.elementType
                    if ((t === SyntaxKind.OpenParenToken || t === SyntaxKind.OpenBracketToken) && open < 0) open = unitAt[child.startOffset] ?: -1
                    if (t === SyntaxKind.CloseParenToken || t === SyntaxKind.CloseBracketToken) close = unitAt[child.startOffset] ?: -1
                    child = child.treeNext
                }
                if (open < 0) null else RiderList(node, open, close, braces = false, construct = null)
            } else {
                val construct = construct(node)?.takeIf { it in BRACE_LISTS }
                construct?.let { bracesOf(node, it) }?.let { RiderList(node, it.open, it.close, braces = true, construct = construct) }
            }
        }
    }

    private fun isItem(list: RiderList, node: ASTNode): Boolean = node.treeParent === list.node && node.firstChildNode != null &&
        node.startOffset > units[list.open].start && (list.close < 0 || node.startOffset < units[list.close].start) && firstCode(node) >= 0

    private fun items(list: RiderList): List<ASTNode> = list.node.getChildren(null).filter { isItem(list, it) }

    /** The list has elements (or its close token) on lines of their own: what is inside its elements is one indent further in. */
    private fun active(list: RiderList): Boolean = list.active ?: run {
        if (list.braces) multiLine(list.open, list.close)
        else list.close >= 0 && originalBreak[list.close] || list.node.getChildren(null).any { child ->
            child.elementType === SyntaxKind.CommaToken && unitAt[child.startOffset]?.let { originalBreak[it] } == true ||
                isItem(list, child) && originalBreak[firstCode(child)]
        }
    }.also { list.active = it }

    /** A multi-line brace list whose elements each go to a line of their own. */
    private fun chopped(list: RiderList): Boolean = list.chopped ?: run {
        if (!list.braces || !multiLine(list.open, list.close)) return@run false
        val option = when (list.construct) {
            Construct.OBJECT -> "csharp_new_line_before_members_in_object_initializers"
            Construct.ANONYMOUS -> "csharp_new_line_before_members_in_anonymous_types"
            else -> null
        }
        if (option != null && options.isSet(option)) {
            if (list.construct == Construct.OBJECT) options.newLineBeforeMembersInObjectInitializers else options.newLineBeforeMembersInAnonymousTypes
        } else items(list).any { multiLine(firstCode(it), lastCode(it)) }
    }.also { list.chopped = it }

    private fun multiLineBraces(list: RiderList?): Boolean = list != null && list.braces && multiLine(list.open, list.close)

    /** The brace list whose open or close token unit [i] is. */
    private fun braceListOf(i: Int): RiderList? {
        val t = type(i)
        if (t !== SyntaxKind.OpenBraceToken && t !== SyntaxKind.CloseBraceToken && t !== SyntaxKind.OpenBracketToken && t !== SyntaxKind.CloseBracketToken) return null
        return list(units[i].node?.treeParent)?.takeIf { it.braces && (it.open == i || it.close == i) }
    }

    /** The open brace starts an element of an outer list (`Sum([1,`, `{ {"a", 1},`): it stays where it is. */
    private fun opensItem(list: RiderList): Boolean {
        var top = list.node
        if (firstCode(top) != list.open) return false
        while (true) {
            val parent = top.treeParent ?: return false
            list(parent)?.let { if (isItem(it, top)) return true }
            if (parent.treeParent == null || firstCode(parent) != list.open) return false
            top = parent
        }
    }

    /** The list whose close token code unit [i] is: a line before it is a line of its elements. */
    private fun listClosedBy(i: Int): RiderList? = list(units[i].node?.treeParent)?.takeIf { it.close == i }

    private fun braceCategory(list: RiderList): String = if (list.construct == Construct.ANONYMOUS) "anonymous_types" else "object_collection_array_initializers"

    /** The column of the open brace, the close token and what the elements are one indent right of. */
    private fun listBase(list: RiderList): Int {
        if (list.base < 0) list.base = computeBase(list)
        return list.base
    }

    private fun computeBase(list: RiderList): Int {
        val bound = list.open
        var child: ASTNode = list.node
        var node: ASTNode = list.node
        while (true) {
            if (node !== list.node) controlParenthesesColumn(node, child)?.let { return it }
            headBefore(node, bound)?.let { return column[it] }
            val parent = node.treeParent ?: return 0
            if (parent.treeParent == null) return 0
            list(parent)?.let { if (isItem(it, node) && active(it)) return listBase(it) + ind }
            child = node
            node = parent
        }
    }

    /** Inside the parentheses of `if`, `while`, `foreach`, `using`, ...: Rider aligns with the first token after `(`. */
    private fun controlParenthesesColumn(statement: ASTNode, child: ASTNode): Int? {
        if (kindOf(statement) !in CONTROL_STATEMENTS) return null
        var open: ASTNode? = null
        var close: ASTNode? = null
        var c = statement.firstChildNode
        while (c != null) {
            if (c.elementType === SyntaxKind.OpenParenToken && open == null) open = c
            if (c.elementType === SyntaxKind.CloseParenToken && open != null && close == null) close = c
            c = c.treeNext
        }
        if (open == null || child.startOffset < open.startOffset || close != null && child.startOffset > close.startOffset) return null
        val first = firstCodeAfter(unitAt[open.startOffset] ?: return null)
        return if (first in 0 until current) finalColumn(first) else null
    }

    /**
     * The last line start before [bound] that belongs to [node] itself: its first token, a token of its own (`?` of a conditional,
     * `+` of a binary) or the operator of the member access it calls (`\n.Bar(`). Line starts deeper in an earlier part of the node
     * (the arguments of another call on the line) do not count.
     */
    private fun headBefore(node: ASTNode, bound: Int): Int? {
        val first = firstCode(node)
        if (first < 0 || first >= bound || bound == 0) return null
        var h = lineHead[bound - 1]
        while (h >= first) {
            if (isCode(h)) {
                val parent = units[h].node?.treeParent
                if (h == first || parent === node || parent?.treeParent === node && kindOf(parent) in MEMBER_ACCESSES) return h
            }
            if (h == 0) break
            h = lineHead[h - 1]
        }
        return null
    }

    /** The column of code unit [i] by Rider's lists when it starts a line; null: not a part of a list that Rider's layout places. */
    private fun riderColumn(i: Int): Int? {
        if (!options.riderLists) return null
        val node = units[i].node ?: return null
        list(node.treeParent)?.let { list ->
            // a one-line `{ ... }` / `[...]` on a line of its own is a continuation like any other
            if (i == list.close || i == list.open && multiLineBraces(list)) return listBase(list)
            if (type(i) === SyntaxKind.CommaToken) return listBase(list) + ind
        }
        var top = node
        while (true) {
            val parent = top.treeParent ?: return null
            list(parent)?.let { if (isItem(it, top)) return listBase(it) + ind }
            if (parent.treeParent == null || firstCode(parent) != i) return null
            top = parent
        }
    }

    /** Line feeds Rider's lists put between code units [a] and [b]; null: not theirs to say. */
    private fun riderBreaks(a: Int, b: Int): Int? {
        if (!options.riderLists) return null
        val after = braceListOf(a)
        val before = braceListOf(b)
        if (after != null && a == after.open && multiLineBraces(after)) return 1
        if (before != null && b == before.close) return if (multiLineBraces(before)) 1 else 0
        if (type(a) === SyntaxKind.CommaToken) {
            val list = list(units[a].node!!.treeParent)
            if (list != null && list.braces && chopped(list) && b != list.close) return 1
        }
        if (before != null) return if (multiLineBraces(before) && !opensItem(before) && options.braceOnNewLine(braceCategory(before))) 1 else 0
        if (type(a) === SyntaxKind.CommaToken && list(units[a].node!!.treeParent)?.braces == true) return 0
        return if (after != null && a == after.open) 0 else null
    }

    /** `{` of a multi-line initializer goes up to the line before when `.editorconfig` keeps such braces at the end of the line. */
    private fun riderJoins(a: Int, b: Int): Boolean? {
        if (!options.riderLists) return null
        val list = braceListOf(b)?.takeIf { b == it.open } ?: return if (braceListOf(a) != null) false else null
        return multiLineBraces(list) && !opensItem(list) && !options.braceOnNewLine(braceCategory(list)) && isCode(a)
    }

    // ---- columns

    /** The indent of the line unit [i] is on, in the new layout. */
    private fun lineColumn(i: Int): Int = column[lineHead[i]]

    private fun lineColumnOf(node: ASTNode): Int = firstCode(node).let { if (it < 0) 0 else lineColumn(it) }

    private fun braceColumn(braces: Braces): Int {
        if (braces.construct == Construct.BLOCK && blockIsStatement(braces.node)) return nodeColumn(braces.node) ?: 0
        val owner = owner(braces) ?: return 0
        return lineColumnOf(owner) + if (braces.construct == Construct.BLOCK && options.indentBraces) ind else 0
    }

    /** Where the `{` of [braces] is: its column when it starts a line (a lambda's keeps its place), otherwise where it would go. */
    private fun openColumn(braces: Braces): Int = if (braces.open <= current && startsLine[braces.open]) column[braces.open] else braceColumn(braces)

    private fun contentColumn(braces: Braces): Int =
        openColumn(braces) + if (braces.construct == Construct.BLOCK && !options.indentBlockContents) 0 else ind

    private fun labelColumn(switch: ASTNode): Int {
        val braces = bracesOf(switch, Construct.SWITCH) ?: return lineColumnOf(switch)
        return if (options.indentSwitchLabels) contentColumn(braces) else braceColumn(braces)
    }

    /** The column of [node]'s first token when it starts a line, by the structure; null: it is a continuation. */
    private fun nodeColumn(node: ASTNode): Int? {
        val parent = node.treeParent ?: return 0
        val kind = kindOf(node)
        return when (kindOf(parent)) {
            null -> 0
            SyntaxKind.CompilationUnit, SyntaxKind.FileScopedNamespaceDeclaration -> 0
            SyntaxKind.GlobalStatement, SyntaxKind.LabeledStatement -> nodeColumn(parent)
            SyntaxKind.Block -> contentColumn(bracesOf(parent, Construct.BLOCK) ?: return null)
            SyntaxKind.SwitchStatement -> if (kind === SyntaxKind.SwitchSection) labelColumn(parent) else null
            SyntaxKind.SwitchSection -> {
                val labels = labelColumn(parent.treeParent)
                when {
                    kind === SyntaxKind.CaseSwitchLabel || kind === SyntaxKind.CasePatternSwitchLabel || kind === SyntaxKind.DefaultSwitchLabel -> labels
                    kind === SyntaxKind.Block -> labels + if (options.indentCaseContentsWhenBlock) ind else 0
                    else -> labels + if (options.indentCaseContents) ind else 0
                }
            }
            SyntaxKind.IfStatement, SyntaxKind.ElseClause, SyntaxKind.WhileStatement, SyntaxKind.ForStatement, SyntaxKind.ForEachStatement,
            SyntaxKind.ForEachVariableStatement, SyntaxKind.UsingStatement, SyntaxKind.LockStatement, SyntaxKind.FixedStatement, SyntaxKind.DoStatement ->
                when {
                    node.psi !is io.github.dotnetsupport.csharp.lang.psi.CSharpStatement -> null
                    // stacked `using (a) using (b)` and `fixed`: one column, as Roslyn does
                    kind === kindOf(parent) && (kind === SyntaxKind.UsingStatement || kind === SyntaxKind.FixedStatement) -> lineColumnOf(parent)
                    else -> lineColumnOf(parent) + ind
                }
            else -> {
                val braces = construct(parent)?.let { bracesOf(parent, it) } ?: return null
                // what stands between the braces: the header of a type (base list, constraints) is a continuation
                val first = firstCode(node)
                if (first <= braces.open || braces.close in 0..first) return null
                when (braces.construct) {
                    Construct.TYPE, Construct.ENUM, Construct.ACCESSORS, Construct.OBJECT, Construct.ANONYMOUS, Construct.SWITCH_EXPRESSION ->
                        if (kind === SyntaxKind.CommaToken) null else contentColumn(braces)
                    else -> null
                }
            }
        }
    }

    /** The column of code unit [i] if it starts a line. */
    private fun codeColumn(i: Int): Int {
        placedBy = STRUCTURE
        val node = units[i].node!!
        if (interpolatedString(i) != null) return asIs(i)
        riderColumn(i)?.let { return it }
        braces(i)?.let { braces ->
            return when {
                // the `{` of `new[]` / `new int[]` on a line of its own is put under the line of `new`; the rest of the initializer stays
                braces.construct == Construct.COLLECTION && i == braces.open && kindOf(braces.node.treeParent) in ARRAY_CREATIONS -> lineColumnOf(braces.node.treeParent)
                braces.construct == Construct.COLLECTION -> asIs(i)
                braces.construct == Construct.COLLECTION_EXPRESSION -> if (i == braces.close) lineColumn(braces.open) else continuationColumn(i)
                // a lambda's `{` already on a line of its own keeps its place, as a continuation; one moved there goes under the lambda's line
                isLambdaBody(braces) && i == braces.open && originalBreak[i] -> continuationColumn(i)
                i == braces.close -> openColumn(braces)
                else -> braceColumn(braces)
            }
        }
        // the highest node that starts with this token
        var top = node
        while (true) {
            val parent = top.treeParent ?: break
            if (kindOf(parent) === SyntaxKind.CompilationUnit || parent.treeParent == null || firstCode(parent) != i) break
            top = parent
        }
        val parent = top.treeParent
        val parentKind = kindOf(parent)
        val topKind = kindOf(top)
        when {
            topKind === SyntaxKind.ElseClause -> return lineColumnOf(parent!!)
            topKind === SyntaxKind.CatchClause || topKind === SyntaxKind.FinallyClause -> return lineColumnOf(parent!!)
            type(i) === SyntaxKind.WhileKeyword && parentKind === SyntaxKind.DoStatement -> return lineColumnOf(parent!!)
            topKind === SyntaxKind.LabeledStatement -> {
                val normal = nodeColumn(top) ?: return continuationColumn(i)
                return when (options.indentLabels) {
                    "flush_left" -> 0
                    "no_change" -> originalColumn(i)
                    else -> maxOf(0, normal - ind)
                }
            }
            parentKind === SyntaxKind.QueryBody || parentKind === SyntaxKind.QueryExpression -> {
                var query = parent!!
                while (kindOf(query) !== SyntaxKind.QueryExpression) query = query.treeParent ?: break
                val from = firstCode(query)
                if (from in 0 until i) return finalColumn(from)
            }
            parentKind === SyntaxKind.CollectionExpression -> {
                val braces = bracesOf(parent!!, Construct.COLLECTION_EXPRESSION)
                if (braces != null && firstCodeAfter(braces.open) == i) return contentColumn(braces)
                return asIs(i)
            }
            topKind === SyntaxKind.AttributeList || afterAttributes(top) -> if (parent != null && isDeclaration(parent)) {
                val first = firstCode(parent)
                if (first in 0 until i) return lineColumn(first)
            }
        }
        nodeColumn(top)?.let { return it }
        return continuationColumn(i)
    }

    private fun firstCodeAfter(i: Int): Int = (i + 1 until units.size).firstOrNull { isCode(it) } ?: -1

    private fun afterAttributes(node: ASTNode): Boolean {
        var previous = node.treePrev
        while (previous != null && (previous.textLength == 0 || previous is com.intellij.psi.PsiWhiteSpace || previous.elementType in CSharpTokenTypes.COMMENTS)) previous = previous.treePrev
        return previous != null && kindOf(previous) === SyntaxKind.AttributeList
    }

    private fun isDeclaration(node: ASTNode): Boolean {
        val psi = node.psi
        return psi is io.github.dotnetsupport.csharp.lang.psi.CSharpMemberDeclaration || psi is io.github.dotnetsupport.csharp.lang.psi.CSharpAccessorDeclaration ||
            psi is io.github.dotnetsupport.csharp.lang.psi.CSharpLocalFunctionStatement || kindOf(node) === SyntaxKind.EnumMemberDeclaration
    }

    /** A continuation line keeps its offset from its anchor (the start of the statement, the member, ...), moved as the anchor moved. */
    private fun continuationColumn(i: Int): Int {
        var node = units[i].node?.treeParent
        while (node != null && node.treeParent != null) {
            if (firstCode(node) != i) {
                val construct = construct(node)
                if (!options.riderLists && (construct == Construct.COLLECTION || construct == Construct.COLLECTION_EXPRESSION)) return asIs(i)
                if (isAnchor(node)) {
                    val anchor = firstCode(node)
                    if (anchor in 0 until i) {
                        placedBy = anchor
                        return maxOf(0, originalColumn(i) + delta(anchor))
                    }
                }
            }
            node = node.treeParent
        }
        return asIs(i)
    }

    private fun asIs(i: Int): Int {
        placedBy = AS_IS
        return originalColumn(i)
    }

    private fun isAnchor(node: ASTNode): Boolean {
        val psi = node.psi
        if (psi is io.github.dotnetsupport.csharp.lang.psi.CSharpStatement || psi is io.github.dotnetsupport.csharp.lang.psi.CSharpMemberDeclaration ||
            psi is io.github.dotnetsupport.csharp.lang.psi.CSharpAccessorDeclaration || psi is io.github.dotnetsupport.csharp.lang.psi.CSharpSwitchLabel) return true
        return when (kindOf(node)) {
            SyntaxKind.UsingDirective, SyntaxKind.ExternAliasDirective, SyntaxKind.EnumMemberDeclaration, SyntaxKind.SwitchExpressionArm,
            SyntaxKind.AnonymousObjectMemberDeclarator, SyntaxKind.ElseClause, SyntaxKind.CatchClause, SyntaxKind.FinallyClause, SyntaxKind.AttributeList -> true
            // an element of a list: what continues it moves with it
            else -> construct(node.treeParent) == Construct.OBJECT || list(node.treeParent)?.let { isItem(it, node) } == true
        }
    }

    /** How far the line of unit [anchor] moved. */
    private fun delta(anchor: Int): Int {
        val head = lineHead[anchor]
        return column[head] - originalColumn(head)
    }

    /** The column unit [i] ends up at, for what aligns with a token in the middle of a line (query clauses). */
    private fun finalColumn(i: Int): Int {
        val head = lineHead[i]
        var col = column[head]
        for (k in head + 1..i) {
            val previous = units[k - 1]
            val newline = text.lastIndexOf('\n', previous.end - 1).takeIf { it >= previous.start }
            col = if (newline != null) previous.end - newline - 1 else col + (previous.end - previous.start)
            col += gap[k]
        }
        return col
    }

    /**
     * The column of a comment or a region directive on its own line: the one of the code that follows it, the contents of a block before
     * its `}`. Before a continuation line the comment keeps its own offset from the anchor, as the line does.
     */
    private fun columnBeforeCode(i: Int, region: Boolean): Int {
        val next = (i + 1 until units.size).firstOrNull { isCode(it) } ?: return 0
        listClosedBy(next)?.let { return listBase(it) + ind }
        val braces = braces(next)
        if (braces != null && next == braces.close && braces.construct != Construct.COLLECTION && braces.construct != Construct.COLLECTION_EXPRESSION) {
            return if (region) openColumn(braces) else contentColumn(braces)
        }
        // a comment above a label takes the column of the statements, not the outdented one of the label
        val label = units[next].node?.treeParent?.takeIf { kindOf(it) === SyntaxKind.LabeledStatement && firstCode(it) == next }
        if (label != null) nodeColumn(label)?.let { return it }
        val column = codeColumn(next)
        return when (val by = placedBy) {
            STRUCTURE -> column
            AS_IS -> originalColumn(i)
            else -> maxOf(0, originalColumn(i) + delta(by))
        }
    }

    /**
     * A `//` comment on its own line right under a line that ends with a comment: Roslyn puts it under that comment, unless it is at
     * its own column already. Null: no such comment above.
     */
    private fun underTrailingComment(i: Int, normal: Int): Int? {
        if (units[i].node?.elementType !== SyntaxKind.SingleLineCommentTrivia || i == 0) return null
        val above = i - 1
        if (units[above].kind != Kind.COMMENT || startsLine[above] || text.subSequence(units[above].end, units[i].start).count { it == '\n' } != 1) return null
        return if (originalColumn(i) == normal) normal else finalColumn(above)
    }

    // ---- line breaks and spaces

    /** Line feeds Roslyn puts between unit [a] and the code unit [b]: 0 when it keeps what is there. */
    private fun forcedLines(a: Int, b: Int): Int {
        if (type(a) === SyntaxKind.SemicolonToken && parentKind(a) === SyntaxKind.FileScopedNamespaceDeclaration &&
            units[b].kind != Kind.DIRECTIVE && units[b].kind != Kind.REGION && units[b].kind != Kind.DISABLED) return 2
        if (!isCode(a) || !isCode(b)) return 0
        if (frozen(a, b) || interpolatedString(b) != null) return 0
        riderBreaks(a, b)?.let { return it }
        braces(b)?.let { braces ->
            if (b == braces.open && wrapsBefore(braces) && options.braceOnNewLine(category(braces))) return 1
            if (b == braces.close && wraps(braces)) return 1
        }
        // `else`, `catch`, `finally` of a statement that spans lines
        val keyword = type(b)
        if (keyword === SyntaxKind.ElseKeyword || keyword === SyntaxKind.CatchKeyword || keyword === SyntaxKind.FinallyKeyword) {
            val statement = units[b].node!!.treeParent?.treeParent
            val wanted = when (keyword) {
                SyntaxKind.ElseKeyword -> options.newLineBeforeElse
                SyntaxKind.CatchKeyword -> options.newLineBeforeCatch
                else -> options.newLineBeforeFinally
            }
            if (statement != null && wanted && firstCode(statement).let { it >= 0 && lineOf(units[it].start) != lineOf(units[b].start) }) return 1
        }
        braces(a)?.let { braces ->
            if (a == braces.open && wraps(braces)) return 1
            if (a == braces.close && wraps(braces) && braces.construct != Construct.OBJECT && braces.construct != Construct.ANONYMOUS &&
                braces.construct != Construct.SWITCH_EXPRESSION && (braces.construct != Construct.BLOCK || ownerIsStatementLike(braces))) {
                if (type(b) === SyntaxKind.SemicolonToken && parentKind(b) === SyntaxKind.EmptyStatement) return 1
                return when (type(b)) {
                    SyntaxKind.ElseKeyword, SyntaxKind.CatchKeyword, SyntaxKind.FinallyKeyword -> 0
                    SyntaxKind.CloseParenToken, SyntaxKind.CommaToken, SyntaxKind.SemicolonToken, SyntaxKind.DotToken, SyntaxKind.CloseBracketToken,
                    SyntaxKind.QuestionToken, SyntaxKind.ExclamationToken, SyntaxKind.WhileKeyword, SyntaxKind.EqualsToken, SyntaxKind.MinusGreaterThanToken -> 0
                    else -> 1
                }
            }
        }
        if (type(a) === SyntaxKind.CommaToken) {
            val parent = units[a].node!!.treeParent
            val construct = construct(parent)
            if (construct == Construct.OBJECT && options.newLineBeforeMembersInObjectInitializers ||
                construct == Construct.ANONYMOUS && options.newLineBeforeMembersInAnonymousTypes) {
                val braces = bracesOf(parent)
                if (braces != null && wraps(braces) && b != braces.close) return 1
            }
        }
        if (!options.preserveSingleLineStatements && type(a) === SyntaxKind.SemicolonToken) {
            val statement = units[a].node!!.treeParent
            if (statement.psi is io.github.dotnetsupport.csharp.lang.psi.CSharpStatement && kindOf(statement) !== SyntaxKind.ForStatement &&
                kindOf(statement.treeParent) === SyntaxKind.Block) return 1
        }
        // Rider's style: in braces that wrap, the statement or the member after one that ends at [a] starts a line (`{ x(); y(); }`, `class A { int a; int b; }`)
        if (options.riderBlocks && (type(a) === SyntaxKind.SemicolonToken || type(a) === SyntaxKind.CloseBraceToken)) {
            val braces = wrappingContainerEndedAt(a)
            if (braces != null && braces.open in 0 until a && b != braces.close && riderWraps(braces)) return 1
        }
        return 0
    }

    /** The block or the type a statement or a member that ends with unit [a] stands in, climbing the nodes that end there too. */
    private fun wrappingContainerEndedAt(a: Int): Braces? {
        var node = units[a].node?.treeParent
        while (node != null && lastCode(node) == a) {
            val parent = node.treeParent ?: return null
            if (kindOf(parent) === SyntaxKind.Block && node.psi is io.github.dotnetsupport.csharp.lang.psi.CSharpStatement) return bracesOf(parent)
            if (construct(parent) == Construct.TYPE && node.psi is io.github.dotnetsupport.csharp.lang.psi.CSharpMemberDeclaration) return bracesOf(parent)
            node = parent
        }
        return null
    }

    private fun ownerIsStatementLike(braces: Braces): Boolean = when (kindOf(braces.node.treeParent)) {
        SyntaxKind.SimpleLambdaExpression, SyntaxKind.ParenthesizedLambdaExpression, SyntaxKind.AnonymousMethodExpression -> false
        else -> true
    }

    /** `csharp_new_line_before_open_brace` without the category, `..._else` false: the brace or the keyword goes up to the line before. */
    private fun joins(a: Int, b: Int): Boolean {
        if (!isCode(a) || !isCode(b)) return false
        riderJoins(a, b)?.let { return it }
        braces(b)?.let { braces ->
            if (b == braces.open && wrapsBefore(braces) && !options.braceOnNewLine(category(braces))) return true
        }
        braces(a)?.let { braces ->
            if (a == braces.close && braces.construct == Construct.BLOCK && wraps(braces)) {
                return type(b) === SyntaxKind.ElseKeyword && !options.newLineBeforeElse || type(b) === SyntaxKind.CatchKeyword && !options.newLineBeforeCatch ||
                    type(b) === SyntaxKind.FinallyKeyword && !options.newLineBeforeFinally
            }
        }
        return false
    }

    private fun layOut() {
        val starts = ArrayList<Int>()
        starts += 0
        for (k in text.indices) if (text[k] == '\n') starts += k + 1
        lineStarts = starts.toIntArray()
        val size = units.size
        startsLine = BooleanArray(size)
        lineHead = IntArray(size)
        column = IntArray(size)
        gap = IntArray(size)
        indents = IntArray(size) { -1 }
        spacings = arrayOfNulls(size)
        originalBreak = BooleanArray(size) { it == 0 || text.subSequence(units[it - 1].end, units[it].start).contains('\n') }
        val regions = ArrayList<Int>()
        for (i in 0 until size) {
            current = i
            val unit = units[i]
            if (i == 0) {
                startsLine[0] = true
            } else {
                val whitespace = text.subSequence(units[i - 1].end, unit.start)
                val breaks = whitespace.count { it == '\n' }
                when {
                    unit.kind == Kind.DISABLED || units[i - 1].kind == Kind.DISABLED && breaks == 0 -> {
                        spacings[i] = Spacing.getReadOnlySpacing()
                        startsLine[i] = breaks > 0
                        gap[i] = whitespace.length
                    }
                    breaks == 0 && frozen(i - 1, i) -> {
                        spacings[i] = Spacing.getReadOnlySpacing()
                        gap[i] = whitespace.length
                    }
                    breaks > 0 && joins(i - 1, i) -> {
                        spacings[i] = Spacing.createSpacing(1, 1, 0, false, 0)
                        gap[i] = 1
                    }
                    else -> {
                        val forced = forcedLines(i - 1, i)
                        if (breaks > 0 || forced > 0) {
                            spacings[i] = Spacing.createSpacing(0, 0, forced, true, KEEP_BLANK_LINES)
                            startsLine[i] = true
                        } else {
                            val spaces = spaces(i - 1, i)
                            if (spaces == null) {
                                spacings[i] = Spacing.getReadOnlySpacing()
                                gap[i] = whitespace.length
                            } else {
                                spacings[i] = Spacing.createSpacing(spaces, spaces, 0, false, 0)
                                gap[i] = spaces
                            }
                        }
                    }
                }
            }
            lineHead[i] = if (startsLine[i]) i else lineHead[i - 1]
            if (startsLine[i]) {
                column[i] = when (unit.kind) {
                    Kind.CODE -> if (i > 0 && frozen(i - 1, i)) originalColumn(i) else codeColumn(i)
                    Kind.COMMENT -> columnBeforeCode(i, region = false).let { underTrailingComment(i, it) ?: it }
                    Kind.DOC -> columnBeforeCode(i, region = false)
                    Kind.REGION -> if (directiveKeyword(unit.node!!) === SyntaxKind.EndRegionKeyword && regions.isNotEmpty()) column[regions.removeAt(regions.size - 1)]
                        else columnBeforeCode(i, region = true).also { regions += i }
                    Kind.DIRECTIVE -> 0
                    Kind.COMMENT_TAIL -> maxOf(0, originalColumn(i) + delta(unit.head))
                    Kind.DISABLED -> originalColumn(i)
                }
                indents[i] = column[i]
            }
        }
    }

    /** The indent of a new line before unit [index] (Enter, Auto-Indent of an empty line). */
    fun indentBefore(index: Int): Int {
        if (index >= units.size) return 0
        if (isCode(index)) listClosedBy(index)?.let { return listBase(it) + ind }
        val braces = if (isCode(index)) braces(index) else null
        if (braces != null && index == braces.close && braces.construct != Construct.COLLECTION) return contentColumn(braces)
        return when (units[index].kind) {
            Kind.CODE -> codeColumn(index)
            Kind.DIRECTIVE -> 0
            else -> columnBeforeCode(index, units[index].kind == Kind.REGION)
        }
    }

    // ---- spaces on one line

    private fun isWord(type: IElementType?) = type != null && WORDS.contains(type)

    /** The spaces between code units [a] and [b] on one line; null: as they are. */
    private fun spaces(a: Int, b: Int): Int? {
        if (!isCode(a) || !isCode(b) || frozen(a, b, before = true)) return null
        if ((isStringPiece(a) || isStringPiece(b)) && interpolatedString(a).let { it != null && it === interpolatedString(b) }) {
            // `{ x}` of a hole is `{x}`
            if (type(a) === SyntaxKind.OpenBraceToken && parentKind(a) === SyntaxKind.Interpolation && !isStringPiece(b)) return 0
            if (type(b) === SyntaxKind.CloseBraceToken && parentKind(b) === SyntaxKind.Interpolation && !isStringPiece(a)) return 0
            return null
        }
        val spaces = rawSpaces(a, b) ?: return null
        if (spaces == 0 && units[b].start > units[a].end && wouldMerge(a, b)) return 1
        return spaces
    }

    private fun wouldMerge(a: Int, b: Int): Boolean {
        val left = text[units[a].end - 1]
        val right = text[units[b].start]
        if (isWordChar(left) && isWordChar(right)) return true
        return "$left$right" in MERGING
    }

    private fun isWordChar(c: Char) = c.isLetterOrDigit() || c == '_' || c == '@'

    private fun flag(value: Boolean) = if (value) 1 else 0

    private fun rawSpaces(a: Int, b: Int): Int? {
        val pt = type(a)
        val nt = type(b)
        val pp = parentKind(a)
        val np = parentKind(b)
        // ; and ,
        if (nt === SyntaxKind.SemicolonToken) {
            return when {
                np === SyntaxKind.ForStatement && pt === SyntaxKind.SemicolonToken -> flag(options.spaceAfterSemicolonInFor)
                np === SyntaxKind.ForStatement && pt === SyntaxKind.OpenParenToken -> 0
                np === SyntaxKind.ForStatement -> flag(options.spaceBeforeSemicolonInFor)
                // `while (x) ;`: one space before an empty statement stays one
                np === SyntaxKind.EmptyStatement -> if (units[b].start > units[a].end) 1 else 0
                else -> 0
            }
        }
        if (pt === SyntaxKind.SemicolonToken) {
            if (pp !== SyntaxKind.ForStatement) return 1
            // `for (int i = n; --i >= 0;)`: nothing after the condition, nothing before `)`; `for (;;)` is `for (; ; )`
            if (nt === SyntaxKind.CloseParenToken && a > 0 && type(a - 1) !== SyntaxKind.SemicolonToken) return 0
            return flag(options.spaceAfterSemicolonInFor)
        }
        if (nt === SyntaxKind.CommaToken) return flag(options.spaceBeforeComma)
        if (pt === SyntaxKind.CommaToken) {
            if ((nt === SyntaxKind.CloseBracketToken || nt === SyntaxKind.GreaterThanToken) && (pp === SyntaxKind.ArrayRankSpecifier || pp === SyntaxKind.TypeArgumentList)) return 0
            return flag(options.spaceAfterComma)
        }
        // member access
        if (nt === SyntaxKind.DotToken) return flag(options.spaceBeforeDot)
        if (pt === SyntaxKind.DotToken) return flag(options.spaceAfterDot)
        if (pt === SyntaxKind.MinusGreaterThanToken || nt === SyntaxKind.MinusGreaterThanToken || pt === SyntaxKind.ColonColonToken || nt === SyntaxKind.ColonColonToken) return 0
        if (pt === SyntaxKind.QuestionToken && pp === SyntaxKind.ConditionalAccessExpression) return 0
        if (nt === SyntaxKind.QuestionToken && np === SyntaxKind.ConditionalAccessExpression) return 0
        // :
        if (nt === SyntaxKind.ColonToken || pt === SyntaxKind.ColonToken) {
            val colonParent = if (nt === SyntaxKind.ColonToken) np else pp
            val before = nt === SyntaxKind.ColonToken
            return when (colonParent) {
                SyntaxKind.BaseList -> flag(if (before) options.spaceBeforeColonInInheritance else options.spaceAfterColonInInheritance)
                SyntaxKind.BaseConstructorInitializer, SyntaxKind.ThisConstructorInitializer, SyntaxKind.TypeParameterConstraintClause, SyntaxKind.ConditionalExpression -> 1
                else -> if (before) 0 else 1
            }
        }
        if ((pt === SyntaxKind.QuestionToken && pp === SyntaxKind.ConditionalExpression) || (nt === SyntaxKind.QuestionToken && np === SyntaxKind.ConditionalExpression)) return 1
        if (pt === SyntaxKind.LessThanToken && pp in TYPE_LISTS || nt === SyntaxKind.LessThanToken && np in TYPE_LISTS) return 0
        if (nt === SyntaxKind.GreaterThanToken && np in TYPE_LISTS) return 0
        if (pt === SyntaxKind.DotDotToken && pp === SyntaxKind.RangeExpression) return 0
        // ( )
        if (pt === SyntaxKind.OpenParenToken) return inside(a, empty = nt === SyntaxKind.CloseParenToken)
        if (nt === SyntaxKind.CloseParenToken) return inside(b, empty = false)
        if (nt === SyntaxKind.OpenParenToken) return beforeOpenParenthesis(a, b)
        if (pt === SyntaxKind.CloseParenToken) return afterCloseParenthesis(a, b)
        // [ ]
        if (pt === SyntaxKind.OpenBracketToken) return if (nt === SyntaxKind.CloseBracketToken) flag(options.spaceInEmptySquareBrackets) else flag(options.spaceInSquareBrackets)
        if (nt === SyntaxKind.CloseBracketToken) return flag(options.spaceInSquareBrackets)
        if (nt === SyntaxKind.OpenBracketToken) return beforeOpenBracket(a, b)
        if (pt === SyntaxKind.CloseBracketToken) return afterCloseBracket(a, b)
        // { }
        if (nt === SyntaxKind.OpenBraceToken || pt === SyntaxKind.OpenBraceToken || nt === SyntaxKind.CloseBraceToken) return 1
        if (pt === SyntaxKind.CloseBraceToken) {
            return when (nt) {
                SyntaxKind.QuestionToken -> if (np === SyntaxKind.ConditionalExpression) 1 else 0
                SyntaxKind.ExclamationToken -> if (np === SyntaxKind.SuppressNullableWarningExpression) 0 else 1
                else -> 1
            }
        }
        // < > of type argument and parameter lists
        if (pt === SyntaxKind.LessThanToken && pp in TYPE_LISTS || nt === SyntaxKind.LessThanToken && np in TYPE_LISTS) return 0
        if (nt === SyntaxKind.GreaterThanToken && np in TYPE_LISTS) return 0
        if (pt === SyntaxKind.GreaterThanToken && pp in TYPE_LISTS) {
            return when {
                nt === SyntaxKind.QuestionToken && np === SyntaxKind.NullableType -> 0
                nt === SyntaxKind.AsteriskToken && np === SyntaxKind.PointerType -> 0
                nt === SyntaxKind.ExclamationToken && np === SyntaxKind.SuppressNullableWarningExpression -> 0
                nt === SyntaxKind.GreaterThanToken && np in TYPE_LISTS -> 0
                else -> 1
            }
        }
        // ? of nullable types, * of pointers
        if (nt === SyntaxKind.QuestionToken && np === SyntaxKind.NullableType) return 0
        if (pt === SyntaxKind.QuestionToken && pp === SyntaxKind.NullableType) return if (isWord(nt)) 1 else null
        if (nt === SyntaxKind.AsteriskToken && (np === SyntaxKind.PointerType || np === SyntaxKind.FunctionPointerType)) return 0
        if (pt === SyntaxKind.AsteriskToken && np !== SyntaxKind.FunctionPointerParameterList && pp === SyntaxKind.PointerType) return if (isWord(nt)) 1 else null
        if (pt === SyntaxKind.AsteriskToken && pp === SyntaxKind.FunctionPointerType) return 0
        // unary operators
        if (pp in PREFIX_UNARY && units[a].node === units[a].node!!.treeParent.firstChildNode) return 0
        if (np in PREFIX_UNARY && units[b].node === units[b].node!!.treeParent.firstChildNode && isWord(pt)) return 1
        if (pt === SyntaxKind.OperatorKeyword) return 1
        if (np in POSTFIX_UNARY && units[b].node === units[b].node!!.treeParent.lastChildNode) return 0
        // ..
        if (pt === SyntaxKind.DotDotToken) return if (pp === SyntaxKind.RangeExpression) 0 else 1
        if (nt === SyntaxKind.DotDotToken && np === SyntaxKind.RangeExpression) return 0
        // => and binary operators
        if (pt === SyntaxKind.EqualsGreaterThanToken || nt === SyntaxKind.EqualsGreaterThanToken) return 1
        if (isBinaryOperator(b) || isBinaryOperator(a)) {
            return when (options.binaryOperators) {
                "none" -> if (isAssignmentLike(if (isBinaryOperator(b)) b else a)) 1 else 0
                "ignore" -> if (isAssignmentLike(if (isBinaryOperator(b)) b else a)) 1 else null
                else -> 1
            }
        }
        if (isWord(pt) && isWord(nt)) return 1
        return null
    }

    private fun isBinaryOperator(i: Int): Boolean {
        val t = type(i) ?: return false
        if (t !in BINARY_TOKENS) return false
        return when (parentKind(i)) {
            in BINARY_EXPRESSIONS, in ASSIGNMENTS, SyntaxKind.EqualsValueClause, SyntaxKind.NameEquals, SyntaxKind.RelationalPattern, SyntaxKind.OrPattern,
            SyntaxKind.AndPattern, SyntaxKind.LetClause -> true
            else -> false
        }
    }

    private fun isAssignmentLike(i: Int): Boolean = parentKind(i).let { it in ASSIGNMENTS || it === SyntaxKind.EqualsValueClause || it === SyntaxKind.NameEquals }

    /** Spaces after `(` or before `)` of unit [i]. */
    private fun inside(i: Int, empty: Boolean): Int = when (val kind = parentKind(i)) {
        SyntaxKind.ArgumentList -> flag(if (empty) options.spaceInEmptyCallParentheses else options.spaceInCallParentheses)
        SyntaxKind.ParameterList -> if (isLambdaParameters(units[i].node!!.treeParent)) 0 else
            flag(if (empty) options.spaceInEmptyDeclarationParentheses else options.spaceInDeclarationParentheses)
        SyntaxKind.CastExpression -> flag(options.spaceInCastParentheses)
        SyntaxKind.ParenthesizedExpression -> flag(options.spaceInExpressionParentheses)
        in CONTROL_STATEMENTS -> flag(options.spaceInControlParentheses && !empty)
        else -> if (kind === SyntaxKind.CatchDeclaration || kind === SyntaxKind.CatchFilterClause) flag(options.spaceInControlParentheses) else 0
    }

    private fun isLambdaParameters(list: ASTNode): Boolean = kindOf(list.treeParent).let {
        it === SyntaxKind.ParenthesizedLambdaExpression || it === SyntaxKind.AnonymousMethodExpression
    }

    private fun beforeOpenParenthesis(a: Int, b: Int): Int? {
        val pt = type(a)
        val list = units[b].node!!.treeParent
        val np = kindOf(list)
        when (np) {
            SyntaxKind.ArgumentList, SyntaxKind.AttributeArgumentList -> {
                val owner = kindOf(list.treeParent)
                if (owner === SyntaxKind.InvocationExpression || owner === SyntaxKind.ObjectCreationExpression || owner === SyntaxKind.ImplicitObjectCreationExpression ||
                    owner === SyntaxKind.BaseConstructorInitializer || owner === SyntaxKind.ThisConstructorInitializer || owner === SyntaxKind.PrimaryConstructorBaseType ||
                    owner === SyntaxKind.Attribute
                ) return flag(options.spaceBeforeCallParenthesis)
            }
            SyntaxKind.ParameterList -> if (!isLambdaParameters(list)) return flag(options.spaceBeforeDeclarationParenthesis)
            SyntaxKind.TypeOfExpression, SyntaxKind.SizeOfExpression, SyntaxKind.DefaultExpression, SyntaxKind.CheckedExpression, SyntaxKind.UncheckedExpression,
            SyntaxKind.MakeRefExpression, SyntaxKind.RefTypeExpression, SyntaxKind.RefValueExpression -> return 0
            in CONTROL_STATEMENTS, SyntaxKind.CatchDeclaration, SyntaxKind.CatchFilterClause -> return flag(options.spaceAfterControlKeyword)
            SyntaxKind.PositionalPatternClause -> if (pt === SyntaxKind.IdentifierToken || pt === SyntaxKind.GreaterThanToken) return 0
            SyntaxKind.ConstructorConstraint -> return 0
        }
        return when {
            pt === SyntaxKind.OpenParenToken || pt === SyntaxKind.OpenBracketToken -> 0
            pp(a) in PREFIX_UNARY && units[a].node === units[a].node!!.treeParent.firstChildNode -> 0
            // `[A] (x) => x`: an attribute list before lambda parameters
            pt === SyntaxKind.CloseBracketToken && parentKind(a) === SyntaxKind.AttributeList -> 1
            pt === SyntaxKind.CloseParenToken || pt === SyntaxKind.CloseBracketToken -> 0
            isWord(pt) -> 1
            pt === SyntaxKind.GreaterThanToken && parentKind(a) in TYPE_LISTS -> 0
            else -> 1
        }
    }

    private fun pp(i: Int) = parentKind(i)

    private fun afterCloseParenthesis(a: Int, b: Int): Int? {
        val nt = type(b)
        if (parentKind(a) === SyntaxKind.CastExpression) return flag(options.spaceAfterCast)
        return when (nt) {
            SyntaxKind.CloseParenToken, SyntaxKind.CloseBracketToken, SyntaxKind.OpenBracketToken, SyntaxKind.OpenParenToken -> 0
            SyntaxKind.QuestionToken -> if (parentKind(b) === SyntaxKind.ConditionalExpression) 1 else 0
            SyntaxKind.ExclamationToken, SyntaxKind.PlusPlusToken, SyntaxKind.MinusMinusToken -> if (parentKind(b) in POSTFIX_UNARY) 0 else 1
            SyntaxKind.AsteriskToken -> if (parentKind(b) === SyntaxKind.PointerType) 0 else 1
            else -> 1
        }
    }

    private fun beforeOpenBracket(a: Int, b: Int): Int? {
        val pt = type(a)
        return when (parentKind(b)) {
            SyntaxKind.AttributeList -> if (pt === SyntaxKind.OpenParenToken) 0 else if (pt === SyntaxKind.CloseBracketToken) null else 1
            SyntaxKind.CollectionExpression, SyntaxKind.ListPattern -> if (pt === SyntaxKind.OpenParenToken || pt === SyntaxKind.OpenBracketToken) 0 else 1
            else -> if (pt === SyntaxKind.OpenBraceToken) 1 else flag(options.spaceBeforeOpenSquareBracket)
        }
    }

    private fun afterCloseBracket(a: Int, b: Int): Int? {
        val nt = type(b)
        if (parentKind(a) === SyntaxKind.AttributeList) return if (nt === SyntaxKind.OpenBracketToken) null else 1
        return when (nt) {
            SyntaxKind.OpenParenToken, SyntaxKind.CloseParenToken -> 0
            SyntaxKind.OpenBracketToken -> if (parentKind(b) === SyntaxKind.AttributeList) null else 0
            SyntaxKind.QuestionToken -> if (parentKind(b) === SyntaxKind.ConditionalExpression) 1 else 0
            SyntaxKind.ExclamationToken, SyntaxKind.PlusPlusToken, SyntaxKind.MinusMinusToken -> if (parentKind(b) in POSTFIX_UNARY) 0 else 1
            SyntaxKind.AsteriskToken -> if (parentKind(b) === SyntaxKind.PointerType) 0 else 1
            else -> 1
        }
    }

    companion object {
        private val WORDS = TokenSet.orSet(
            CSharpSyntaxFacts.IsKeywordKind, CSharpSyntaxFacts.IsContextualKeyword,
            TokenSet.create(
                SyntaxKind.IdentifierToken, SyntaxKind.NumericLiteralToken, SyntaxKind.StringLiteralToken, SyntaxKind.CharacterLiteralToken,
                SyntaxKind.SingleLineRawStringLiteralToken, SyntaxKind.MultiLineRawStringLiteralToken, SyntaxKind.Utf8StringLiteralToken,
                SyntaxKind.Utf8SingleLineRawStringLiteralToken, SyntaxKind.Utf8MultiLineRawStringLiteralToken, SyntaxKind.InterpolatedStringExpression,
                SyntaxKind.ArgListKeyword, SyntaxKind.UnderscoreToken, SyntaxKind.InterpolatedStringStartToken, SyntaxKind.InterpolatedVerbatimStringStartToken,
            SyntaxKind.InterpolatedSingleLineRawStringStartToken, SyntaxKind.InterpolatedMultiLineRawStringStartToken, SyntaxKind.InterpolatedStringEndToken,
            SyntaxKind.InterpolatedRawStringEndToken,
            ),
        )

        private val STRING_PIECES = TokenSet.create(
            SyntaxKind.InterpolatedStringStartToken, SyntaxKind.InterpolatedVerbatimStringStartToken, SyntaxKind.InterpolatedSingleLineRawStringStartToken,
            SyntaxKind.InterpolatedMultiLineRawStringStartToken, SyntaxKind.InterpolatedStringTextToken, SyntaxKind.InterpolatedStringEndToken,
            SyntaxKind.InterpolatedRawStringEndToken,
        )

        private val CREATIONS = TokenSet.create(SyntaxKind.ObjectCreationExpression, SyntaxKind.ImplicitObjectCreationExpression, SyntaxKind.WithExpression)

        private val ARGUMENT_LISTS = TokenSet.create(
            SyntaxKind.ArgumentList, SyntaxKind.BracketedArgumentList, SyntaxKind.AttributeArgumentList, SyntaxKind.ParameterList, SyntaxKind.BracketedParameterList,
        )

        private val BRACE_LISTS = setOf(Construct.OBJECT, Construct.ANONYMOUS, Construct.COLLECTION, Construct.COLLECTION_EXPRESSION)

        private val MEMBER_ACCESSES = TokenSet.create(SyntaxKind.SimpleMemberAccessExpression, SyntaxKind.PointerMemberAccessExpression, SyntaxKind.MemberBindingExpression)

        private const val STRUCTURE = -1
        private const val AS_IS = -2

        private val ARRAY_CREATIONS = TokenSet.create(
            SyntaxKind.ArrayCreationExpression, SyntaxKind.ImplicitArrayCreationExpression, SyntaxKind.StackAllocArrayCreationExpression,
            SyntaxKind.ImplicitStackAllocArrayCreationExpression,
        )

        private val BINARY_EXPRESSIONS = TokenSet.create(
            SyntaxKind.AddExpression, SyntaxKind.SubtractExpression, SyntaxKind.MultiplyExpression, SyntaxKind.DivideExpression, SyntaxKind.ModuloExpression,
            SyntaxKind.LeftShiftExpression, SyntaxKind.RightShiftExpression, SyntaxKind.UnsignedRightShiftExpression, SyntaxKind.LogicalOrExpression,
            SyntaxKind.LogicalAndExpression, SyntaxKind.BitwiseOrExpression, SyntaxKind.BitwiseAndExpression, SyntaxKind.ExclusiveOrExpression,
            SyntaxKind.EqualsExpression, SyntaxKind.NotEqualsExpression, SyntaxKind.LessThanExpression, SyntaxKind.LessThanOrEqualExpression,
            SyntaxKind.GreaterThanExpression, SyntaxKind.GreaterThanOrEqualExpression, SyntaxKind.IsExpression, SyntaxKind.AsExpression,
            SyntaxKind.CoalesceExpression,
        )

        private val ASSIGNMENTS = TokenSet.create(
            SyntaxKind.SimpleAssignmentExpression, SyntaxKind.AddAssignmentExpression, SyntaxKind.SubtractAssignmentExpression,
            SyntaxKind.MultiplyAssignmentExpression, SyntaxKind.DivideAssignmentExpression, SyntaxKind.ModuloAssignmentExpression,
            SyntaxKind.AndAssignmentExpression, SyntaxKind.ExclusiveOrAssignmentExpression, SyntaxKind.OrAssignmentExpression,
            SyntaxKind.LeftShiftAssignmentExpression, SyntaxKind.RightShiftAssignmentExpression, SyntaxKind.CoalesceAssignmentExpression,
            SyntaxKind.UnsignedRightShiftAssignmentExpression,
        )

        /** Blank lines are kept as they are, as Roslyn keeps them. */
        const val KEEP_BLANK_LINES = 1000

        private val TYPE_LISTS = TokenSet.create(SyntaxKind.TypeArgumentList, SyntaxKind.TypeParameterList, SyntaxKind.FunctionPointerParameterList)

        private val CONTROL_STATEMENTS = TokenSet.create(
            SyntaxKind.IfStatement, SyntaxKind.WhileStatement, SyntaxKind.DoStatement, SyntaxKind.ForStatement, SyntaxKind.ForEachStatement,
            SyntaxKind.ForEachVariableStatement, SyntaxKind.SwitchStatement, SyntaxKind.UsingStatement, SyntaxKind.LockStatement, SyntaxKind.FixedStatement,
        )

        private val PREFIX_UNARY = TokenSet.create(
            SyntaxKind.UnaryPlusExpression, SyntaxKind.UnaryMinusExpression, SyntaxKind.BitwiseNotExpression, SyntaxKind.LogicalNotExpression,
            SyntaxKind.PreIncrementExpression, SyntaxKind.PreDecrementExpression, SyntaxKind.PointerIndirectionExpression, SyntaxKind.AddressOfExpression,
            SyntaxKind.IndexExpression,
        )

        private val POSTFIX_UNARY = TokenSet.create(SyntaxKind.PostIncrementExpression, SyntaxKind.PostDecrementExpression, SyntaxKind.SuppressNullableWarningExpression)

        private val BINARY_TOKENS = TokenSet.orSet(
            CSharpSyntaxFacts.IsBinaryExpressionOperatorToken, CSharpSyntaxFacts.IsAssignmentExpressionOperatorToken,
            TokenSet.create(SyntaxKind.EqualsToken, SyntaxKind.LessThanToken, SyntaxKind.GreaterThanToken, SyntaxKind.LessThanEqualsToken, SyntaxKind.GreaterThanEqualsToken,
                SyntaxKind.EqualsEqualsToken, SyntaxKind.ExclamationEqualsToken, SyntaxKind.OrKeyword, SyntaxKind.AndKeyword),
        )

        /** Two characters that make another token when the space between them goes. */
        private val MERGING = setOf(
            "++", "--", "&&", "||", "==", "!=", "<=", ">=", "<<", "=>", "->", "??", "::", "..", "//", "/*", "*/", "+=", "-=", "*=", "/=", "%=", "&=",
            "|=", "^=", "?.", "?[", "=="
        )
    }
}

/** The platform's formatter over [NativeCSharpLayout]: one leaf block per unit under the root, the spacing and the indent the layout chose. */
class NativeCSharpFormattingModelBuilder : CustomFormattingModelBuilder {
    override fun isEngagedToFormat(context: PsiElement?): Boolean = NativeCSharpFormatting.engaged(context?.containingFile)

    override fun createModel(formattingContext: FormattingContext): FormattingModel {
        val file = formattingContext.containingFile
        val settings = formattingContext.codeStyleSettings
        val document = PsiDocumentManager.getInstance(file.project).getDocument(file)
        val text = document?.immutableCharSequence ?: file.viewProvider.contents
        val indentOptions = settings.getIndentOptionsByFile(file)
        // not engaged («None», CSharpier, dotnet format, the switch on the server) and still asked: the platform falls back to the first
        // builder of the language when none is engaged — an empty root changes nothing (Reformat Selection with «None» must not format)
        val root = if (text.length == file.textLength && NativeCSharpFormatting.engaged(file)) {
            NativeCSharpRootBlock(NativeCSharpLayout(file.node, text, CSharpFormatOptions.of(file, indentOptions)), text.length)
        } else NativeCSharpRootBlock(null, file.textLength)
        // the platform shifts the inner lines of a multi-line block with its first one: never the insides of a verbatim or raw string
        return if (document != null) object : DocumentBasedFormattingModel(root, document, file.project, settings, file.fileType, file) {
            override fun shiftIndentInsideRange(node: ASTNode?, range: TextRange, indent: Int): TextRange = range
        } else FormattingModelProvider.createFormattingModelForPsiFile(file, root, settings)
    }
}

internal class NativeCSharpRootBlock(private val layout: NativeCSharpLayout?, private val length: Int) : Block {
    private val children: List<Block> by lazy {
        val layout = layout ?: return@lazy emptyList()
        layout.units.mapIndexed { index, unit -> NativeCSharpLeafBlock(TextRange(unit.start, unit.end), index, layout.indents[index]) }
    }

    override fun getTextRange(): TextRange = TextRange(0, length)
    override fun getSubBlocks(): List<Block> = children
    override fun getWrap(): Wrap? = null
    override fun getIndent(): Indent = Indent.getAbsoluteNoneIndent()
    override fun getAlignment(): Alignment? = null

    override fun getSpacing(child1: Block?, child2: Block): Spacing? {
        val layout = layout ?: return null
        if (child1 == null) return Spacing.createSpacing(0, 0, 0, true, NativeCSharpLayout.KEEP_BLANK_LINES)
        return layout.spacings[(child2 as NativeCSharpLeafBlock).index]
    }

    override fun getChildAttributes(newChildIndex: Int): ChildAttributes =
        ChildAttributes(Indent.getSpaceIndent(layout?.indentBefore(newChildIndex) ?: 0), null)

    override fun isIncomplete(): Boolean = false
    override fun isLeaf(): Boolean = false
}

internal class NativeCSharpLeafBlock(private val range: TextRange, val index: Int, column: Int) : Block {
    private val indent = if (column <= 0) Indent.getNoneIndent() else Indent.getSpaceIndent(column)

    override fun getTextRange(): TextRange = range
    override fun getSubBlocks(): List<Block> = emptyList()
    override fun getWrap(): Wrap? = null
    override fun getIndent(): Indent = indent
    override fun getAlignment(): Alignment? = null
    override fun getSpacing(child1: Block?, child2: Block): Spacing? = null
    override fun getChildAttributes(newChildIndex: Int): ChildAttributes = ChildAttributes(Indent.getNoneIndent(), null)
    override fun isIncomplete(): Boolean = false
    override fun isLeaf(): Boolean = true
}
