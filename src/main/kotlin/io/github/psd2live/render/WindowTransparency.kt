package io.github.psd2live.render

import org.jetbrains.skiko.GraphicsApi
import org.jetbrains.skiko.SkiaLayer
import java.awt.Component
import java.awt.Container
import java.awt.EventQueue
import java.awt.Window

/** Linux GLX transparent windows can stall in swapBuffers, even before the canvas GPU is attached. */
internal object WindowTransparency {
    fun needsSoftware(transparent: Boolean, osName: String = System.getProperty("os.name")): Boolean =
        transparent && osName.contains("linux", ignoreCase = true)

    /** Called during the window's initial composition, before its first frame. No global renderer setting changes. */
    fun configure(window: Window, transparent: Boolean) {
        if (!needsSoftware(transparent)) return
        check(EventQueue.isDispatchThread())
        val layer = requireNotNull(findLayer(window)) { "Transparent window has no Skia layer" }
        // Skiko 0.144's public setter only changes the reported API. Use the same renderer recreation
        // path as its fallback handling, before a GLX frame can swap on the transparent window.
        // Like SkiaGpu's context binding, keep this version-specific interop confined to the render package.
        val manager = managerField.get(layer)
        layer.renderApi = GraphicsApi.SOFTWARE_FAST
        recreate.invoke(manager, true)
    }

    private val managerField by lazy { SkiaLayer::class.java.getDeclaredField("redrawerManager").apply { isAccessible = true } }
    private val recreate by lazy {
        managerField.type.getDeclaredMethod("findNextWorkingRenderApi", Boolean::class.javaPrimitiveType!!)
    }

    private fun findLayer(component: Component): SkiaLayer? {
        if (component is SkiaLayer) return component
        if (component is Container) for (child in component.components) findLayer(child)?.let { return it }
        return null
    }
}
