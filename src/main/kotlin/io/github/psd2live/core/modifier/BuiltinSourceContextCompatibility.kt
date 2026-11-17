package io.github.psd2live.core.modifier

import io.github.psd2live.algorithms.binding.MouthFittedSourceAlgorithm
import java.util.concurrent.ConcurrentHashMap
import java.util.zip.ZipFile

/** Compatibility for built-in archives that changed their input schema without changing package version. */
internal object BuiltinSourceContextCompatibility {
    private val mouthContexts = ConcurrentHashMap<ModifierPluginReference, Boolean>()

    fun supportsMouthScaffold(instance: RigModifierInstance, store: ModifierPluginStore = ModifierPluginStore.shared): Boolean {
        val reference = instance.plugin ?: return true
        if (!reference.id.startsWith("builtin.") || reference == BuiltinModifierPlugins.reference(instance.type, instance.version)) return true
        mouthContexts[reference]?.let { return it }
        val entry = store.findRunnable(reference) ?: return false
        // Both old and new workers were published as 1.0.64. Their digest proves identity, not
        // schema age. The new worker's public context-key constant is the distinguishing marker.
        // Inspect its immutable payload without loading or executing archived classes in the host.
        val supported = ZipFile(entry.directory.resolve("runtime.jar").toFile()).use { jar ->
            val file = jar.getEntry("io/github/psd2live/algorithms/binding/MouthFittedSourceAlgorithm.class") ?: return@use false
            val bytes = jar.getInputStream(file).use { it.readNBytes(1024 * 1024) }
            val marker = MouthFittedSourceAlgorithm.COARSE_CONTEXT.toByteArray(Charsets.UTF_8)
            (0..bytes.size - marker.size).any { offset -> marker.indices.all { bytes[offset + it] == marker[it] } }
        }
        mouthContexts[reference] = supported
        return supported
    }
}
