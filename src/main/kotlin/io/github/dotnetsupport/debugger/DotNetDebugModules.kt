package io.github.dotnetsupport.debugger

import com.google.gson.JsonObject
import com.intellij.icons.AllIcons
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.table.TableView
import com.intellij.util.ui.ColumnInfo
import com.intellij.util.ui.ListTableModel
import javax.swing.JComponent

/**
 * The modules of the debuggee, as the Modules window of Visual Studio / Rider: what is loaded, from where, whether its symbols are (the
 * reason a breakpoint in a library is not bound). Filled from the adapter's `module` events, which come from the very start of the program
 * (`dotnet-debugger` sends one per loaded assembly: id, name, path, symbol status), so the list is kept from the first event whether the tab
 * is open or not.
 */
class DotNetDebugModules {
    /** One row: the order of loading is the order the program asked for its assemblies. */
    data class Module(val id: String, val order: Int, val name: String, val path: String, val symbols: String, val version: String, val userCode: Boolean?)

    private val rows = LinkedHashMap<String, Module>()
    private var loaded = 0
    private var model: ListTableModel<Module>? = null

    @Synchronized
    fun all(): List<Module> = rows.values.toList()

    /** `module` event: `new`, `changed` (symbols loaded later) or `removed` (an unloaded context). */
    fun event(body: JsonObject) {
        val module = body.getAsJsonObject("module") ?: return
        val id = module.get("id")?.takeIf { !it.isJsonNull }?.asString ?: return
        synchronized(this) {
            when (body.string("reason")) {
                "removed" -> rows.remove(id)
                else -> rows[id] = parse(id, module, rows[id]?.order ?: ++loaded)
            }
        }
        refresh()
    }

    /** The answer to `modules` (the list when the adapter is asked instead of telling). */
    fun all(answer: JsonObject) {
        synchronized(this) {
            for (module in answer.objects("modules")) {
                val id = module.get("id")?.takeIf { !it.isJsonNull }?.asString ?: continue
                rows[id] = parse(id, module, rows[id]?.order ?: ++loaded)
            }
        }
        refresh()
    }

    fun component(): JComponent {
        // no version or "user code" columns: `dotnet-debugger` sends neither (the table is made before the first module comes)
        val table = ListTableModel<Module>(
            column("#") { it.order.toString() }, column("Name") { it.name },
            column("Symbols") { it.symbols }, column("Path") { it.path },
        )
        model = table
        refresh()
        return JBScrollPane(TableView(table).apply {
            setShowGrid(false)
            // the number narrow, the name and the symbols as wide as their usual text, the path the rest (seen live: equal columns cut the names)
            listOf(48, 300, 220).forEachIndexed { index, width ->
                columnModel.getColumn(index).preferredWidth = com.intellij.util.ui.JBUI.scale(width)
                if (index == 0) columnModel.getColumn(index).maxWidth = com.intellij.util.ui.JBUI.scale(width)
            }
        })
    }

    private fun refresh() {
        val table = model ?: return
        val items = all()
        ApplicationManager.getApplication().invokeLater({ table.items = items }, ModalityState.any())
    }

    companion object {
        val ICON = AllIcons.Nodes.PpLibFolder

        fun parse(id: String, module: JsonObject, order: Int): Module = Module(
            id = id, order = order,
            name = module.string("name").orEmpty(), path = module.string("path").orEmpty(),
            symbols = module.string("symbolStatus").orEmpty(), version = module.string("version").orEmpty(), userCode = module.bool("isUserCode"),
        )

        private fun column(name: String, value: (Module) -> String) = object : ColumnInfo<Module, String>(name) {
            override fun valueOf(item: Module): String = value(item)
        }
    }
}
