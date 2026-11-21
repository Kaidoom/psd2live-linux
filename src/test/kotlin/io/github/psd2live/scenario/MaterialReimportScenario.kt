package io.github.psd2live.scenario

import io.github.psd2live.application.*
import io.github.psd2live.core.PipelineConfig
import io.github.psd2live.bridge.PuppetIr
import io.github.psd2live.format.compile.document.ContentHash
import io.github.psd2live.project.*
import org.junit.jupiter.api.io.TempDir
import org.umamo.format.art.LayerRaster
import org.umamo.format.psd.PsdReader
import org.umamo.format.psd.PsdWriter
import org.umamo.format.art.LayerId
import org.umamo.format.art.SourceLayer
import org.umamo.runtime.model.PuppetModel
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*

class MaterialReimportScenario {
    @TempDir lateinit var temp: Path

    @Test fun reimportRepairsATruncatedSavedFolderAndKeepsItsMaterialLinks() = Studio.run(temp, checks = emptySet()) {
        val psd = Files.move(Characters.figurePsd(temp), temp.resolve("Figure#2.psd"))
        importPsd(psd, PipelineConfig(atlasSize = 512, exportMoc3 = false))
        val original = now.document
        val library = original.layout.materials
        val folder = library.folders.single { it.origin == psd.fileName.toString() }
        val legacy = library.copy(folders = library.folders.map { if (it.id == folder.id) it.copy(origin = "Figure") else it })
        val document = original.copy(layout = original.layout.copy(materials = legacy))
        step("load old folder metadata") {
            runtime.install(now.state, now.projectId, document, builder.build(document), discardUnsaved = true)
        }
        reopen()
        edit("reimport archived PSD", WorkspaceMaterialReimport.OP, req("folder_id" to folder.id,
            "staging" to WorkspaceMaterialStaging.stage(WorkspaceMaterialFiles.read(listOf(psd)))))
        reopen()
        val repaired = now.document.layout
        assertEquals("Figure#2.psd", repaired.materials.folder(folder.id)!!.origin)
        assertEquals(original.layout.layerMaterials, repaired.layerMaterials)
        assertEquals(library.items.map { it.id }, repaired.materials.items.map { it.id })
    }

    @Test fun artworkUpdatesAllUsersWithoutChangingAuthoredRigAndSurvivesHistoryAndReload() =
        Studio.run(temp, checks = setOf(Studio.Check.UNDO, Studio.Check.REPLAY)) {
            val psd = Characters.figurePsd(temp)
            importPsd(psd, PipelineConfig(atlasSize = 512, generatePhysics = true, exportMoc3 = false))
            val originalEntries = WorkspaceMaterialFiles.read(listOf(psd))
            val folder = now.document.layout.materials.folders.single { it.origin == "figure.psd" }.id
            val item = now.document.layout.materials.items.single { it.name == "front_hair" }
            val hair = now.document.source.layers.single { it.name == "front_hair" }.id.raw
            val sleeve = now.document.source.layers.single { it.name == "sleeve_l" }.id.raw
            edit("share the hair material with another existing canvas object", "layer_replace_image", req("layer_id" to sleeve, "material_id" to item.id))
            val drawable = mesh(hair)
            val axes = puppet.drawables.single { it.id.raw == drawable }.geometryGrid?.axes.orEmpty()
                .associate { axis -> axis.parameterId.raw to puppet.parameters.single { it.id == axis.parameterId }.default }
            edits("author a mesh deformation and animated parameter",
                "parameter_create" to req("parameter_id" to "ParamCustom", "name" to "Custom", "min" to 0, "max" to 1, "kind" to "blend_shape"),
                "rig_deform" to req("changes" to listOf(mapOf("target" to "mesh:$drawable", "key" to axes + ("ParamCustom" to 1),
                    "operations" to listOf(mapOf("type" to "scale", "factors" to listOf(1.1, 1.1)))))),
                "motion_create" to req("id" to "wave", "name" to "Wave"),
                "motion_set_key" to req("id" to "wave", "parameter" to "ParamCustom", "key" to mapOf("time" to 0, "value" to 0)),
                "motion_set_key" to req("id" to "wave", "parameter" to "ParamCustom", "key" to mapOf("time" to 1, "value" to 1)))
            val before = now
            fun apply(entries: List<WorkspaceMaterialFiles.Entry>, choices: Map<String, String> = emptyMap(), fit: Boolean = false) =
                edit("reimport PSD", WorkspaceMaterialReimport.OP, req("folder_id" to folder,
                    "staging" to WorkspaceMaterialStaging.stage(entries), "choices" to choices, "fit_changed_bounds" to fit))

            apply(originalEntries)
            assertEquals(before.document.layout.materials.items.map { it.id }, now.document.layout.materials.items.map { it.id })
            val stableHead = now.historyHead
            apply(originalEntries)
            assertEquals(stableHead, now.historyHead, "An unchanged repeated drop should not add history or duplicate materials")

            // Write and parse a real modified PSD: changed pixels, a new nested layer, one missing old layer.
            val art = PsdReader.read(Files.readAllBytes(psd))
            val hairLayer = art.layers.single { it.id.raw == hair }
            val pixels = hairLayer.raster.rgba.copyOf().also { for (i in it.indices step 4) it[i] = (it[i].toInt() xor 0x7f).toByte() }
            val changedHair = object : SourceLayer by hairLayer {
                override val raster = LayerRaster(hairLayer.raster.width, hairLayer.raster.height, pixels)
            }
            val missing = art.layers.single { it.name == "back_hair" }
            val newLayer = object : SourceLayer by changedHair {
                override val id = LayerId("lyid:20000")
                override val name = "LeftLeg"
                override val groupPath = "Extras/Legs"
            }
            Files.write(psd, PsdWriter.write(art.widthPx, art.heightPx,
                art.layers.filter { it.id != missing.id }.map { if (it.id.raw == hair) changedHair else it } + newLayer, art.groups))
            val changed = WorkspaceMaterialFiles.read(listOf(psd))
            val changedEntry = changed.single { it.name == "front_hair" }
            val invalidEntry = changed.single { it.name == "top" }.let {
                it.copy(raster = LayerRaster(it.raster.width, it.raster.height, byteArrayOf(0)))
            }
            refused("a failure after replacing the first layer rolls back the entire reimport", WorkspaceMaterialReimport.OP,
                req("folder_id" to folder, "staging" to WorkspaceMaterialStaging.stage(listOf(changedEntry, invalidEntry))))
            apply(changed)
            val after = now
            assertEquals(before.document.layout.layerMaterials, after.document.layout.layerMaterials)
            assertEquals(before.document.layout.materials.items.size + 1, after.document.layout.materials.items.size)
            assertNotNull(after.document.layout.materials.items.find { it.name == missing.name })
            val leg = after.document.layout.materials.items.single { it.name == "LeftLeg" }
            assertEquals(listOf("Legs", "Extras"), after.document.layout.materials.ancestry(leg.folderId).take(2).map { it.name })
            for (id in listOf(hair, sleeve)) {
                val layer = after.document.source.layers.single { it.id.raw == id }
                assertContentEquals(pixels, layer.raster.rgba)
                assertEquals(before.document.source.layers.single { it.id.raw == id }.canvasRect(), layer.canvasRect())
            }
            assertEquals(before.document.rigEdits, after.document.rigEdits)
            assertEquals(before.document.meshOverrides, after.document.meshOverrides)
            assertGeometryPreserved(before.model.rig.puppet, puppet)
            val sidecars = before.model.runtimeBundle.assets.filter { it.path.endsWith(".physics3.json") || it.path.endsWith(".motion3.json") }
            assertTrue(sidecars.any { it.path.endsWith(".motion3.json") })
            for (asset in sidecars) assertContentEquals(asset.bytes, after.model.runtimeBundle.assets.single { it.path == asset.path }.bytes,
                "Animation or physics changed: ${asset.path}")
            assertFalse(before.model.atlas.pages.first().png.contentEquals(after.model.atlas.pages.first().png))
            val updatedHead = now.historyHead
            apply(changed)
            assertEquals(updatedHead, now.historyHead)
            reopen()
            assertEquals(after.document.layout.layerMaterials, now.document.layout.layerMaterials)

            val index = changed.indexOfFirst { it.sourceLayerId == hair }
            assertTrue(index >= 0)
            val enlarged = changed.toMutableList().also { list ->
                val old = list[index]
                list[index] = old.copy(raster = LayerRaster(old.raster.width * 2, old.raster.height,
                    ByteArray(old.raster.width * 2 * old.raster.height * 4) { -1 }))
            }
            refused("bounds changes cannot silently alter a used layer", WorkspaceMaterialReimport.OP,
                req("folder_id" to folder, "staging" to WorkspaceMaterialStaging.stage(enlarged), "choices" to mapOf(index.toString() to item.id)))
            val beforeResize = now
            apply(enlarged, mapOf(index.toString() to item.id), fit = true)
            assertGeometryPreserved(beforeResize.model.rig.puppet, puppet)
            assertEquals(beforeResize.document.rigEdits, now.document.rigEdits)

            val beforeCleanup = now
            val unused = WorkspaceMaterialEdits.unusedIds(now.document.layout, folder)
            assertTrue(leg.id in unused)
            edit("delete unused in a mixed folder", WorkspaceMaterialEdits.DELETE_UNUSED, req("folder_id" to folder))
            assertEquals(beforeCleanup.document.layout.materials.items.size - unused.size, now.document.layout.materials.items.size)
            assertEquals(beforeCleanup.document.layout.layerMaterials, now.document.layout.layerMaterials)
            assertNotNull(now.document.layout.materials.folder(folder))
            assertNull(now.document.layout.materials.folder(leg.folderId!!))
            undo(); assertEquals(beforeCleanup.document.layout.materials, now.document.layout.materials)
            redo(); reopen()
            assertEquals(beforeCleanup.document.rigEdits, now.document.rigEdits)
        }

    companion object {
        internal fun assertGeometryPreserved(before: PuppetModel, after: PuppetModel) {
            val old = PuppetIr.toIr(before)
            val updated = PuppetIr.toIr(after)
            assertEquals(old.meshes.map { it.id }, updated.meshes.map { it.id })
            // Texture placement and source content hashes may change; every authored value must stay identical.
            val normalized = updated.copy(textures = old.textures,
                authoring = updated.authoring.copy(sources = old.authoring.sources),
                meshes = updated.meshes.zip(old.meshes).map { (mesh, previous) ->
                    mesh.copy(page = previous.page, textureSource = previous.textureSource, tile = previous.tile,
                        geometry = mesh.geometry?.copy(uvs = previous.geometry!!.uvs))
                })
            assertEquals(ContentHash.of(old), ContentHash.of(normalized), "Geometry, deformation, clipping or rig settings changed")
        }
    }
}
