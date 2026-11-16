package io.github.psd2live.render

import io.github.psd2live.ui.state.CanvasBackground
import io.github.psd2live.ui.state.CanvasBackgroundKind
import kotlin.test.*

class WindowTransparencyTest {
    @Test fun onlyLinuxDesktopBackgroundNeedsTheSoftwareWindow() {
        for (kind in CanvasBackgroundKind.entries) {
            val transparent = CanvasBackground(kind).windowTransparent
            assertEquals(kind == CanvasBackgroundKind.TRANSPARENT, WindowTransparency.needsSoftware(transparent, "Linux"))
            assertFalse(WindowTransparency.needsSoftware(transparent, "Windows 11"))
            assertFalse(WindowTransparency.needsSoftware(transparent, "Mac OS X"))
        }
    }
}
