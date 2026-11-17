package io.github.psd2live.tools

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.*
import androidx.compose.ui.unit.dp
import io.github.psd2live.application.*
import io.github.psd2live.core.modifier.RigModifierStack
import io.github.psd2live.i18n.*
import io.github.psd2live.scenario.Characters
import io.github.psd2live.ui.state.PSD2LiveViewModel
import io.github.psd2live.ui.theme.*
import io.github.psd2live.ui.views.ClosedMouthVisibilitySection
import kotlinx.coroutines.*
import kotlinx.serialization.json.JsonObject
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import java.io.File
import kotlin.test.*

/** Offscreen rendering and real pointer events; no user workspace or preferences are written. */
@EnabledIfEnvironmentVariable(named = "PSD2LIVE_TOOLS", matches = "1")
@OptIn(ExperimentalComposeUiApi::class)
class ClosedMouthVisibilityPanelTool {
    @Test fun visibilityPanel() = runBlocking {
        val previous = I18n.currentLanguage
        try {
            I18n.setLanguage(AppLanguage.ENGLISH, persist = false)
            val (prepared, choices) = WorkspacePresetInitialize.prepare(Characters.closedMouthed(), WorkspaceInitialModifiers.RECOMMENDED)
            val document = WorkspacePresetInitialization.prepare(prepared, WorkspaceRasterWork.Direct, requireNotNull(choices)).first
            val model = WorkspacePreviewBuilder().build(document)
            val mouth = RigModifierStack.fromJson(document.rigEdits.modifierStack!!).instances.single { it.type == "mouth" }
            PSD2LiveViewModel().use { vm ->
                vm.setStateForTest(vm.state.value.copy(previewModel = model, rigEdits = document.rigEdits))
                withContext(Dispatchers.Main) {
                    for (width in listOf(320, 480)) {
                        var committed: JsonObject? = null
                        // A different saved build is still editable, without applying an algorithm upgrade.
                        var displayed by mutableStateOf(mouth.copy(plugin = mouth.plugin?.copy(digest = "0".repeat(64))))
                        val scene = ImageComposeScene(width, 300, coroutineContext = currentCoroutineContext()) {
                            val state by vm.state.collectAsState()
                            CompactToolTheme(ToolColors.Dark) {
                                Column(Modifier.fillMaxSize().background(LocalToolColors.current.panelBackground).padding(8.dp)) {
                                    ClosedMouthVisibilitySection(displayed, state, true, vm) { config, _, done -> committed = config; done(null) }
                                }
                            }
                        }
                        try {
                            var time = 0L
                            suspend fun frames() { repeat(10) { time += 16_000_000; scene.render(time).close(); yield() } }
                            suspend fun click(x: Float, y: Float) {
                                val p = Offset(x, y)
                                scene.sendPointerEvent(PointerEventType.Press, p, buttons = PointerButtons(isPrimaryPressed = true), button = PointerButton.Primary)
                                scene.sendPointerEvent(PointerEventType.Release, p, button = PointerButton.Primary)
                                frames()
                            }
                            suspend fun dragPoint(from: Offset, to: Offset) {
                                scene.sendPointerEvent(PointerEventType.Press, from, buttons = PointerButtons(isPrimaryPressed = true), button = PointerButton.Primary)
                                frames()
                                repeat(8) { i ->
                                    scene.sendPointerEvent(PointerEventType.Move, from + (to - from) * ((i + 1) / 8f), buttons = PointerButtons(isPrimaryPressed = true))
                                    frames()
                                }
                                scene.sendPointerEvent(PointerEventType.Release, to, button = PointerButton.Primary)
                                frames()
                            }
                            frames(); click(100f, 20f)
                            val file = File("build/tools/closed-mouth-visibility/panel-$width.png").also { it.parentFile.mkdirs() }
                            scene.render(time + 16_000_000).use { file.writeBytes(requireNotNull(it.encodeToData()).bytes) }
                            assertNull(committed, "Opening the control must not write a configuration")
                            if (width == 480) {
                                dragPoint(Offset(52f, 104f), Offset(136f, 104f)) // Move 0.05 to 0.25 on the key rail.
                                click(114f, 104f) // Add an opacity stop near 0.20 on the shared key rail.
                                click(391f, 166f) // Set its opacity to 100% with the existing numeric slider.
                                click(240f, 104f) // Add, then delete a spare opacity key with the shared rail.
                                scene.sendPointerEvent(PointerEventType.Press, Offset(240f, 104f),
                                    buttons = PointerButtons(isSecondaryPressed = true), button = PointerButton.Secondary)
                                scene.sendPointerEvent(PointerEventType.Release, Offset(240f, 104f), button = PointerButton.Secondary)
                                frames()
                                assertNull(committed, "Editing the curve stays local until Apply")
                                scene.render(time + 16_000_000).use { File(file.parentFile, "edited.png").writeBytes(requireNotNull(it.encodeToData()).bytes) }
                                click(446f, 230f)
                                val curve = io.github.psd2live.algorithms.binding.ClosedMouthVisibility.fromConfig(assertNotNull(committed))!!
                                assertEquals(4, curve.points.size)
                                assertEquals(.2f, curve.points[1].open, .005f)
                                assertEquals(1f, curve.points[1].opacity, .01f)
                                assertEquals(.25f, curve.points[2].open, .005f)
                                assertEquals(0f, curve.points[2].opacity, .01f)
                                // Compaction may remove an all-constant opacity axis. The saved curve must stay editable.
                                val constant = io.github.psd2live.algorithms.binding.ClosedMouthVisibility.Default.setOpacity(.05f, 1f).setOpacity(1f, 1f)
                                displayed = mouth.copy(config = JsonObject(mouth.config + ("closedMouthVisibility" to constant.toJson())))
                                val compacted = model.rig.puppet.copy(drawables = model.rig.puppet.drawables.map { drawable ->
                                    if (model.rig.ownLayerIdByDrawableId[drawable.id.raw] == "closed-mouth")
                                        drawable.copy(channelGrids = org.umamo.runtime.model.ChannelGrids.Empty) else drawable
                                })
                                vm.setStateForTest(vm.state.value.copy(previewModel = model.copy(rig = model.rig.copy(puppet = compacted))))
                                frames()
                                scene.render(time + 16_000_000).use { rendered ->
                                    val bytes = requireNotNull(rendered.encodeToData()).bytes
                                    File(file.parentFile, "constant.png").writeBytes(bytes)
                                    val bitmap = javax.imageio.ImageIO.read(java.io.ByteArrayInputStream(bytes))
                                    val colors = bitmap.getRGB(12, 12, 250, 20, null, 0, 250).toSet()
                                    assertTrue(colors.size > 2, "A constant saved visibility curve must not hide its control")
                                }
                            }
                        } finally { scene.close() }
                    }
                }
            }
        } finally { I18n.setLanguage(previous, persist = false) }
    }
}
