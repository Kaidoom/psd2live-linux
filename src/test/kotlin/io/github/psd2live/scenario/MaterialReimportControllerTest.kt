package io.github.psd2live.scenario

import io.github.psd2live.application.*
import io.github.psd2live.project.MutationAuthor
import io.github.psd2live.ui.state.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.test.*

class MaterialReimportControllerTest {
    @TempDir lateinit var temp: Path

    @Test fun dropsUpdateTheExistingFolderAndCancellingAnAmbiguousTargetCommitsNothing() = runBlocking {
        PSD2LiveViewModel().use { vm ->
            vm.setStateForTest(vm.state.value.copy(atlasSize = 512, exportMoc3 = false, generatePhysics = false))
            DesktopWorkspace(vm, temp.resolve("store")).use { workspace ->
                vm.attachWorkspace(workspace)
                val psd = Characters.headPsd(temp)
                workspace.importPsd(psd.toString(), true)
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
