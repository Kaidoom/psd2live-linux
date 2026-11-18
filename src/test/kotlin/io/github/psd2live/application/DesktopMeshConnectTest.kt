package io.github.psd2live.application

import io.github.psd2live.core.CanvasTopology
import io.github.psd2live.core.MeshSettings
import io.github.psd2live.core.PipelineConfig
import io.github.psd2live.core.modifier.ModifierAuthorEdits
import io.github.psd2live.core.modifier.ModifierTarget
import io.github.psd2live.core.modifier.RigModifierStack
import io.github.psd2live.i18n.tr
import io.github.psd2live.project.*
import io.github.psd2live.ui.EditHierarchyMode
import io.github.psd2live.ui.state.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.JsonObject
import org.junit.jupiter.api.io.TempDir
import org.umamo.edit.MeshTopology
import org.umamo.format.art.LayerRaster
import java.nio.file.Path
import kotlin.test.*

class DesktopMeshConnectTest {
    @TempDir lateinit var temp: Path

    @Test fun meshTracingLayersRoundTripAndOlderDocumentSettingsClearThem() {
        val marked = PSD2LiveState(visiblePixelMeshLayers = setOf("right-eye", "left-eye"))
        val saved = WorkspaceStateCodec.encode(marked)
        assertEquals(marked.visiblePixelMeshLayers, WorkspaceStateCodec.decode(saved).visiblePixelMeshLayers)
        assertEquals(marked.visiblePixelMeshLayers, WorkspaceSettingsCodec.decode(WorkspaceStateCodec.settings(marked)).visiblePixelMeshLayers)
        val old = WorkspaceSettingsCodec.encode(PipelineConfig())
        assertFalse("visiblePixelMeshLayers" in old)
        assertTrue(WorkspaceStateCodec.decode(old, marked).visiblePixelMeshLayers.isEmpty())
    }

    @Test fun joinedEdgeAndOutlineSurviveDesktopPreviewRebuildAndReopen(): Unit = runBlocking {
        val project = SyntheticProject(temp.resolve("fixture"), 1, 64, 1)
        val root = project.tree.head().snapshot
        val layer = root.source.layers.single() as WorkspaceSourceLayer
        val pixels = ByteArray(64 * 64 * 4)
        for (y in 16..39) for (x in 4..55) {
            if (x in 4..23 || x in 36..55) pixels[(y * 64 + x) * 4 + 3] = -1
        }
        val artwork = WorkspaceSourceArt(64, 64, listOf(layer.copy(raster = LayerRaster(64, 64, pixels))), emptyList())
        val settings = MeshSettings()
        val config = PipelineConfig(atlasSize = 128, generateDeformers = false, generatePhysics = false,
            visiblePixelMeshLayers = setOf(layer.id.raw))
        val base = root.copy(source = artwork, settings = WorkspaceSettingsCodec.encode(config),
            meshOverrides = mapOf(layer.id.raw to settings))
        val meshId = WorkspacePreviewBuilder().build(base).rig.puppet.drawables.single().id
        val stack = ModifierAuthorEdits.append(RigModifierStack(), listOf(ModifierTarget("mesh", meshId.raw)))
        val doc = base.copy(rigEdits = base.rigEdits.copy(modifierStack = stack.toJson()))
        val revision = WorkspaceRevisions.of(doc)
        project.tree.commit(project.tree.head().node.id, doc, revision, revision, "Two islands", "user")
        project.store.persistHistory(SyntheticProject.PROJECT, project.tree.state())
        // Older desktops wrote the layer setting into the document, but omitted it from the presentation.
        val input = temp.resolve("two-islands.psd2live")
        val oldPresentation = JsonObject(doc.settings - "visiblePixelMeshLayers")
        ProjectRepository(writeHeadCache = false).save(project.capture().copy(presentation = oldPresentation), input)

        PSD2LiveViewModel().use { vm -> DesktopWorkspace(vm, temp.resolve("workspace")).use { workspace ->
            vm.attachWorkspace(workspace)
            workspace.open(input)
            val editor = vm.canvasEditor
            val before = vm.state.value.previewModel!!.rig.puppet.drawables.single().mesh!!
            val neighbors = MeshTopology.buildVertexAdjacency(before.vertexCount, before.indices)
            val islands = MeshTopology.selectionIslands(neighbors, neighbors.indices.toSet()).toList()
            assertEquals(2, islands.size)
            fun distanceSquared(a: Int, b: Int): Float {
                val dx = before.positions[2 * a] - before.positions[2 * b]
                val dy = before.positions[2 * a + 1] - before.positions[2 * b + 1]
                return dx * dx + dy * dy
            }
            val (a, b) = islands[0].flatMap { a -> islands[1].map { b -> a to b } }
                .minBy { (a, b) -> distanceSquared(a, b) }
            val pair = setOf(a, b)
            val joined = assertNotNull(CanvasTopology.build(before, "connect", pair)).edit.newMesh.indices
            fun rim(indices: IntArray) = MeshTopology.edgeUses(indices).filterValues { it.size == 1 }.keys
            assertTrue((rim(before.indices) - rim(joined)).isNotEmpty())
            assertTrue((rim(joined) - rim(before.indices)).isNotEmpty())

            withContext(Dispatchers.Main) {
                editor.selectLayer(layer.id.raw)
                editor.setHierarchyMode(EditHierarchyMode.EDIT)
                editor.topology("connect", picked = pair)
                assertContentEquals(joined, editor.editMeshTargets().single().indices)
            }
            withTimeout(20_000) { vm.state.first { !it.canvasEditBusy && !it.workspaceEditBusy } }
            // Ask for a real background rebuild with identical settings. This goes through the same
            // draft projection that used to publish the original outline over a successful Connect.
            val shown = vm.state.value.previewModel
            withContext(Dispatchers.Main) { vm.previewPartMeshSettings(layer.id.raw, settings) }
            withTimeout(20_000) { vm.state.first { it.previewModel !== shown && it.statusText == tr("status.layerChangesApplied") } }
            withContext(Dispatchers.Main) {
                assertNull(editor.error)
                val displayed = editor.editMeshTargets().single().indices
                assertContentEquals(joined, displayed)
                assertEquals(rim(joined), rim(displayed))
                assertEquals(config.visiblePixelMeshLayers, vm.state.value.previewModel!!.config.visiblePixelMeshLayers)
                vm.cancelPartMeshSettingsPreview(layer.id.raw)
            }
            val saved = temp.resolve("joined.psd2live")
            vm.saveProjectNow(saved)
            workspace.open(saved)
            val reopened = vm.state.value.previewModel!!
            assertContentEquals(joined, reopened.rig.puppet.drawables.single().mesh!!.indices)
            assertEquals(config.visiblePixelMeshLayers, reopened.config.visiblePixelMeshLayers)
        } }
    }
}
