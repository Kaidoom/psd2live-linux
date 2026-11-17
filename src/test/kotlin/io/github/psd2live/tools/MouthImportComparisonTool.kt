package io.github.psd2live.tools

import io.github.psd2live.application.*
import io.github.psd2live.core.*
import io.github.psd2live.core.modifier.RigModifierStack
import io.github.psd2live.core.recipe.RigRecipe
import io.github.psd2live.core.recipe.RigModules
import io.github.psd2live.core.recipe.ModuleChoice
import io.github.psd2live.project.*
import io.github.psd2live.ui.parameterKeyMarks
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.umamo.runtime.model.ParameterId
import java.nio.file.Path
import java.security.MessageDigest
import kotlin.test.Test
import kotlin.test.assertEquals

/** Read-only comparison: PSD2LIVE_TOOLS=1, PSD2LIVE_IMPORT_PSD and PSD2LIVE_COMPARE_PROJECT. */
class MouthImportComparisonTool {
    @Test fun compareSavedAndImportedMouths(): Unit = runBlocking {
        requireTools()
        val psd = Path.of(requireNotNull(System.getenv("PSD2LIVE_IMPORT_PSD"))).toAbsolutePath()
        val project = Path.of(requireNotNull(System.getenv("PSD2LIVE_COMPARE_PROJECT"))).toAbsolutePath()
        val builder = WorkspacePreviewBuilder()
        val rows = mutableListOf<Pair<String, RigPreviewModel>>()
        val notes = mutableListOf<String>()
        var fittedRuntime: WorkspaceRuntime<RigPreviewModel>? = null
        var fittedCapture: WorkspaceCapture<RigPreviewModel>? = null
        fun record(label: String, document: WorkspaceDocument, model: RigPreviewModel) {
            rows += label to model
            notes += "$label: recipe=${document.config().recipe}; outline=${document.config().mouthOutlineEnabled}"
            notes += "  keys=${model.rig.puppet.parameterKeyMarks()[ParameterId("ParamMouthOpenY")]}"
            notes += "  mouth layers=" + model.analysis.layers.filter { it.semantic.tag.name.startsWith("MOUTH") }
                .joinToString { "${it.source.name}: ${it.semantic.tag} ${it.bounds}" }
            notes += "  artwork=" + document.source.layers.filter { it.name.contains("mouth", true) }.joinToString {
                "${it.name}: ${MessageDigest.getInstance("SHA-256").digest(it.raster.rgba).joinToString("") { b -> "%02x".format(b) }}"
            }
            notes += "  mouth shape=${document.config().mouthShape}, visibility=${document.config().layerVisibility}"
            notes += "  journal operations=" + document.rigEdits.authoringJournal.groupingBy {
                it["op"]?.jsonPrimitive?.content ?: "?"
            }.eachCount()
            document.rigEdits.modifierStack?.let { json ->
                for (entry in RigModifierStack.fromJson(json).instances) {
                    if (entry.type.startsWith("mouth")) notes += "  ${entry.id}: ${entry.type}@${entry.version} ${entry.config}"
                }
            }
            val deletion = buildJsonArray { addJsonObject {
                put("op", "parameter_keys"); put("parameter", "ParamMouthOpenY"); put("action", "delete")
                putJsonArray("values") { add(.05f) }
            } }
            notes += "  delete .05: " + runCatching { WorkspaceDocumentEdits.journal(document, model, deletion); "accepted" }
                .fold({ it }, { it.message.orEmpty() })
        }
        ProjectRepository().open(project).use { opened ->
            val document = opened.history.head().snapshot
            record("Saved project", document, builder.build(document))
        }
        for ((label, recipe, mode) in listOf(
            Triple("Fresh fitted", RigRecipe.Modern, WorkspaceInitialModifiers.RECOMMENDED),
            Triple("Fresh legacy", RigRecipe.Legacy, WorkspaceInitialModifiers.CUSTOM),
            Triple("Legacy flat / no outline", RigRecipe.Legacy, WorkspaceInitialModifiers.CUSTOM),
            Triple("Fitted without add-ons", RigRecipe.Modern.copy(modules = mapOf(
                RigModules.MOUTH_VOWEL.id to ModuleChoice(on = false),
                RigModules.MOUTH_CAPTURE.id to ModuleChoice(on = false))), WorkspaceInitialModifiers.CUSTOM))) {
            val runtime = WorkspaceRuntime<RigPreviewModel>({ builder.build(it) })
            val imported = WorkspaceSourceImporter(runtime).importPsd(psd, null, runtime.state.value.state,
                initialConfig = PipelineConfig(recipe = recipe, mouthShape = if (label.startsWith("Legacy flat")) "flat" else "smile"), modifiers = mode,
                preset = WorkspaceInitialPreset(mouthOutline = !label.startsWith("Legacy flat")).takeIf { mode == WorkspaceInitialModifiers.CUSTOM }).capture
            record(label, imported.document, imported.model)
            if (label == "Fresh fitted") { fittedRuntime = runtime; fittedCapture = imported }
        }
        val commands = WorkspaceDocumentCommands(requireNotNull(fittedRuntime))
        var edited = requireNotNull(fittedCapture)
        for (value in listOf(.05f, .25f)) {
            val deletion = buildJsonArray { addJsonObject {
                put("op", "parameter_keys"); put("parameter", "ParamMouthOpenY"); put("action", "delete")
                putJsonArray("values") { add(value) }
            } }
            edited = commands.execute(edited.projectId, edited.state, "Delete mouth key $value",
                listOf(WorkspaceDocumentOperation("keyform_apply", buildJsonObject { put("changes", deletion) })), MutationAuthor.USER).capture
        }
        assertEquals(listOf(0f, .5f, 1f), edited.model.rig.puppet.parameterKeyMarks().getValue(ParameterId("ParamMouthOpenY")).gridKeys)
        assertEquals(RigIrCompiler.compile(edited.model), RigIrCompiler.compile(builder.build(edited.document)))
        record("Fitted / keys deleted", edited.document, edited.model)
        val mouth = rows[1].second.analysis.layers.first { it.semantic.tag == SemanticTag.MOUTH_OPEN }.bounds
        val rect = Bounds(mouth.left - 25f, mouth.top - 25f, mouth.right + 25f, mouth.bottom + 25f)
        val frames = rows.flatMap { (label, model) -> listOf(0f, .05f, .25f, .5f, 1f).map { value ->
            "$label / $value" to Renderer(model, 320).render(mapOf("ParamMouthOpenY" to value), rect)
        } }
        val directory = output("mouth-import-comparison")
        sheet(frames, directory.resolve("comparison.png"), columns = 5)
        sheet(frames.take(10), directory.resolve("saved-vs-fitted.png"), columns = 5)
        for (index in 0..4) {
            val current = frames[5 + index].second
            val withoutAddons = frames[20 + index].second
            val pixels = current.getRGB(0, 0, current.width, current.height, null, 0, current.width)
            val other = withoutAddons.getRGB(0, 0, withoutAddons.width, withoutAddons.height, null, 0, withoutAddons.width)
            notes += "Add-on comparison at ${listOf(0f, .05f, .25f, .5f, 1f)[index]}: ${pixels.indices.count { pixels[it] != other[it] }} differing pixels"
        }
        directory.resolve("report.txt").writeText(notes.joinToString("\n"))
        notes.forEach(::println)
    }
}
