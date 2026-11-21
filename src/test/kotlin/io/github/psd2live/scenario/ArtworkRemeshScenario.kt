package io.github.psd2live.scenario

import io.github.psd2live.application.*
import io.github.psd2live.bridge.PuppetIr
import kotlinx.serialization.json.*
import io.github.psd2live.core.*
import io.github.psd2live.core.modifier.RigModifierStack
import io.github.psd2live.project.*
import org.junit.jupiter.api.io.TempDir
import org.umamo.format.art.*
import org.umamo.format.psd.PsdWriter
import java.nio.file.Files
import java.nio.file.Path
import kotlin.math.hypot
import kotlin.test.*

class ArtworkRemeshScenario {
    @TempDir lateinit var temp: Path

    /** A soft iris grows and moves in its PSD. The other eye and its rig provide an independent reference. */
    private fun writePsd(updated: Boolean, reveal: Boolean = false): Path {
        val source = Characters.eyed().source
        val layers = source.layers.mapIndexed { index, layer ->
            val name = layer.name.replace("iris_", "irides-").replace("white_", "eyewhite-").replace("lash_", "eyelash-")
            val iris = name == "irides-l"
            val w = if (updated) 44 else 20
            val h = if (updated) 36 else 24
            val bounds = if (iris) LayerBounds(if (updated) 207 else 215, 50, w, h) else layer.bounds
            val art = if (!iris) layer.raster else LayerRaster(w, h, ByteArray(w * h * 4).also { rgba ->
                for (y in 0 until h) for (x in 0 until w) {
                    val radius = hypot((x + .5 - w / 2) / (w * .44), (y + .5 - h / 2) / (h * .44))
                    val o = (y * w + x) * 4
                    rgba[o] = (x * 3).toByte()
                    rgba[o + 3] = if (reveal && x < w / 2 && y in 0..h / 2) 255.toByte() else ((1.0 - radius) * 1500).toInt().coerceIn(0, 255).toByte()
                }
            })
            object : SourceLayer by layer {
                override val id = LayerId("lyid:${index + 101}")
                override val name = name
                override val bounds = bounds
                override val raster = art
            }
        }
        return temp.resolve("eyes.psd").also { Files.write(it, PsdWriter.write(source.widthPx, source.heightPx, layers, source.groups)) }
    }

    @Test fun legacyRigKeepsItsEyeBindingAfterResizingTheSourceArt() = Studio.run(temp, checks = emptySet()) {
        val path = writePsd(false)
        importPsd(path, PipelineConfig(atlasSize = 512, generatePhysics = false))
        val layer = now.document.source.layers.single { it.name == "irides-l" }
        val id = mesh(layer.id.raw)
        val parent = puppet.drawables.single { it.id.raw == id }.parentDeformerId
        val key = puppet.drawables.single { it.id.raw == id }.geometryGrid?.axes.orEmpty().associate {
            it.parameterId.raw to puppet.parameters.single { parameter -> parameter.id == it.parameterId }.default
        } + ("ParamIrisSculpt" to 1f)
        edits("author an independent iris pose",
            "parameter_create" to req("parameter_id" to "ParamIrisSculpt", "name" to "Iris sculpt", "min" to 0, "max" to 1, "kind" to "blend_shape"),
            "rig_deform" to req("changes" to listOf(mapOf("target" to "mesh:$id", "key" to key,
                "operations" to listOf(mapOf("type" to "scale", "factors" to listOf(1.1, 1.1)))))))
        assertTrue(motion(id, "ParamIrisSculpt", 1f) > .01)
        val item = now.document.layout.layerMaterials.getValue(layer.id.raw)
        val folder = now.document.layout.materials.folders.single { it.origin == path.fileName.toString() }.id
        val incoming = WorkspaceMaterialFiles.read(listOf(writePsd(true))).single { it.name == layer.name }
        edit("adopt resized iris", WorkspaceMaterialReimport.OP, req("folder_id" to folder, "staging" to WorkspaceMaterialStaging.stage(listOf(incoming)),
            "choices" to mapOf("0" to item), "adapt_changed_bounds" to true))
        reopen()
        assertEquals(incoming.placement, now.document.source.layers.single { it.id == layer.id }.canvasRect())
        assertEquals(parent, puppet.drawables.single { it.id.raw == id }.parentDeformerId)
        assertEquals(0f, MeshCoverage.uncovered(now.model, layer.id.raw))
        assertTrue(motion(id, "ParamIrisSculpt", 1f) > .01)
    }

    @Test fun revealInsideOriginalBoundsPreservesAuthoredPoseThroughUndoAndReopen() = Studio.run(temp, checks = emptySet()) {
        val path = writePsd(false)
        importPsd(path, PipelineConfig(atlasSize = 512, generatePhysics = false))
        val layer = now.document.source.layers.single { it.name == "irides-l" }
        val sourceId = layer.id.raw
        val id = mesh(sourceId)
        fun geometry() = puppet.drawables.single { it.id.raw == id }.mesh!!
        val axes = puppet.drawables.single { it.id.raw == id }.geometryGrid!!.axes.associate { axis ->
            axis.parameterId.raw to puppet.parameters.single { it.id == axis.parameterId }.default
        }
        edits("sculpt iris", "parameter_create" to req("parameter_id" to "ParamCustom", "name" to "Pose",
            "min" to 0, "max" to 1, "kind" to "blend_shape"),
            "rig_deform" to req("changes" to listOf(mapOf("target" to "mesh:$id", "key" to axes + ("ParamCustom" to 1),
                "operations" to listOf(mapOf("type" to "scale", "factors" to listOf(1.1, 1.1)))))))
        val original = geometry()
        val folder = now.document.layout.materials.folders.single { it.origin == path.fileName.toString() }
        fun reimport(entry: WorkspaceMaterialFiles.Entry) = edit("reimport iris", WorkspaceMaterialReimport.OP,
            req("folder_id" to folder.id, "staging" to WorkspaceMaterialStaging.stage(listOf(entry))))
        val originalEntry = WorkspaceMaterialFiles.read(listOf(path)).single { it.name == layer.name }
        val expanded = WorkspaceMaterialFiles.read(listOf(writePsd(false, reveal = true))).single { it.name == layer.name }
        assertEquals(originalEntry.placement, expanded.placement)
        reimport(expanded)
        val grown = geometry()
        assertFalse(original.positions.contentEquals(grown.positions))
        assertEquals(0f, MeshCoverage.uncovered(now.model, sourceId))
        assertContentEquals(expanded.raster.rgba, now.document.source.layers.single { it.id == layer.id }.raster.rgba)
        assertTrue(motion(id, "ParamCustom", 1f) > .01)
        undo()
        assertContentEquals(original.positions, geometry().positions)
        redo()
        reopen()
        assertContentEquals(grown.positions, geometry().positions)
        assertTrue(motion(id, "ParamCustom", 1f) > .01)
        val stable = now.historyHead
        reimport(expanded)
        assertEquals(stable, now.historyHead)
        reimport(originalEntry)
        assertContentEquals(originalEntry.raster.rgba, now.document.source.layers.single { it.id == layer.id }.raster.rgba)
        assertContentEquals(grown.positions, geometry().positions, "Hiding pixels should retain the edited mesh")
    }

    @Test fun revealUpdatesBothInstancesOfASharedMaterialInOneEdit() = Studio.run(temp, checks = emptySet()) {
        val path = writePsd(false)
        importPsd(path, PipelineConfig(atlasSize = 512, generatePhysics = false))
        val left = now.document.source.layers.single { it.name == "irides-l" }
        val right = now.document.source.layers.single { it.name == "irides-r" }
        val item = now.document.layout.layerMaterials.getValue(left.id.raw)
        val folder = now.document.layout.materials.folders.single { it.origin == path.fileName.toString() }.id
        edit("share iris", "layer_replace_image", req("layer_id" to right.id.raw, "material_id" to item))
        val before = now
        val updated = WorkspaceMaterialFiles.read(listOf(writePsd(false, reveal = true))).single { it.name == left.name }
        edit("reimport shared material", WorkspaceMaterialReimport.OP,
            req("folder_id" to folder, "staging" to WorkspaceMaterialStaging.stage(listOf(updated))))
        for (id in listOf(left.id, right.id)) {
            val previous = before.document.source.layers.single { it.id == id }
            val current = now.document.source.layers.single { it.id == id }
            assertEquals(item, now.document.layout.layerMaterials.getValue(id.raw))
            assertEquals(previous.canvasRect(), current.canvasRect())
            assertContentEquals(updated.raster.rgba, current.raster.rgba)
            assertFalse(MeshCoverage.newlyUncovered(now.model, previous, current.raster, now.model.config.alphaThreshold) {})
        }
        undo()
        assertEquals(before.document.layout.layerMaterials, now.document.layout.layerMaterials)
    }

    @Test fun changedPlacementAndSoftPixelsSurviveReimportReplacementAndColdRebuild() = Studio.run(temp, checks = emptySet()) {
        val path = writePsd(false)
        step("import a modern rig") {
            WorkspaceSourceImporter(runtime).importPsd(path, null, runtime.state.value.state, modifiers = WorkspaceInitialModifiers.RECOMMENDED)
        }
        val layer = now.document.source.layers.single { it.name == "irides-l" }
        val sibling = mesh(now.document.source.layers.single { it.name == "irides-r" }.id.raw)
        val id = mesh(layer.id.raw)
        val parent = puppet.drawables.single { it.id.raw == id }.parentDeformerId
        assertTrue(WorkspaceGenerationEdits.hasLiveSourceMesh(now.model, layer.id.raw))
        // A correction on a non-neutral key. Give all vertices the same offset so its
        // expected value is independent of the old and new triangle layouts.
        val snapshot = now.model.modifierEvaluation!!
        val index = snapshot.entries.indexOfLast { it.ownedKeyforms.any { k -> k.id == id && k.channel == null } }
        val owner = snapshot.entries[index].instance.id
        val stage = PuppetIr.toPuppet(snapshot.outputs[index])
        val drawable = stage.drawables.single { it.id.raw == id }
        val grid = drawable.geometryGrid!!
        val cell = grid.cells.first()
        val pose = grid.axes.mapIndexed { axis, value -> value.parameterId.raw to value.keys[cell.coordinate[axis]] }.toMap()
        val base = cell.form.positionDeltas
        val correction = req("op" to GeneratedOverrides.OP, "generator" to "modifier:$owner", "target" to "mesh:$id",
            "key" to pose, "base" to base, "points" to base.mapIndexed { i, value -> if (i % 2 == 0) value + .025f else value },
            "geometry_binding" to GeneratedOverrides.geometryBinding(stage, "mesh", id), "merge_mode" to "delta")
        val initial = now.document
        val stack = snapshot.stack.copy(overrides = snapshot.stack.overrides + (owner to listOf(correction)))
        fun loadFixture(doc: WorkspaceDocument) = step("load fixture") {
            runtime.install(now.state, now.projectId, doc, builder.build(doc), discardUnsaved = true)
        }
        loadFixture(initial.copy(rigEdits = initial.rigEdits.copy(modifierStack = stack.toJson())))
        val original = now
        val folder = original.document.layout.materials.folders.single { it.origin == "eyes.psd" }.id
        val material = original.document.layout.layerMaterials.getValue(layer.id.raw)
        val new = WorkspaceMaterialFiles.read(listOf(writePsd(true))).single { it.name == layer.name }
        fun expectUpdated() {
            val placed = now.document.source.layers.single { it.id == layer.id }
            assertEquals(new.placement, placed.canvasRect())
            assertContentEquals(new.raster.rgba, placed.raster.rgba)
            assertEquals(0f, MeshCoverage.uncovered(now.model, layer.id.raw))
            assertEquals(parent, puppet.drawables.single { it.id.raw == id }.parentDeformerId)
            assertEquals(original.model.rig.puppet.drawables.single { it.id.raw == sibling }.mesh!!.vertexCount,
                puppet.drawables.single { it.id.raw == sibling }.mesh!!.vertexCount)
        }
        val request = req("folder_id" to folder, "staging" to WorkspaceMaterialStaging.stage(listOf(new)),
            "choices" to mapOf("0" to material), "adapt_changed_bounds" to true)
        edit("adapt PSD layers", WorkspaceMaterialReimport.OP, request)
        expectUpdated()
        val updated = now.model.modifierEvaluation!!
        val saved = updated.stack.overrides.getValue(owner).single()
        val migrated = saved.getValue("points").jsonArray
        val migratedBase = saved.getValue("base").jsonArray
        assertNotEquals(base.size, migrated.size)
        val cleanStack = updated.stack.copy(overrides = updated.stack.overrides - owner)
        val cleanDocument = now.document.copy(rigEdits = now.document.rigEdits.copy(modifierStack = cleanStack.toJson()))
        val clean = step("evaluate the regenerated pose without its sculpt") { builder.build(cleanDocument) }.modifierEvaluation!!
        fun editedPose() {
            val output = PuppetIr.toPuppet(now.model.modifierEvaluation!!.outputs[index])
            val plain = PuppetIr.toPuppet(clean.outputs[index])
            val points = RigGeometryTools.geometry(output, "mesh", id, pose).points
            val expected = RigGeometryTools.geometry(plain, "mesh", id, pose).points
            for (i in points.indices) {
                val offset = if (i % 2 == 0) .025f else 0f
                assertEquals(offset, migrated[i].jsonPrimitive.float - migratedBase[i].jsonPrimitive.float, .00001f)
                assertEquals(offset, points[i] - expected[i], .00001f)
            }
        }
        editedPose()
        val adapted = puppet.drawables.single { it.id.raw == id }.mesh!!
        edit("rebuild from pixels", WorkspaceRasterEdits.REBUILD_MESH, req("layer_id" to layer.id.raw))
        assertContentEquals(adapted.positions, puppet.drawables.single { it.id.raw == id }.mesh!!.positions)
        reopen()
        expectUpdated()
        editedPose()

        loadFixture(initial.copy(rigEdits = initial.rigEdits.copy(modifierStack = stack.toJson())))
        val unmasked = WorkspaceMaterialFiles.read(listOf(writePsd(false, reveal = true))).single { it.name == layer.name }
        edit("unmask the iris inside its original rectangle", WorkspaceMaterialReimport.OP, req("folder_id" to folder,
            "staging" to WorkspaceMaterialStaging.stage(listOf(unmasked))))
        assertEquals(0f, MeshCoverage.uncovered(now.model, layer.id.raw))
        val unmaskedOverride = RigModifierStack.fromJson(now.document.rigEdits.modifierStack!!).overrides.getValue(owner).single()
        assertNotEquals(correction["geometry_binding"], unmaskedOverride["geometry_binding"])
        val points = unmaskedOverride.getValue("points").jsonArray
        val basePoints = unmaskedOverride.getValue("base").jsonArray
        for (i in points.indices) assertEquals(if (i % 2 == 0) .025f else 0f,
            points[i].jsonPrimitive.float - basePoints[i].jsonPrimitive.float, .00001f)

        // The material menu uses the same placement even if the library item already has the new pixels.
        loadFixture(initial)
        edit("fit the changed material into the old rectangle", WorkspaceMaterialReimport.OP,
            req("folder_id" to folder, "staging" to WorkspaceMaterialStaging.stage(listOf(new)), "choices" to mapOf("0" to material),
                "fit_changed_bounds" to true))
        assertEquals(layer.canvasRect(), now.document.source.layers.single { it.id == layer.id }.canvasRect())
        edit("replace with material at artwork size", "layer_replace_image", req("layer_id" to layer.id.raw, "material_id" to material, "fit" to "artwork", "rebuild_mesh" to true))
        expectUpdated()
        undo()
        assertEquals(layer.canvasRect(), now.document.source.layers.single { it.id == layer.id }.canvasRect())
        redo()
        expectUpdated()

        // Replacing only the pixels deliberately retains the old rectangle. Both generation
        // options must honour that choice, with a later refresh tracing all the current texels.
        loadFixture(initial)
        edit("stage replacement", WorkspaceMaterialReimport.OP,
            req("folder_id" to folder, "staging" to WorkspaceMaterialStaging.stage(listOf(new)), "choices" to mapOf("0" to material),
                "fit_changed_bounds" to true))
        val kept = puppet.drawables.single { it.id.raw == id }.mesh!!
        edit("keep replacement topology", "rig_generate", req("preset_group" to "none", "refresh_geometry" to emptyList<String>()))
        assertContentEquals(kept.positions, puppet.drawables.single { it.id.raw == id }.mesh!!.positions)
        edit("remesh staged replacement", "rig_generate", req("preset_group" to "none"))
        assertEquals(layer.canvasRect(), now.document.source.layers.single { it.id == layer.id }.canvasRect())
        assertEquals(0f, MeshCoverage.uncovered(now.model, layer.id.raw))
        assertFalse(layer.id.raw in now.document.layout.generation.refreshLayerIds)
    }
}
