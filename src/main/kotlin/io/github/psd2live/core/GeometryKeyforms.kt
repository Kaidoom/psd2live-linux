package io.github.psd2live.core

import org.umamo.runtime.eval.EPS_KEY
import org.umamo.runtime.keyform.*
import org.umamo.runtime.model.*
import kotlin.math.abs

/** Geometry editing addresses sampled cells, independently of opacity and the parameter panel's stops. */
internal object GeometryKeyforms {
    fun axes(model: PuppetModel, kind: String, id: String): List<KeyformAxis> = when (kind) {
        "mesh" -> model.drawables.firstOrNull { it.id.raw == id }?.geometryGrid?.axes.orEmpty()
        "warp" -> (model.deformers.firstOrNull { it.id.raw == id } as? Deformer.Warp)?.geometryGrid?.axes.orEmpty()
        "rotation" -> (model.deformers.firstOrNull { it.id.raw == id } as? Deformer.Rotation)?.geometryGrid?.axes.orEmpty()
        else -> emptyList()
    }

    /** Explicit stops can be sculpted even before this object's geometry has a cell at that value. */
    fun editingAxes(model: PuppetModel, kind: String, id: String): List<KeyformAxis> = axes(model, kind, id).map { axis ->
        val keys = (axis.keys.toList() + ParameterKeyEdits.pointValues(model, axis.parameterId)).sorted()
            .fold(ArrayList<Float>()) { result, key ->
                if (result.isEmpty() || abs(result.last() - key) >= EPS_KEY) result += key
                result
            }
        KeyformAxis(axis.parameterId, keys.toFloatArray())
    }

    fun canPrepare(model: PuppetModel, kind: String, id: String, key: Map<String, Float>): Boolean =
        kind in setOf("mesh", "warp") && key.isNotEmpty() && axes(model, kind, id).all { it.parameterId.raw in key } &&
            key.keys.all { name -> model.parameters.any { it.id.raw == name && it.kind == ParameterKind.NORMAL } }

    /** Insert complete interpolated slices, so sculpting one key preserves every other keyed pose. */
    fun prepare(model: PuppetModel, kind: String, id: String, key: Map<String, Float>): PuppetModel {
        require(canPrepare(model, kind, id, key)) { "Cannot create a geometry key on $kind:$id at $key; include every bound geometry axis" }
        fun <T> seeded(original: KeyformGrid<T>, neutral: T, interpolator: FormInterpolator<T>): KeyformGrid<T> {
            val coordinate = original.axes.map { axis -> axis.keys.indexOfFirst { abs(it - key.getValue(axis.parameterId.raw)) < EPS_KEY } }
            if (original.axes.size == key.size && coordinate.all { it >= 0 } && original.cells.any { it.coordinate.toList() == coordinate })
                return original
            require(original.isDense) { "Cannot sculpt $kind:$id: its geometry grid is incomplete" }
            var grid = original
            for ((name, value) in key) {
                val parameter = model.parameters.single { it.id.raw == name }
                require(value.isFinite() && value in parameter.min..parameter.max) { "Geometry key $name=$value is outside its parameter range" }
                if (grid.axes.none { it.parameterId == parameter.id }) {
                    grid = requireNotNull(grid.withAxisSeeded(parameter, neutral)) { "Cannot bind geometry to $name" }
                    // Parameter marks may come from other objects or opacity. Seed them all with the
                    // unedited interpolation before a new geometry axis is sculpted at its first key.
                    for (stop in ParameterKeyEdits.pointValues(model, parameter.id))
                        grid = grid.withKeyInserted(parameter.id, stop, interpolator)
                }
                grid = grid.withKeyInserted(parameter.id, value, interpolator)
                require(grid.axes.single { it.parameterId == parameter.id }.keys.any { abs(it - value) < EPS_KEY }) {
                    "Cannot create geometry key $name=$value on $kind:$id: neighboring keys are too close"
                }
            }
            return grid
        }
        return when (kind) {
            "mesh" -> {
                val drawable = model.drawables.single { it.id.raw == id }
                val neutral = MeshDeltaForm(FloatArray(requireNotNull(drawable.mesh) { "$id has no editable mesh" }.positions.size))
                val grid = drawable.geometryGrid ?: KeyformGrid(emptyList(), listOf(KeyformCell(intArrayOf(), neutral)))
                val prepared = seeded(grid, neutral, MeshDeltaInterpolator)
                if (prepared === drawable.geometryGrid) model else model.withReplacedGeometryGrid(KeyformOwner.Drawable(drawable.id), prepared)
            }
            else -> {
                val warp = model.deformers.single { it.id.raw == id } as Deformer.Warp
                val grid = requireNotNull(warp.geometryGrid) { "$id has no editable warp grid" }
                val prepared = seeded(grid, grid.cells.first().form, WarpLatticeInterpolator)
                if (prepared === grid) model else model.withReplacedGeometryGrid(KeyformOwner.Deformer(warp.id), prepared)
            }
        }
    }
}
