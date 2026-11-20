package io.github.psd2live.scenario

import io.github.psd2live.application.*
import io.github.psd2live.project.MutationAuthor
import io.github.psd2live.ui.state.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.umamo.format.art.LayerRaster
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.test.*

class MaterialReimportControllerTest {
    @TempDir lateinit var temp: Path

    @Test fun guidedProjectKeepsSourceMaterialsAndIndependentSplitParts(): Unit = runBlocking {
        PSD2LiveViewModel().use { vm -> DesktopWorkspace(vm, temp.resolve("guide-store")).use { workspace ->
            vm.attachWorkspace(workspace)
            val psd = Characters.eyesPsd(temp)
            val guide = workspace.host.preparePsdGuide(psd)
            val split = guide.defaultSplits(guide.base).single()
            val candidate = guide.split(guide.base, listOf(split))
            workspace.host.installPsdGuide(guide, candidate, discardUnsaved = true)
            val before = assertNotNull(workspace.layoutState())
            val library = before.layout.materials
            val entries = WorkspaceMaterialFiles.read(listOf(psd))
            val folder = library.folders.single { it.origin == psd.fileName.toString() }
            assertEquals(psd.toRealPath().toString(), folder.sourcePath)
            assertEquals(folder.id, WorkspaceMaterialReimport.targets(library, entries, null).automatic)
            val children = before.layers.filter { it.name in split.names }
            assertEquals(split.names.size, children.size)
            val original = before.layout.layerMaterials.getValue(split.id)
            val materials = children.map { before.layout.layerMaterials.getValue(it.id.raw) }
            assertEquals(children.size, materials.distinct().size)
            assertTrue(original !in materials)

            suspend fun reimport(updates: List<WorkspaceMaterialFiles.Entry>) {
                workspace.applyDocumentEdits(workspace.stateToken()!!, "Reimport PSD", listOf(WorkspaceDocumentOperation(
                    WorkspaceMaterialReimport.OP, req("folder_id" to folder.id, "staging" to WorkspaceMaterialStaging.stage(updates))
                )), MutationAuthor.USER)
            }
            val head = workspace.snapshot().historyHeadNodeId
            reimport(entries)
            assertEquals(head, workspace.snapshot().historyHeadNodeId)
            val changed = entries.map { entry ->
                val rgba = entry.raster.rgba.copyOf()
                for (pixel in rgba.indices step 4) if (rgba[pixel + 3] != 0.toByte()) rgba[pixel] = rgba[pixel].inc()
                entry.copy(raster = LayerRaster(entry.raster.width, entry.raster.height, rgba))
            }
            reimport(changed)
            val after = assertNotNull(workspace.layoutState())
            assertEquals(library.items.map { it.id }, after.layout.materials.items.map { it.id })
            val top = after.layers.single { it.name == "top" }
            assertContentEquals(changed.single { it.name == "top" }.raster.rgba, top.raster.rgba)
            assertContentEquals(changed.single { it.name == library.item(original)!!.name }.raster.rgba,
                after.layout.materials.item(original)!!.raster.rgba)
            for (part in children) assertContentEquals(part.raster.rgba, after.layers.single { it.id == part.id }.raster.rgba)
            val updatedHead = workspace.snapshot().historyHeadNodeId
            reimport(changed)
            assertEquals(updatedHead, workspace.snapshot().historyHeadNodeId)
        } }
    }

    @Test fun dropsUpdateTheExistingFolderAndCancellingAnAmbiguousTargetCommitsNothing() = runBlocking {
        PSD2LiveViewModel().use { vm ->
            vm.setStateForTest(vm.state.value.copy(atlasSize = 512, exportMoc3 = false, generatePhysics = false))
            DesktopWorkspace(vm, temp.resolve("store")).use { workspace ->
                vm.attachWorkspace(workspace)
                val psd = Characters.headPsd(temp)
                workspace.importPsd(psd.toString(), true)
                vm.showWorkspace()
                assertIs<FileDropIntent.ImportMaterials>(vm.fileDrops.resolve(CarriedFiles(listOf(psd.toFile())), 0f, 0f).first)
                var job: Job? = null
                val controller = MaterialController({ vm.state.value }, { workspace }, { false },
                    { finished, action -> job = launch { try { action(workspace.stateToken()!!) } finally { finished() } } },
                    {}, {}, {}, {}, FileDropRouter({ null }, { null }), { _, _ -> })
                controller.use {
                    val count = workspace.layoutState()!!.layout.materials.items.size
                    repeat(2) {
                        controller.importMaterials(listOf(psd), null)
                        withTimeout(20_000) { job!!.join() }
                        assertNull(controller.reimportPrompt.value)
                        assertEquals(count, workspace.layoutState()!!.layout.materials.items.size)
                    }
                    val stable = workspace.stateToken()
                    controller.importMaterials(listOf(psd), null)
                    withTimeout(20_000) { job!!.join() }
                    assertEquals(stable, workspace.stateToken())

                    val library = workspace.layoutState()!!.layout.materials
                    val copy = WorkspaceMaterialReimport.separate(library, WorkspaceMaterialFiles.read(listOf(psd)), null)
                    workspace.applyDocumentEdits(workspace.stateToken()!!, "separate source copy", listOf(
                        WorkspaceDocumentOperation(WorkspaceMaterialEdits.ADD, req("staging" to WorkspaceMaterialStaging.stage(copy)))), MutationAuthor.USER)
                    val before = workspace.stateToken()
                    controller.importMaterials(listOf(psd), null)
                    val prompt = withTimeout(20_000) { controller.reimportPrompt.first { it != null } }
                    assertEquals(2, assertIs<MaterialReimportPrompt.Target>(prompt).folders.size)
                    controller.resolveReimport(MaterialReimportDecision.Cancel)
                    withTimeout(20_000) { job!!.join() }
                    assertEquals(before, workspace.stateToken())
                    assertNull(controller.reimportPrompt.value)

                    // Choosing the existing folder uses the same reimport operation as its context menu.
                    controller.importMaterials(listOf(psd), null)
                    val again = withTimeout(20_000) { controller.reimportPrompt.first { it != null } }
                    controller.resolveReimport(MaterialReimportDecision.Target(assertIs<MaterialReimportPrompt.Target>(again).folders.first().id))
                    withTimeout(20_000) { job!!.join() }
                    assertEquals(count * 2, workspace.layoutState()!!.layout.materials.items.size)
                    assertNull(controller.reimportPrompt.value)
                }
            }
        }
    }
}
