package io.github.psd2live.application

import io.github.psd2live.core.*
import io.github.psd2live.scenario.Characters
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.umamo.render.eval.CpuDeformationEvaluator
import org.umamo.runtime.model.DrawableId
import org.umamo.runtime.model.ParameterId
import kotlin.test.*

class ClosedMouthPresetTest {
    @Test fun recommendedPreservesClosedDrawingSwaps() = checkPreset("recommended", requireAddons = true)
    @Test fun fullPreservesClosedDrawingSwaps() = checkPreset("full", requireAddons = true)
    @Test fun defaultPreservesClosedDrawingSwaps() = checkPreset("default")
    @Test fun minimalPreservesClosedDrawingSwaps() = checkPreset("minimal")


    private fun checkPreset(mode: String, requireAddons: Boolean = false): Unit = runBlocking {
        val document = Characters.closedMouthed()
        val builder = WorkspacePreviewBuilder()
        val preview = builder.build(document)
        val initialized = WorkspaceModifierEdits.apply(WorkspaceDocumentOperation("modifier_initialize",
            buildJsonObject { put("modifiers", mode) }), document, preview)
        val model = builder.build(initialized)
        val puppet = model.rig.puppet
        val openId = DrawableId(model.rig.ownLayerIdByDrawableId.entries.single { it.value == "mouth" }.key)
        val closedId = DrawableId(model.rig.ownLayerIdByDrawableId.entries.single { it.value == "closed-mouth" }.key)
        val evaluator = CpuDeformationEvaluator()
        fun swap(pose: Map<String, Float>, opening: Boolean) {
            val evaluated = evaluator.evaluate(puppet, pose.mapKeys { ParameterId(it.key) })
            assertEquals(if (opening) 1f else 0f, evaluated.opacity.getValue(openId), .0001f, "Open drawing at $pose")
            assertEquals(if (opening) 0f else 1f, evaluated.opacity.getValue(closedId), .0001f, "Closed drawing at $pose")
        }
        swap(emptyMap(), false)
        swap(mapOf("ParamMouthOpenY" to 1f), true)
        val parameters = puppet.parameters.mapTo(hashSetOf()) { it.id.raw }
        if (requireAddons) assertTrue("ParamA" in parameters && "ParamMouthFunnel" in parameters)
        if ("ParamA" in parameters) {
            for (vowel in listOf("ParamA", "ParamI", "ParamU", "ParamE", "ParamO")) swap(mapOf(vowel to 1f), true)
        }
        if ("ParamMouthFunnel" in parameters) {
            swap(mapOf("ParamMouthFunnel" to 1f), true)
            swap(mapOf("ParamMouthPressLipOpen" to 1f), true)
            swap(mapOf("ParamMouthX" to 1f), false)
        }
    }
}
