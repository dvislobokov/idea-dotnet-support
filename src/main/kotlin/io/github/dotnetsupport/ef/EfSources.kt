package io.github.dotnetsupport.ef

import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VirtualFileVisitor
import java.io.IOException

/** A migration as its `.Designer.cs` declares it: `[DbContext(typeof(AppDbContext))] [Migration("20240101120000_Init")]`. */
data class EfMigrationSource(val id: String, val dbContext: String?) {
    val name: String get() = id.substringAfter('_')
}

enum class EfDeclarationKind { DB_CONTEXT, MIGRATION }

data class EfDeclaration(val kind: EfDeclarationKind, val name: String, val offset: Int)

class EfMigrationFile(val migration: EfMigrationSource, val designer: VirtualFile) {
    /** `20240101120000_Init.cs` next to `20240101120000_Init.Designer.cs`. */
    val source: VirtualFile? get() = designer.parent?.findChild(designer.name.removeSuffix(DESIGNER_SUFFIX) + ".cs")

    companion object {
        const val DESIGNER_SUFFIX = ".Designer.cs"
    }
}

/**
 * What the sources tell about EF without building anything: the migrations and the `DbContext` classes.
 * The tool knows better (`migrations list`, `dbcontext list`), but it needs a successful build and takes seconds.
 */
object EfSources {
    private val MIGRATION = Regex("""\[\s*Migration\s*\(\s*"([^"]+)"\s*\)\s*]""")
    private val DB_CONTEXT = Regex("""\[\s*DbContext\s*\(\s*typeof\s*\(\s*([\w.]+)\s*\)\s*\)\s*]""")

    // `class AppDb(DbContextOptions<AppDb> options) : IdentityDbContext<User>(options), IFoo`
    private val CLASS = Regex("""((?:\b(?:public|internal|abstract|sealed|partial|static)\s+)*)class\s+(\w+)\s*(?:<[^>{]*>)?\s*(?:\([^)]*\))?\s*:\s*([^{;]+)""")
    private val BASE_CONTEXT = Regex("""^(?:[\w.]+\.)?\w*DbContext\b""")
    private val BASE_MIGRATION = Regex("""^(?:[\w.]+\.)?Migration\b""")
    private val DESTRUCTIVE = Regex("""\bmigrationBuilder\s*\.\s*(Drop(?:Table|Column|Schema|Sequence))\s*(?:<[^>]*>)?\s*\(\s*(?:name\s*:\s*)?"([^"]+)"""")
    private val SKIPPED_DIRECTORIES = setOf("bin", "obj", "node_modules", ".git", ".vs", ".idea", "wwwroot")

    fun parseDesigner(text: CharSequence): EfMigrationSource? {
        val id = MIGRATION.find(text)?.groupValues?.get(1) ?: return null
        return EfMigrationSource(id, DB_CONTEXT.find(text)?.groupValues?.get(1)?.substringAfterLast('.'))
    }

    /**
     * The classes EF works with: the non-abstract ones whose first base type is some `...DbContext`, and the migrations
     * (`partial class AddUsers : Migration`). [EfDeclaration.offset] is where the class name starts: the place of a gutter icon.
     */
    fun declarations(text: CharSequence): List<EfDeclaration> {
        if ("DbContext" !in text && "Migration" !in text) return emptyList()
        return CLASS.findAll(text).mapNotNull { match ->
            val base = match.groupValues[3].trim()
            val kind = when {
                "abstract" in match.groupValues[1] -> null
                BASE_CONTEXT.containsMatchIn(base) -> EfDeclarationKind.DB_CONTEXT
                BASE_MIGRATION.containsMatchIn(base) -> EfDeclarationKind.MIGRATION
                else -> null
            } ?: return@mapNotNull null
            EfDeclaration(kind, match.groupValues[2], match.groups[2]!!.range.first)
        }.toList()
    }

    fun dbContextClasses(text: CharSequence): List<String> = declarations(text).filter { it.kind == EfDeclarationKind.DB_CONTEXT }.map { it.name }

    /** `DropTable Orders`, `DropColumn Email`, ... of the `Up` method: what loses data when the migration is applied. */
    fun destructiveOperations(text: CharSequence): List<String> {
        val up = text.indexOf("void Up(").takeIf { it >= 0 } ?: return emptyList()
        val down = text.indexOf("void Down(", up).takeIf { it >= 0 } ?: text.length
        return DESTRUCTIVE.findAll(text.subSequence(up, down)).map { "${it.groupValues[1]} ${it.groupValues[2]}" }.toList()
    }

    /** Migrations of the project in the order they apply, oldest first. Reads files: not for EDT in a big project. */
    fun migrations(projectFile: VirtualFile): List<EfMigrationFile> = sourceFiles(projectFile)
        .filter { it.name.endsWith(EfMigrationFile.DESIGNER_SUFFIX, ignoreCase = true) }
        .mapNotNull { file -> load(file)?.let(::parseDesigner)?.let { EfMigrationFile(it, file) } }
        .sortedBy { it.migration.id }

    /** `DbContext` classes of the project, the ones that have migrations first. Reads files: not for EDT in a big project. */
    fun dbContexts(projectFile: VirtualFile): List<String> {
        val files = sourceFiles(projectFile)
        val withMigrations = files.filter { it.name.endsWith(EfMigrationFile.DESIGNER_SUFFIX, ignoreCase = true) }
            .mapNotNull { load(it)?.let(::parseDesigner)?.dbContext }
        val declared = files.filterNot { it.name.endsWith(EfMigrationFile.DESIGNER_SUFFIX, ignoreCase = true) }
            .flatMap { load(it)?.let(::dbContextClasses).orEmpty() }
        return (withMigrations + declared.sorted()).distinct()
    }

    /** Where each `DbContext` of the project is declared. Reads files: not for EDT in a big project. */
    fun dbContextFiles(projectFile: VirtualFile): List<Pair<VirtualFile, String>> =
        sourceFiles(projectFile).flatMap { file -> load(file)?.let(::dbContextClasses).orEmpty().map { file to it } }

    private fun sourceFiles(projectFile: VirtualFile): List<VirtualFile> {
        val root = projectFile.parent ?: return emptyList()
        val result = ArrayList<VirtualFile>()
        VfsUtilCore.visitChildrenRecursively(root, object : VirtualFileVisitor<Unit>() {
            override fun visitFile(file: VirtualFile): Boolean {
                if (file.isDirectory) return file == root || file.name !in SKIPPED_DIRECTORIES
                if (file.extension.equals("cs", ignoreCase = true)) result += file
                return true
            }
        })
        return result
    }

    private fun load(file: VirtualFile): String? = try {
        VfsUtilCore.loadText(file)
    } catch (_: IOException) {
        null
    }
}
