package io.github.dotnetsupport.ml

import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.options.BoundConfigurable
import com.intellij.openapi.ui.DialogPanel
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.bindSelected
import com.intellij.ui.dsl.builder.bindText
import com.intellij.ui.dsl.builder.bindValue
import com.intellij.ui.dsl.builder.panel
import io.github.dotnetsupport.DotNetBundle
import javax.swing.JLabel

/**
 * Settings | .NET | ML completion (only in a build with the models, META-INF/csharp-ml.xml): the ML ranking of the completion list, the
 * grey text of the transformer with its gates, the big model, a directory with other models, and what is loaded (models, kernels).
 */
@Suppress("unused")
class CSharpMlConfigurable : BoundConfigurable(DotNetBundle.message("page.ml")) {
    private val settings get() = CSharpMlSettings.getInstance()
    private val status = JLabel()
    private val nnStatus = JLabel()

    override fun createPanel(): DialogPanel = panel {
        group(DotNetBundle.message("ml.ranker.group")) {
            row { checkBox(DotNetBundle.message("ml.ranker.enabled")).bindSelected(settings::rankerEnabled).comment(DotNetBundle.message("ml.ranker.enabled.comment")) }
            row { checkBox(DotNetBundle.message("ml.showMarker")).bindSelected(settings::showMarker).comment(DotNetBundle.message("ml.showMarker.comment")) }
            row(DotNetBundle.message("ml.acceptanceWeight")) {
                spinner(0.0..2.0, 0.1).bindValue(settings::acceptanceWeight).comment(DotNetBundle.message("ml.acceptanceWeight.comment"))
            }
            row(DotNetBundle.message("ml.status")) { cell(status) }
        }
        group(DotNetBundle.message("ml.inline.group")) {
            row { checkBox(DotNetBundle.message("ml.inline.enabled")).bindSelected(settings::inlineEnabled).comment(DotNetBundle.message("ml.inline.enabled.comment")) }
            row { checkBox(DotNetBundle.message("ml.inline.bigModel")).bindSelected(settings::bigModel).comment(DotNetBundle.message("ml.inline.bigModel.comment")) }
            row(DotNetBundle.message("ml.inline.threshold")) {
                spinner(0.5..0.99, 0.05).bindValue(settings::inlineThreshold).comment(DotNetBundle.message("ml.inline.threshold.comment"))
            }
            row(DotNetBundle.message("ml.inline.dotThreshold")) {
                spinner(0.3..0.99, 0.05).bindValue(settings::inlineDotThreshold).comment(DotNetBundle.message("ml.inline.dotThreshold.comment"))
            }
            row(DotNetBundle.message("ml.inline.emptyLineThreshold")) {
                spinner(0.05..0.99, 0.05).bindValue(settings::inlineEmptyLineThreshold).comment(DotNetBundle.message("ml.inline.emptyLineThreshold.comment"))
            }
            row { checkBox(DotNetBundle.message("ml.inline.showClosers")).bindSelected(settings::inlineShowClosers).comment(DotNetBundle.message("ml.inline.showClosers.comment")) }
            row { checkBox(DotNetBundle.message("ml.inline.guessStrings")).bindSelected(settings::inlineGuessStrings).comment(DotNetBundle.message("ml.inline.guessStrings.comment")) }
            row { checkBox(DotNetBundle.message("ml.inline.inStrings")).bindSelected(settings::inlineInStrings).comment(DotNetBundle.message("ml.inline.inStrings.comment")) }
            row { checkBox(DotNetBundle.message("ml.inline.inComments")).bindSelected(settings::inlineInComments).comment(DotNetBundle.message("ml.inline.inComments.comment")) }
            row { checkBox(DotNetBundle.message("ml.inline.debugLog")).bindSelected(settings::inlineDebugLog).comment(DotNetBundle.message("ml.inline.debugLog.comment")) }
            row(DotNetBundle.message("ml.nnStatus")) { cell(nnStatus) }
        }
        row(DotNetBundle.message("ml.modelDirectory")) {
            textFieldWithBrowseButton(FileChooserDescriptorFactory.createSingleFolderDescriptor().withTitle(DotNetBundle.message("ml.modelDirectory")))
                .align(AlignX.FILL).bindText(settings::modelDirectory).comment(DotNetBundle.message("ml.modelDirectory.comment"))
        }
    }

    override fun reset() {
        super.reset()
        refreshStatus()
    }

    override fun apply() {
        val before = snapshot()
        super.apply()
        if (snapshot() != before) CSharpMlModels.getInstance().reset()
        refreshStatus()
    }

    /** What a change of makes the loaded models stale (the gates and the closers are read per call). */
    private fun snapshot() = listOf(settings.modelDirectory, settings.rankerEnabled, settings.inlineEnabled, settings.bigModel)

    private fun refreshStatus() {
        val models = CSharpMlModels.getInstance()
        status.text = models.status(settings.modelDirectory)
        nnStatus.text = models.nnStatus(settings.modelDirectory)
    }
}
