package io.github.dotnetsupport.jsonschema

import com.intellij.codeInsight.editorActions.TypedHandlerDelegate
import com.intellij.json.psi.JsonFile
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.fileTypes.FileType
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiFile
import io.github.dotnetsupport.appsettings.AppSettingsSchemas

/**
 * `,` typed right before a `,` in appsettings*.json steps over it (DEV_JOURNEY 4.9, 0.1.100): the completion of a key of the schema writes
 * `"Journey": {|},` with the comma when a property follows, and the hand that types `}` and `,` after the value got `},,`.
 */
class AppSettingsCommaTypedHandler : TypedHandlerDelegate() {
    override fun beforeCharTyped(c: Char, project: Project, editor: Editor, file: PsiFile, fileType: FileType): Result {
        if (c != ',' || file !is JsonFile || !AppSettingsSchemas.isAppSettings(file.name) || editor.caretModel.caretCount != 1) return Result.CONTINUE
        val offset = editor.caretModel.offset
        if (!stepsOver(editor.document.immutableCharSequence, offset)) return Result.CONTINUE
        editor.caretModel.moveToOffset(offset + 1)
        return Result.STOP
    }

    companion object {
        /** A `,` right at [offset], outside of a string of its line (the quotes before it on the line are paired). */
        fun stepsOver(text: CharSequence, offset: Int): Boolean {
            if (text.getOrNull(offset) != ',') return false
            val lineStart = if (offset == 0) 0 else text.lastIndexOf('\n', offset - 1) + 1
            var quotes = 0
            var i = lineStart
            while (i < offset) {
                when (text[i]) {
                    '\\' -> i++
                    '"' -> quotes++
                }
                i++
            }
            return quotes % 2 == 0
        }
    }
}
