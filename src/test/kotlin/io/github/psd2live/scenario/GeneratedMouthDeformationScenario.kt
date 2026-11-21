package io.github.psd2live.scenario

import io.github.psd2live.application.*
import io.github.psd2live.core.*
import io.github.psd2live.ui.keyformAxesFor
import io.github.psd2live.ui.parameterKeyMarks
import io.github.psd2live.core.modifier.RigModifierStack
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.jupiter.api.io.TempDir
import org.umamo.render.eval.CpuDeformationEvaluator
import org.umamo.runtime.model.*
import java.nio.file.Path
import kotlin.math.abs
import kotlin.test.*

class GeneratedMouthDeformationScenario {
    companion object {
        private val preparedDocument by lazy {
            val (source, choices) = WorkspacePresetInitialize.prepare(Characters.closedMouthHead(), WorkspaceInitialModifiers.RECOMMENDED)
            WorkspacePresetInitialization.prepare(source, WorkspaceRasterWork.Direct, requireNotNull(choices),
                requestedVersions = mapOf("mouth/fitted" to 1)).first
        }
    }

    @TempDir lateinit var temp: Path
    private val open = ParameterId("ParamMouthOpenY")
    private val stops = listOf(0f, .05f, .25f, .5f, 1f)

    private fun document(): io.github.psd2live.project.WorkspaceDocument = preparedDocument

    @Test fun geometryKeysDoNotSnapToOpacityKeys(): Unit = runBlocking {
        val model = WorkspacePreviewBuilder().build(document())
        val puppet = model.rig.puppet
        val id = model.rig.ownLayerIdByDrawableId.entries.first { it.value == "mouth" }.key
        val defaults = puppet.parameters.associate { it.id to it.default }
        for (value in stops + Math.nextUp(.25f)) {
            assertTrue(nearestKeyPose(puppet.keyformAxesFor("mesh", id), mapOf(open to value), defaults).isEmpty(),
                "An existing geometry key must not snap because an opacity track has different keys: $value")
        }
        val marked = ParameterKeyEdits.apply(puppet, req("op" to "parameter_keys", "parameter" to open.raw,
            "action" to "add", "values" to listOf(.4f)))
        assertTrue(nearestKeyPose(marked.keyformAxesFor("mesh", id), mapOf(open to .4f), defaults).isEmpty())
        val form = ParameterId("ParamMouthForm")
        val axes = puppet.keyformAxesFor("mesh", id)
        val snapped = nearestKeyPose(axes, mapOf(open to .3f, form to .35f), defaults)
        assertEquals(mapOf(open to .25f, form to .5f), snapped)
        assertTrue(nearestKeyPose(axes, snapped, defaults).isEmpty())
    }

    @Test fun savedFedoraSourceAxesSculptReplaysAndReopensWithoutMigration(): Unit = runBlocking {
        val document = document()
        val model = WorkspacePreviewBuilder().build(document)
        val original = model.rig.puppet
        val id = model.rig.ownLayerIdByDrawableId.entries.first { it.value == "closed-mouth" }.key
        val axes = requireNotNull(GeometryKeyforms.axes(original, "mesh", id))
        assertTrue(axes.none { it.parameterId == open }, "Exercise the old first-Open-axis record")
        val key = axes.associate { it.parameterId.raw to original.parameters.single { p -> p.id == it.parameterId }.default } + (open.raw to .25f)
        val prepared = GeneratedOverrides.withGeometryKeys(original, "mesh", id, key)
        val grid = prepared.drawables.single { it.id.raw == id }.geometryGrid!!
        val form = grid.cells.single { cell -> grid.axes.indices.all { grid.axes[it].keys[cell.coordinate[it]] == key.getValue(grid.axes[it].parameterId.raw) } }.form
        val points = form.positionDeltas.copyOf().also { it[0] += .02f }
        val owner = model.modifierEvaluation!!.entries.last { entry -> entry.ownedKeyforms.any { it.id == id && it.channel == null } }.instance.id
        val legacy = req("op" to "generated_override", "generator" to "modifier:$owner", "target" to "mesh:$id",
            "key" to key, "source_axes" to axes.map { it.parameterId.raw }, "base" to form.positionDeltas, "points" to points)
        val stack = RigModifierStack.fromJson(document.rigEdits.modifierStack!!)
        val saved = document.copy(rigEdits = document.rigEdits.copy(modifierStack = stack.copy(overrides =
            stack.overrides + (owner to (stack.overrides[owner].orEmpty() + legacy))).toJson()))
        Studio.run(temp, saved) {
            fun verify() {
                val edited = puppet.drawables.single { it.id.raw == id }.geometryGrid!!
                val cell = edited.cells.single { cell -> edited.axes.indices.all { edited.axes[it].keys[cell.coordinate[it]] == key.getValue(edited.axes[it].parameterId.raw) } }
                assertContentEquals(points, cell.form.positionDeltas)
                val persisted = RigModifierStack.fromJson(now.document.rigEdits.modifierStack!!)
                assertEquals(listOf(legacy), persisted.overrides.getValue(owner), "Loading must not rewrite saved edit records")
                assertTrue(now.model.modifierEvaluation!!.entries.flatMap { it.overrideIssues }.isEmpty())
            }
            verify()
            reopen()
            verify()
        }
    }

    @Test fun sculptedOpenAndClosedVerticesPersistAtEveryDefaultAndNewKey() = Studio.run(temp, document()) {
        val evaluator = CpuDeformationEvaluator()
        val ids = listOf("mouth", "closed-mouth").map { layer ->
            now.model.rig.ownLayerIdByDrawableId.entries.first { it.value == layer }.key
        }
        val original = now
        fun local(model: PuppetModel, id: String, value: Float) =
            RigGeometryTools.geometry(model, "mesh", id, mapOf(open.raw to value)).points
        fun close(expected: FloatArray, actual: FloatArray, label: String, tolerance: Float = .0001f) {
            assertEquals(expected.size, actual.size, label)
            assertTrue(expected.indices.all { abs(expected[it] - actual[it]) <= tolerance }, label)
        }
        // Exercise endpoints, an interior stop, an added key, and a second edit on an already-sculpted stop.
        val sampleStops = listOf(0f, .25f, .4f, 1f, .25f)
        for (id in ids) {
            for (value in sampleStops) {
                if (value == .4f && puppet.parameters.single { it.id == open }.keys?.contains(.4f) != true) {
                    edit("add independent mouth key", "keyform_apply", req("changes" to listOf(mapOf("op" to "parameter_keys",
                        "parameter" to open.raw, "action" to "add", "values" to listOf(.4f)))))
                }
                val before = puppet
                val defaults = before.parameters.associate { it.id.raw to it.default }
                val drawable = before.drawables.single { it.id.raw == id }
                val key = drawable.geometryGrid?.axes.orEmpty().associate { it.parameterId.raw to defaults.getValue(it.parameterId.raw) } + (open.raw to value)
                val world = evaluator.evaluate(before, key.mapKeys { ParameterId(it.key) }).worldPositions.getValue(DrawableId(id))
                val start = listOf(world[0], -world[1])
                val activeStops = if (before.parameters.single { it.id == open }.keys?.contains(.4f) == true) stops + .4f else stops
                val untouched = activeStops.distinct().filter { it != value }.associateWith { local(before, id, it) }
                edit("sculpt $id vertex at $value", "canvas_deform_stroke", req("correction_mode" to "absolute", "stroke" to mapOf(
                    "mode" to "deform", "action" to "brush", "pose" to key,
                    "targets" to listOf(mapOf("target" to "mesh:$id", "key" to key, "vertices" to listOf(0))),
                    "radius" to 8, "strength" to 1,
                    "samples" to listOf(mapOf("point" to start), mapOf("point" to listOf(start[0] + .2f, start[1]))))))
                val afterWorld = evaluator.evaluate(puppet, key.mapKeys { ParameterId(it.key) }).worldPositions.getValue(DrawableId(id))
                assertEquals(world[0] + .2f, afterWorld[0], .01f, "$id at $value must keep the actual vertex movement")
                assertEquals(world[1], afterWorld[1], .01f)
                close(world.copyOfRange(2, world.size), afterWorld.copyOfRange(2, afterWorld.size), "Only the selected vertex moves", .01f)
                for ((neighbor, positions) in untouched) close(positions, local(puppet, id, neighbor), "$id: editing $value changed key $neighbor")
                // The authored residual interpolates, rather than jumping to a neighboring key.
                val left = activeStops.filter { it < value }.maxOrNull()
                if (left != null) {
                    val middle = (left + value) / 2f
                    val oldMiddle = local(before, id, middle); val newMiddle = local(puppet, id, middle)
                    val oldKey = local(before, id, value); val newKey = local(puppet, id, value)
                    close(FloatArray(oldMiddle.size) { (newKey[it] - oldKey[it]) / 2f },
                        FloatArray(oldMiddle.size) { newMiddle[it] - oldMiddle[it] }, "$id residual interpolation at $middle")
                }
            }
        }
        val sculpted = ids.associateWith { id -> sampleStops.distinct().associateWith { value -> local(puppet, id, value).copyOfRange(0, 2) } }
        val mouth = RigModifierStack.fromJson(requireNotNull(now.document.rigEdits.modifierStack)).instances.single { it.type == "mouth/fitted" }
        allow("GENERATED_OVERRIDE_CONFLICT")
        edit("regenerate the fitted mouth with different opening timing", "modifier_update", req("id" to mouth.id,
            "config" to JsonObject(mouth.config + ("values" to JsonObject(mouth.config["values"]!!.jsonObject + ("early" to JsonPrimitive(45f)))))))
        for ((id, poses) in sculpted) for ((value, vertex) in poses)
            close(vertex, local(puppet, id, value).copyOfRange(0, 2), "Regeneration overwrote $id's sculpt at $value")
        val edited = now
        val expected = RigIrCompiler.compile(edited.model)
        checkout("undo all mouth sculpts", original.historyHead)
        assertEquals(RigIrCompiler.compile(original.model), RigIrCompiler.compile(now.model))
        checkout("redo mouth sculpts", edited.historyHead)
        assertEquals(expected, RigIrCompiler.compile(now.model))
        reopen()
        assertEquals(expected, RigIrCompiler.compile(now.model))
        // Opt-in fixture for cross-version checks: a real project with sculpts at every mouth key.
        System.getenv("PSD2LIVE_MOUTH_EDIT_FIXTURE")?.let { directory ->
            val root = java.nio.file.Files.createDirectories(Path.of(directory))
            java.nio.file.Files.copy(save(), root.resolve("sculpted.psd2live"), java.nio.file.StandardCopyOption.REPLACE_EXISTING)
            java.nio.file.Files.write(root.resolve("sculpted.rig"), io.github.psd2live.format.compile.RigIrBinary.encode(expected))
            val plugins = io.github.psd2live.core.modifier.ModifierPluginStore.shared
            val references = RigModifierStack.fromJson(now.document.rigEdits.modifierStack!!).instances.mapNotNull { it.plugin }.distinct()
            plugins.retainBundledReferences(references)
            for (reference in references) {
                val source = plugins.findRunnable(reference)!!.directory
                java.nio.file.Files.walk(source).use { paths -> paths.forEach { path ->
                    val target = root.resolve("plugins").resolve(reference.digest).resolve(source.relativize(path))
                    if (java.nio.file.Files.isDirectory(path)) java.nio.file.Files.createDirectories(target)
                    else java.nio.file.Files.copy(path, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING)
                } }
            }
        }
    }

    @Test fun sculptedMouthBlendShapePersistsOnUpgradedMesh() = Studio.run(temp, document()) {
        val evaluator = CpuDeformationEvaluator()
        val id = now.model.rig.ownLayerIdByDrawableId.entries.first { it.value == "mouth" }.key
        val blend = puppet.parameters.firstOrNull { it.kind == ParameterKind.BLEND_SHAPE &&
            puppet.drawables.single { d -> d.id.raw == id }.blendShapes.any { b -> b.parameterId == it.id } }
            ?: fail("Expected a generated mouth blend shape on $id")
        val defaults = puppet.parameters.associate { it.id to it.default }
        val allParameterAxes = puppet.parameterKeyMarks().mapNotNull { (paramId, marks) ->
            marks.allKeys.takeIf { it.isNotEmpty() }?.let { KeyformAxis(paramId, it.toFloatArray()) }
        }
        val snappedBlend = nearestKeyPose(allParameterAxes, mapOf(blend.id to .7f, open to .3f), defaults)
        assertEquals(mapOf(blend.id to 1f, open to .25f), snappedBlend)
        assertTrue(nearestKeyPose(allParameterAxes, snappedBlend, defaults).isEmpty())

        val original = now
        val before = puppet
        val drawable = before.drawables.single { it.id.raw == id }
        val key = drawable.geometryGrid?.axes.orEmpty().associate { it.parameterId.raw to defaults.getValue(it.parameterId) } +
            (blend.id.raw to 1f)
        val restBefore = evaluator.evaluate(before, emptyMap()).worldPositions.getValue(DrawableId(id))
        val worldBefore = evaluator.evaluate(before, key.mapKeys { ParameterId(it.key) }).worldPositions.getValue(DrawableId(id))
        val start = listOf(worldBefore[0], -worldBefore[1])
        edit("sculpt mouth blend shape ${blend.id.raw}", "canvas_deform_stroke", req("correction_mode" to "delta", "stroke" to mapOf(
            "mode" to "deform", "action" to "brush", "pose" to key,
            "targets" to listOf(mapOf("target" to "mesh:$id", "key" to key, "vertices" to listOf(0))),
            "radius" to 8, "strength" to 1,
            "samples" to listOf(mapOf("point" to start), mapOf("point" to listOf(start[0] + .2f, start[1]))))))
        val worldAfter = evaluator.evaluate(puppet, key.mapKeys { ParameterId(it.key) }).worldPositions.getValue(DrawableId(id))
        assertEquals(worldBefore[0] + .2f, worldAfter[0], .01f, "Blend shape sculpt must persist on upgraded mouth mesh")
        assertEquals(worldBefore[1], worldAfter[1], .01f)
        val restAfter = evaluator.evaluate(puppet, emptyMap()).worldPositions.getValue(DrawableId(id))
        assertEquals(restBefore[0], restAfter[0], .001f, "Rest pose must remain unchanged")
        val edited = now
        val expected = RigIrCompiler.compile(edited.model)
        checkout("undo blend sculpt", original.historyHead)
        assertEquals(RigIrCompiler.compile(original.model), RigIrCompiler.compile(now.model))
        checkout("redo blend sculpt", edited.historyHead)
        assertEquals(expected, RigIrCompiler.compile(now.model))
        reopen()
        assertEquals(expected, RigIrCompiler.compile(now.model))
    }
}
