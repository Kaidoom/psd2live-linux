package io.github.psd2live.tools

import io.github.psd2live.application.*
import io.github.psd2live.core.*
import io.github.psd2live.project.MutationAuthor
import io.github.psd2live.scenario.req
import kotlinx.serialization.json.*
import kotlinx.coroutines.runBlocking
import org.umamo.render.eval.CpuDeformationEvaluator
import org.umamo.runtime.model.*
import java.nio.file.Path
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertTrue

/** Diagnostic only: edits disposable imported state, never the input PSD. */
class MouthDeformationProbeTool {
    @Test fun traceVertexEdits(): Unit = runBlocking {
        requireTools()
        val psd = Path.of(requireNotNull(System.getenv("PSD2LIVE_IMPORT_PSD"))).toAbsolutePath()
        val builder = WorkspacePreviewBuilder()
        val runtime = WorkspaceRuntime<RigPreviewModel>({ builder.build(it) })
        val initial = WorkspaceSourceImporter(runtime).importPsd(psd, null, runtime.state.value.state,
            modifiers = WorkspaceInitialModifiers.RECOMMENDED).capture
        val commands = WorkspaceDocumentCommands(runtime)
        val evaluator = CpuDeformationEvaluator()
        var current = initial
        val notes = mutableListOf<String>()
        for (tag in listOf(SemanticTag.MOUTH_OPEN, SemanticTag.MOUTH_CLOSE)) {
            val layer = initial.model.analysis.layers.first { it.semantic.tag == tag }
            val id = initial.model.rig.ownLayerIdByDrawableId.entries.first { it.value == layer.source.id.raw }.key
            val drawable = initial.model.rig.puppet.drawables.single { it.id.raw == id }
            notes += "$tag / $id geometry=${drawable.geometryGrid?.axes?.map { it.parameterId.raw to it.keys.toList() }}"
            notes += "channels=${drawable.channelGrids.gridsByChannel.mapValues { (_, grid) -> grid.axes.map { it.parameterId.raw to it.keys.toList() } }}"
            for (value in listOf(0f, .05f, .25f, .5f, 1f, .4f)) {
                current = runtime.checkout(current.projectId, current.state, initial.historyHead)
                if (value == .4f) current = commands.execute(current.projectId, current.state, "Add key", listOf(
                    WorkspaceDocumentOperation("keyform_apply", req("changes" to listOf(mapOf("op" to "parameter_keys",
                        "parameter" to "ParamMouthOpenY", "action" to "add", "values" to listOf(value)))))), MutationAuthor.USER).capture
                val model = current.model.rig.puppet
                val pose = mapOf(ParameterId("ParamMouthOpenY") to value)
                val defaults = model.parameters.associate { it.id to it.default }
                val key = model.drawables.single { it.id.raw == id }.geometryGrid?.axes.orEmpty()
                    .associate { it.parameterId.raw to (pose[it.parameterId] ?: defaults.getValue(it.parameterId)) } + ("ParamMouthOpenY" to value)
                val snapping = nearestKeyPose(GeometryKeyforms.editingAxes(model, "mesh", id), pose, defaults)
                val before = evaluator.evaluate(model, pose).worldPositions.getValue(DrawableId(id))
                val operation = WorkspaceDocumentOperation("rig_deform", req("changes" to listOf(mapOf(
                    "target" to "mesh:$id", "key" to key, "selection" to mapOf("indices" to listOf(0)),
                    "operations" to listOf(mapOf("type" to "translate", "delta" to listOf(.005f, 0f)))))))
                val outcome = runCatching {
                    current = commands.execute(current.projectId, current.state, "Move vertex", listOf(operation), MutationAuthor.USER).capture
                    val after = evaluator.evaluate(current.model.rig.puppet, pose).worldPositions.getValue(DrawableId(id))
                    val delta = before.indices.maxOf { abs(after[it] - before[it]) }
                    val issues = current.model.rig.modifierReport?.get("entries")?.jsonArray.orEmpty()
                        .flatMap { it.jsonObject["override_issues"]?.jsonArray.orEmpty() }
                    assertTrue(delta > .01f, "$tag / $value lost the vertex edit")
                    assertTrue(issues.isEmpty(), "$tag / $value: $issues")
                    "moved=$delta, overrideIssues=$issues"
                }.fold({ it }, { "FAILED: ${it.message}" })
                notes += "$tag key=$value snap=$snapping $outcome"
                println(notes.last().take(700))
            }
        }
        output("mouth-deformation-probe").resolve("report.txt").writeText(notes.joinToString("\n"))
        assertTrue(notes.none { "FAILED:" in it }, notes.filter { "FAILED:" in it }.joinToString("\n"))
    }
}
