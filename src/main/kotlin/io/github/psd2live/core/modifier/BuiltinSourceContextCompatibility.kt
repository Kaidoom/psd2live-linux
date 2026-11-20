package io.github.psd2live.core.modifier

import io.github.psd2live.algorithms.binding.MouthFittedSourceAlgorithm
import java.util.concurrent.ConcurrentHashMap
import java.util.zip.ZipFile

/** Compatibility for built-in archives that changed their input schema without changing package version. */
internal object BuiltinSourceContextCompatibility {
    private val contexts = ConcurrentHashMap<Triple<ModifierPluginReference, String, String>, Boolean>()

    fun supportsMouthScaffold(instance: RigModifierInstance, store: ModifierPluginStore = ModifierPluginStore.shared): Boolean =
        supports(instance, "MouthFittedSourceAlgorithm", MouthFittedSourceAlgorithm.COARSE_CONTEXT, store)

    /** 3.8.2 introduced a new outline selection policy together with richer submodule captures. */
    fun supportsMouthRibbons(instance: RigModifierInstance, store: ModifierPluginStore = ModifierPluginStore.shared): Boolean =
        supports(instance, "MouthVowelAlgorithm\$Companion", "outlineRibbons", store)

    fun supportsChainLabels(instance: RigModifierInstance, store: ModifierPluginStore = ModifierPluginStore.shared): Boolean =
        supports(instance, "ChainLabels", "chain_labels", store)

    /** Fedora 3.8.2 let insert_keys create the first geometry axis without recording source_axes.
     * Only its bundled workers contained this curve class. Stock workers use the upstream replay contract. */
    fun usesFedoraAxisInsertion(instance: RigModifierInstance): Boolean {
        val ref = instance.plugin ?: return false
        return ref.id.startsWith("builtin.") && ref != BuiltinModifierPlugins.entry(ref.id)?.reference &&
            supports(instance, "ClosedMouthVisibility", "closedMouthVisibility", ModifierPluginStore.shared)
    }

    private fun supports(instance: RigModifierInstance, className: String, field: String, store: ModifierPluginStore): Boolean {
        val reference = instance.plugin ?: return true
        if (!reference.id.startsWith("builtin.") || reference == BuiltinModifierPlugins.entry(reference.id)?.reference) return true
        val key = Triple(reference, className, field)
        contexts[key]?.let { return it }
        val entry = store.findRunnable(reference) ?: return false
        if ("runtime.jar" !in entry.manifest.files) return false
        // Package versions were reused across source-contract changes. Detect the supported key
        // in the immutable worker rather than treating every noncurrent digest as an old schema.
        // Inspect its immutable payload without loading or executing archived classes in the host.
        val supported = ZipFile(entry.directory.resolve("runtime.jar").toFile()).use { jar ->
            val file = jar.getEntry("io/github/psd2live/algorithms/binding/$className.class") ?: return@use false
            val bytes = jar.getInputStream(file).use { it.readNBytes(1024 * 1024) }
            val marker = field.toByteArray(Charsets.UTF_8)
            (0..bytes.size - marker.size).any { offset -> marker.indices.all { bytes[offset + it] == marker[it] } }
        }
        contexts[key] = supported
        return supported
    }
}
