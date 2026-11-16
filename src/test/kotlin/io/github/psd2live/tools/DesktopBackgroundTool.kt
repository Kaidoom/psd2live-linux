package io.github.psd2live.tools

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.awt.ComposeWindow
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import io.github.psd2live.render.*
import io.github.psd2live.ui.state.*
import org.jetbrains.skiko.GraphicsApi
import org.jetbrains.skiko.SkiaLayer
import org.junit.jupiter.api.Assumptions.assumeTrue
import java.awt.Component
import java.awt.Container
import java.awt.EventQueue
import java.awt.GraphicsEnvironment
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.*

/** PSD2LIVE_TOOLS=1 ./gradlew :test --tests '*DesktopBackgroundTool'. Uses the test JVM's isolated preferences. */
class DesktopBackgroundTool {
    private fun <T> edt(block: () -> T): T {
        if (EventQueue.isDispatchThread()) return block()
        val task = FutureTask<T> { block() }
        EventQueue.invokeLater(task)
        return try { task.get(3, TimeUnit.SECONDS) } catch (failure: Exception) {
            output("desktop-background").resolve("threads.txt").writeText(Thread.getAllStackTraces().entries.joinToString("\n\n") {
                (thread, stack) -> "${thread.name}: ${thread.state}\n${stack.joinToString("\n")}"
            })
            throw failure
        }
    }

    private fun await(label: String, condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(8)
        while (!edt(condition)) {
            check(System.nanoTime() < deadline) { "Timed out: $label" }
            Thread.sleep(30)
        }
    }

    private fun layer(component: Component): SkiaLayer? {
        if (component is SkiaLayer) return component
        if (component is Container) component.components.forEach { layer(it)?.let { found -> return found } }
        return null
    }

    @Test fun savedDesktopModeAndRepeatedWindowReplacementRemainResponsive() {
        requireTools()
        assumeTrue(WindowTransparency.needsSoftware(true) && !GraphicsEnvironment.isHeadless())
        GraphicsSettings.applyAtStartup(GraphicsOptions())
        val original = AppSettings.canvasBackground
        // The formerly stuck startup state; the app still honours the saved Desktop selection.
        AppSettings.canvasBackground = CanvasBackground(CanvasBackgroundKind.TRANSPARENT)
        val background = mutableStateOf(AppSettings.canvasBackground)
        val floatingVisible = mutableStateOf(false)
        val main = AtomicReference<ComposeWindow?>()
        val floating = AtomicReference<ComposeWindow?>()
        val close = AtomicReference<(() -> Unit)?>()
        val failure = AtomicReference<Throwable?>()
        val app = Thread {
            try {
                application(exitProcessOnExit = false) {
                    close.set { exitApplication() }
                    val transparent = background.value.windowTransparent
                    for (primary in listOf(true, false)) key(primary, transparent) {
                        Window(onCloseRequest = ::exitApplication, title = "PSD2Live Desktop background check",
                            state = rememberWindowState(size = DpSize(440.dp, 300.dp)),
                            undecorated = true, transparent = transparent, visible = primary || floatingVisible.value) {
                            remember(window) { WindowTransparency.configure(window, transparent) }
                            val target = if (primary) main else floating
                            DisposableEffect(window) {
                                target.set(window)
                                if (primary) SkiaGpu.capturePrimary(window)
                                onDispose { target.compareAndSet(window, null) }
                            }
                            Canvas(Modifier.fillMaxSize()) {
                                drawRect(if (transparent) Color.Transparent else Color.Blue, blendMode = BlendMode.Src)
                                drawRect(Color.Red, size = Size(size.width, 36f))
                            }
                        }
                    }
                }
            } catch (t: Throwable) { failure.set(t) }
        }.apply { isDaemon = true; start() }
        try {
            for (kind in listOf(CanvasBackgroundKind.TRANSPARENT, CanvasBackgroundKind.CHECKER,
                CanvasBackgroundKind.TRANSPARENT, CanvasBackgroundKind.SOLID)) {
                edt {
                    floatingVisible.value = false
                    AppSettings.canvasBackground = background.value.copy(kind = kind)
                    background.value = AppSettings.canvasBackground
                }
                val transparent = kind == CanvasBackgroundKind.TRANSPARENT
                val api = if (transparent) GraphicsApi.SOFTWARE_FAST else GraphicsApi.OPENGL
                await("$kind main with hidden floating panel") {
                    failure.get()?.let { throw it }
                    main.get()?.let { it.isShowing && it.isTransparent == transparent && it.renderApi == api } == true
                }
                edt { floatingVisible.value = true }
                await("$kind main and floating renderers") {
                    failure.get()?.let { throw it }
                    main.get()?.let { it.isShowing && it.isTransparent == transparent && it.renderApi == api } == true &&
                        floating.get()?.let { it.isShowing && it.isTransparent == transparent && it.renderApi == api } == true
                }
                await("$kind primary context") { if (transparent) SkiaGpu.primary == null else SkiaGpu.primary != null }
                // Keep pinging beyond the original 5-second freeze threshold, including after the first swap.
                repeat(24) {
                    edt { assertTrue(main.get()!!.isShowing); if (transparent) assertNull(SkiaGpu.primary) else assertNotNull(SkiaGpu.primary) }
                    Thread.sleep(250)
                }
                edt {
                    if (transparent) for (window in listOf(main.get()!!, floating.get()!!)) {
                        requireNotNull(layer(window)?.screenshot()).use { bitmap ->
                            assertTrue(bitmap.getColor(10, 10) ushr 24 > 0, "Window chrome must remain visible")
                            assertEquals(0, bitmap.getColor(bitmap.width / 2, bitmap.height / 2) ushr 24,
                                "Desktop must show through the canvas")
                        }
                    }
                }
                assertEquals(RenderPath.GPU, GraphicsSettings.current.renderPath, "The user's GPU preference must not change")
            }
        } finally {
            runCatching { edt { close.get()?.invoke(); AppSettings.canvasBackground = original } }
            app.join(5_000)
        }
        assertFalse(app.isAlive)
        failure.get()?.let { throw it }
    }
}
