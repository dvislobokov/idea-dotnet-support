package io.github.dotnetsupport

import com.intellij.openapi.editor.colors.EditorColorsScheme
import com.intellij.openapi.editor.colors.impl.EditorColorsSchemeImpl
import com.intellij.openapi.util.JDOMUtil

/**
 * The bundled scheme "Rider Dark" / "Rider Light" read from its file: headless tests load only Default and Darcula into the manager,
 * no bundled scheme of a plugin.
 */
internal fun riderScheme(dark: Boolean): EditorColorsScheme {
    val file = if (dark) "RiderDark" else "RiderLight"
    val element = checkNotNull(RiderSchemesAnchor::class.java.getResourceAsStream("/colorSchemes/$file.xml")) { "$file.xml" }.use { JDOMUtil.load(it) }
    return EditorColorsSchemeImpl(null).apply { readExternal(element) }
}

private object RiderSchemesAnchor
