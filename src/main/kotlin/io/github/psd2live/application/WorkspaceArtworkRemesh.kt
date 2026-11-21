package io.github.psd2live.application

import io.github.psd2live.bridge.PuppetIr
import io.github.psd2live.core.*
import io.github.psd2live.core.modifier.ModifierStatus
import io.github.psd2live.core.modifier.RigModifierStack
import io.github.psd2live.project.WorkspaceDocument
import io.github.psd2live.project.config
import kotlinx.serialization.json.*
import org.umamo.runtime.model.*

/** Carry sculpted offsets at their owning modifier stage across a deliberate artwork remesh. */
internal object WorkspaceArtworkRemesh {
    fun requested(edit: WorkspaceDocumentOperation, document: WorkspaceDocument): Boolean = when (edit.operation) {
        WorkspaceRasterEdits.REBUILD_MESH -> true
        WorkspaceGenerationEdits.GENERATE -> edit.request["refresh_geometry"]?.jsonArray?.isNotEmpty()
            ?: document.layout.generation.refreshLayerIds.isNotEmpty()
        "layer_replace_image" -> edit.request["fit"]?.jsonPrimitive?.content == "artwork" || edit.request["rebuild_mesh"]?.jsonPrimitive?.boolean == true
        else -> false
    }

    private fun meshId(command: JsonObject): String? = command["target"]?.jsonPrimitive?.content
        ?.takeIf { it.startsWith("mesh:") && command["op"]?.jsonPrimitive?.content == GeneratedOverrides.OP &&
            "channel" !in command }?.removePrefix("mesh:")

    fun transfer(candidate: WorkspaceDocument, before: RigPreviewModel, checkpoint: () -> Unit): WorkspaceDocument {
        val old = before.modifierEvaluation ?: return candidate
        var stack = candidate.rigEdits.modifierStack?.let(RigModifierStack::fromJson) ?: return candidate
        val owners = stack.instances.filter { instance -> instance.enabled && stack.overrides[instance.id].orEmpty().any { meshId(it) != null } }
        if (owners.isEmpty()) return candidate
        var result = candidate
        val pipeline = PSD2LivePipeline().apply { deferRuntimeBundles = true }
        try {
            for (owner in owners) {
                val index = old.entries.indexOfFirst { it.instance.id == owner.id && it.status == ModifierStatus.ACTIVE }
                if (index < 0) continue
                checkpoint()
                val probe = pipeline.buildPreview(result.source, result.config(), ProgressListener { _, _ -> checkpoint() }, before.atlas)
                val evaluated = requireNotNull(probe.modifierEvaluation)
                val at = evaluated.entries.indexOfFirst { it.instance.id == owner.id && it.status == ModifierStatus.ACTIVE }
                if (at < 0) continue // The transaction reports a failed stage before publishing.
                val original = PuppetIr.toPuppet(old.outputs[index])
                val replacement = PuppetIr.toPuppet(evaluated.outputs[at])
                val edits = stack.overrides.getValue(owner.id)
                val migrations = edits.mapNotNull(::meshId).distinct().associateWith { id ->
                    migration(original, replacement, DrawableId(id), checkpoint)
                }
                val rebased = edits.map { command ->
                    checkpoint()
                    val migration = migrations[meshId(command)]
                    val binding = command["geometry_binding"]?.jsonPrimitive?.content
                    when {
                        migration == null -> command
                        binding == migration.before -> migration.apply(command)
                        // Early absolute corrections lacked a fingerprint. Transfer only an edit that actually
                        // applied to its original stage, retaining its three-way merge and conflict detection.
                        binding == null && command["merge_mode"]?.jsonPrimitive?.content != "delta" &&
                            old.entries[index].overrideIssues.none { it.target == command["target"]?.jsonPrimitive?.content } ->
                            migration.apply(command)
                        else -> command
                    }
                }
                if (edits != rebased) {
                    stack = stack.copy(overrides = stack.overrides + (owner.id to rebased))
                    result = result.copy(rigEdits = result.rigEdits.copy(modifierStack = stack.toJson()))
                }
            }
        } finally {
            pipeline.modifierBuildOwner.close()
        }
        return result
    }

    private data class Migration(val before: String, val after: String, val oldCount: Int, val plan: RasterMeshJournal.Plan?) {
        fun apply(command: JsonObject): JsonObject {
            if (plan == null) return JsonObject(command + ("geometry_binding" to JsonPrimitive(after)))
            fun mapped(key: String): JsonArray {
                val values = command.getValue(key).jsonArray.map { it.jsonPrimitive.float }.toFloatArray()
                require(values.size == oldCount * 2) { "Saved mesh sculpt has an incompatible vertex count" }
                return JsonArray(RasterMeshJournal.remapOffsets(values, plan.sources).map(::JsonPrimitive))
            }
            return JsonObject(command + mapOf("base" to mapped("base"), "points" to mapped("points"), "geometry_binding" to JsonPrimitive(after)))
        }
    }

    private fun migration(old: PuppetModel, next: PuppetModel, id: DrawableId, checkpoint: () -> Unit): Migration? {
        val a = old.drawables.singleOrNull { it.id == id } ?: return null
        val b = next.drawables.singleOrNull { it.id == id } ?: return null
        val original = GeneratedOverrides.geometryBinding(old, "mesh", id.raw)
        val replacement = GeneratedOverrides.geometryBinding(next, "mesh", id.raw)
        if (original == replacement) return null
        require(a.parentDeformerId == b.parentDeformerId) {
            "Cannot transfer a sculpt on ${id.raw} to a different parent deformer. Keep the existing mesh or adjust the rig first."
        }
        val oldMesh = requireNotNull(a.mesh)
        val newMesh = requireNotNull(b.mesh)
        if (oldMesh.positions.contentEquals(newMesh.positions) && oldMesh.indices.contentEquals(newMesh.indices))
            return Migration(original, replacement, oldMesh.vertexCount, null)
        // Use the artwork as the correspondence surface. Its atlas tile can move or rotate, and the replacement
        // may occupy a different rectangle. The offset pairs themselves remain in the shared parent's local space.
        fun surface(model: PuppetModel, drawable: Drawable): DrawableMesh {
            val mesh = requireNotNull(drawable.mesh)
            val texture = LayerTexture.of(model, drawable).texture
            val space = texture.space
            val xy = texture.toCanvas(mesh.uvs)
            for (i in xy.indices step 2) {
                xy[i] = (xy[i] - space.left) / space.width
                xy[i + 1] = (xy[i + 1] - space.top) / space.height
            }
            return DrawableMesh(xy, mesh.uvs, mesh.indices)
        }
        val aMesh = surface(old, a)
        return Migration(original, replacement, aMesh.vertexCount, RasterMeshJournal.prepare(aMesh, surface(next, b), checkpoint))
    }
}
