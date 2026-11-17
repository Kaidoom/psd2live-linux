package io.github.psd2live.core

import org.umamo.runtime.eval.EPS_KEY
import org.umamo.runtime.model.KeyformAxis
import org.umamo.runtime.model.ParameterId
import kotlin.math.abs

/** Evaluator tolerance and first-key tie breaking are shared by gesture and agent preparation. */
internal fun nearestKeyPose(axes: List<KeyformAxis>, pose: Map<ParameterId, Float>, defaults: Map<ParameterId, Float>): Map<ParameterId, Float> =
    buildMap {
        for ((id, bound) in axes.groupBy { it.parameterId }) {
            val current = pose[id] ?: defaults[id] ?: continue
            val keys = bound.flatMap { it.keys.toList() }.distinct().sorted()
            if (keys.any { abs(it - current) < EPS_KEY }) continue
            keys.minByOrNull { abs(it - current) }?.let { put(id, it) }
        }
    }
