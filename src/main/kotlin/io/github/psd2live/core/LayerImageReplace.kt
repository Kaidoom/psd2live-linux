package io.github.psd2live.core

import io.github.psd2live.project.WorkspaceDocument
import io.github.psd2live.document.WorkspaceSourceArt
import io.github.psd2live.document.WorkspaceSourceLayer
import io.github.psd2live.document.LayerCanvasRect
import io.github.psd2live.document.canvasRect
import org.umamo.format.art.LayerRaster
import kotlin.math.roundToInt

/**
 * Replace the displayed raster and freeze the preceding generation input for rig replay.
 * By default the old frame is retained. A source-sized replacement supplies the new frame
 * in layer coordinates; the layer transform continues to place it on the canvas.
 */
internal object LayerImageReplace {
	/** How a raster of another aspect ratio is laid onto the rectangle. */
	enum class Fit {
		/** The raster is stretched over the whole rectangle. */
		STRETCH,
		/** The raster keeps its aspect ratio, centred in the rectangle, with transparent pixels around it. */
		CONTAIN,
	}

	/** Rasters above this many pixels are refused, as any single layer is. */
	const val MAX_PIXELS: Long = 16L * 1024 * 1024

	/**
	 * [document] with layer [layerId]'s raster replaced by [raster] laid onto [placement] or the existing canvas rectangle
	 * by [fit]. The pixels before the first replacement or paint become the generation input.
	 */
	fun replace(document: WorkspaceDocument, layerId: String, raster: LayerRaster, fit: Fit,
	            placement: LayerCanvasRect? = null): WorkspaceDocument {
		require(raster.width > 0 && raster.height > 0) { "Replacement raster is empty" }
		require(raster.rgba.size.toLong() == raster.width.toLong() * raster.height * 4) { "Replacement raster does not match its size" }
		require(raster.width.toLong() * raster.height <= MAX_PIXELS) { "Replacement raster exceeds ${MAX_PIXELS / (1024 * 1024)} megapixels" }
		val layers = document.source.layers
		val index = layers.indexOfFirst { it.id.raw == layerId }
		require(index >= 0) { "Unknown source layer: $layerId" }
		val layer = layers[index]
		val rect = placement ?: layer.canvasRect()
		require(rect.width > 0f && rect.height > 0f) { "Layer $layerId has an empty canvas rectangle" }
		val laid = when (fit) {
			Fit.STRETCH -> raster
			Fit.CONTAIN -> contained(raster, rect.width / rect.height)
		}
		val old = WorkspaceSourceLayer.copyOf(layer, layer.order) as WorkspaceSourceLayer
		val replaced = if (placement == null) old.copy(raster = laid) else {
			LayerSizeBudget.require(laid.width, laid.height, placement)
			val (bounds, stored) = LayerSizeBudget.enclosing(placement)
			old.copy(raster = laid, bounds = bounds, rect = stored)
		}
		val source = WorkspaceSourceArt(document.source.widthPx, document.source.heightPx,
			layers.mapIndexed { i, old -> if (i == index) replaced else old }, document.source.groups)
		return document.copy(source = source,
			generationSource = RigGenerationSource.pinned(document.generationSource, document.source, layer, document.rigEdits,
				io.github.psd2live.project.WorkspaceSettingsCodec.decode(document.settings).meshTrace))
	}

	/** [raster] centred on transparent pixels so its aspect ratio becomes [aspect] (width over height). */
	fun contained(raster: LayerRaster, aspect: Float): LayerRaster {
		val own = raster.width.toFloat() / raster.height
		val width: Int; val height: Int
		if (own > aspect) { width = raster.width; height = maxOf(raster.height, (raster.width / aspect).roundToInt()) }
		else { height = raster.height; width = maxOf(raster.width, (raster.height * aspect).roundToInt()) }
		if (width == raster.width && height == raster.height) return raster
		require(width.toLong() * height <= MAX_PIXELS) { "Contained raster exceeds ${MAX_PIXELS / (1024 * 1024)} megapixels" }
		val rgba = ByteArray(width * height * 4)
		val left = (width - raster.width) / 2; val top = (height - raster.height) / 2
		for (y in 0 until raster.height) System.arraycopy(raster.rgba, y * raster.width * 4, rgba, ((top + y) * width + left) * 4, raster.width * 4)
		return LayerRaster(width, height, rgba)
	}
}
