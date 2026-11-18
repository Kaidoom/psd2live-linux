package io.github.psd2live.application

import io.github.psd2live.core.LayerImageReplace
import io.github.psd2live.core.RigPreviewModel
import io.github.psd2live.project.*
import kotlinx.serialization.json.*

/** PSD reconciliation shared by explicit reimport and smart material import. All mutations are private candidates. */
internal object WorkspaceMaterialReimport {
    const val OP = "material_reimport_psd"
    const val NEW = "@new"
    const val SKIP = "@skip"

    enum class Reason { AMBIGUOUS, POSSIBLE_RENAME, CHANGED_BOUNDS }
    data class Issue(val index: Int, val reason: Reason, val candidates: List<String>, val suggested: String? = null)
    data class Plan(
        val folderId: String,
        val entries: List<WorkspaceMaterialFiles.Entry>,
        val items: List<MaterialItem>,
        val automatic: Map<Int, String>,
        val issues: List<Issue>,
        val used: Set<String>,
    ) {
        fun resolve(choices: Map<Int, String>, fitChangedBounds: Boolean, adaptChangedBounds: Boolean = false): Map<Int, String> {
            require(choices.keys.all { it in entries.indices }) { "Reimport contains an unknown layer choice" }
            val resolved = automatic.toMutableMap()
            for (issue in issues) resolved[issue.index] = requireNotNull(choices[issue.index]) {
                "Resolve the PSD layer ${entries[issue.index].name} before reimporting"
            }
            // Choices for otherwise automatic rows are useful for explicitly skipping a layer.
            resolved.putAll(choices)
            require(resolved.size == entries.size) { "Every incoming PSD layer needs a disposition" }
            val byId = items.associateBy { it.id }
            val replacements = resolved.values.filter { it != NEW && it != SKIP }
            require(replacements.distinct().size == replacements.size) { "Two PSD layers cannot replace the same material" }
            for ((index, target) in resolved) {
                if (target == NEW || target == SKIP) continue
                val item = requireNotNull(byId[target]) { "Replacement material is outside the selected PSD folder: $target" }
                require(fitChangedBounds || adaptChangedBounds || item.id !in used || !changedBounds(item, entries[index])) {
                    "${item.name}: image dimensions or PSD bounds changed. Choose the updated PSD placement, fit the artwork to the existing bounds, or skip this layer."
                }
            }
            return resolved
        }

        fun retained(resolved: Map<Int, String>): Int = items.count { it.id !in resolved.values }
        fun updated(resolved: Map<Int, String>): Int {
            val byId = items.associateBy { it.id }
            return resolved.count { (index, id) -> byId[id]?.let { changedBounds(it, entries[index]) || !WorkspaceMaterialLinks.shows(it.raster, entries[index].raster) } == true }
        }
    }

    data class Targets(val folders: List<MaterialFolder>, val automatic: String? = null)

    fun isPsdFolder(folder: MaterialFolder): Boolean = folder.origin.endsWith(".psd", ignoreCase = true)

    /** A nested imported file is a different source, even when the user moved it beneath this PSD folder. */
    fun sourceItems(library: MaterialLibrary, folderId: String): List<MaterialItem> {
        val scope = library.subtree(folderId)
        val nested = library.folders.filter { it.id != folderId && it.id in scope && isPsdFolder(it) }
            .flatMapTo(HashSet()) { library.subtree(it.id) }
        return library.items.filter { it.folderId in scope && it.folderId !in nested && it.origin.substringBefore('#').endsWith(".psd", true) }
    }

    fun relativeFolders(entry: WorkspaceMaterialFiles.Entry): List<String> = entry.folder.drop(entry.fileFolder + 1)
    private fun path(entry: WorkspaceMaterialFiles.Entry): List<String> = entry.sourceLayerPath ?: (relativeFolders(entry) + entry.name)
    private fun path(library: MaterialLibrary, folderId: String, item: MaterialItem): List<String> =
        item.sourceLayerPath ?: (library.ancestry(item.folderId).takeWhile { it.id != folderId }.reversed().map { it.name } + item.name)

    private fun legacyId(item: MaterialItem): String? = item.origin.substringAfter('#', "").takeIf { it.matches(Regex("lyid:[0-9]+")) }
    private fun stableId(item: MaterialItem): String? = item.sourceLayerId ?: legacyId(item).takeIf { item.sourceLayerPath == null }

    /** Exact source paths win; legacy filenames can auto-match, while changed/uncertain sources need a choice. */
    fun targets(library: MaterialLibrary, entries: List<WorkspaceMaterialFiles.Entry>, destination: String?): Targets {
        val first = entries.first()
        val sourcePath = first.sourcePath
        val filename = first.origin.substringBefore('#')
        val scope = destination?.let(library::subtree)
        val folders = library.folders.filter { isPsdFolder(it) && (scope == null || it.id in scope) }
        val exact = folders.filter { sourcePath.isNotEmpty() && it.sourcePath == sourcePath }
        if (exact.isNotEmpty()) return Targets(exact, exact.singleOrNull()?.id)
        val named = folders.filter { it.origin == filename || it.name == first.folder.getOrNull(first.fileFolder) }
        if (named.isNotEmpty()) return Targets(named, named.singleOrNull()?.takeIf { it.sourcePath.isEmpty() }?.id)
        val paths = entries.mapTo(HashSet(), ::path)
        val possible = folders.filter { folder -> sourceItems(library, folder.id).any { path(library, folder.id, it) in paths } }
        return Targets(possible)
    }

    fun plan(library: MaterialLibrary, folderId: String, entries: List<WorkspaceMaterialFiles.Entry>, used: Set<String>): Plan {
        val folder = requireNotNull(library.folder(folderId)) { "PSD folder no longer exists: $folderId" }
        require(isPsdFolder(folder)) { "Select an imported PSD folder" }
        if (entries.isEmpty()) return Plan(folderId, entries, sourceItems(library, folderId), emptyMap(), emptyList(), used)
        require(entries.isNotEmpty() && entries.all { it.fileFolder >= 0 && it.origin.substringBefore('#').endsWith(".psd", true) }) {
            "Reimport requires PSD layer entries"
        }
        require(entries.map { it.sourcePath to it.folder.take(it.fileFolder + 1) }.distinct().size == 1) { "Reimport one PSD at a time" }
        val items = sourceItems(library, folderId)
        val byId = items.filter { stableId(it) != null }.groupBy { stableId(it)!! }
        val byPath = items.groupBy { path(library, folderId, it) }
        val idCounts = entries.mapNotNull { it.sourceLayerId }.groupingBy { it }.eachCount()
        val pathCounts = entries.groupingBy(::path).eachCount()
        val automatic = LinkedHashMap<Int, String>()
        val issues = LinkedHashMap<Int, Issue>()
        val reserved = HashSet<String>()
        fun match(index: Int, item: MaterialItem) {
            if (!reserved.add(item.id)) {
                issues[index] = Issue(index, Reason.AMBIGUOUS, listOf(item.id)); return
            }
            if (item.id in used && changedBounds(item, entries[index])) {
                issues[index] = Issue(index, Reason.CHANGED_BOUNDS, listOf(item.id), item.id)
            } else automatic[index] = item.id
        }
        // Resolve reliable IDs first so a name fallback cannot steal another layer's stable match.
        for ((index, entry) in entries.withIndex()) {
            val id = entry.sourceLayerId?.takeIf { idCounts[it] == 1 } ?: continue
            val candidates = byId[id].orEmpty()
            when {
                candidates.size > 1 -> issues[index] = Issue(index, Reason.AMBIGUOUS, candidates.map { it.id })
                candidates.size == 1 -> {
                    val item = candidates.single()
                    // Older files retain lyid in origin, but not the reader's reliability flag or original path.
                    if (item.sourceLayerId == null && path(library, folderId, item) != path(entry))
                        issues[index] = Issue(index, Reason.POSSIBLE_RENAME, listOf(item.id))
                    else match(index, item)
                }
            }
        }
        val unmatched = ArrayList<Int>()
        for ((index, entry) in entries.withIndex()) {
            if (index in automatic || index in issues) continue
            val candidates = byPath[path(entry)].orEmpty()
            when {
                candidates.size == 1 && pathCounts[path(entry)] == 1 && candidates.single().id !in reserved -> {
                    val item = candidates.single()
                    if (entry.sourceLayerId != null && stableId(item) != null && entry.sourceLayerId != stableId(item))
                        issues[index] = Issue(index, Reason.POSSIBLE_RENAME, listOf(item.id))
                    else match(index, item)
                }
                candidates.isNotEmpty() -> issues[index] = Issue(index, Reason.AMBIGUOUS, candidates.map { it.id })
                else -> unmatched += index
            }
        }
        val remaining = items.filter { it.id !in reserved }
        for (index in unmatched) {
            val id = entries[index].sourceLayerId
            val possibleRename = remaining.filter { id == null || stableId(it) == null }
            if (possibleRename.isEmpty()) automatic[index] = NEW
            else issues[index] = Issue(index, Reason.POSSIBLE_RENAME, possibleRename.map { it.id })
        }
        return Plan(folderId, entries, items, automatic, issues.values.sortedBy { it.index }, used)
    }

    fun changedBounds(item: MaterialItem, entry: WorkspaceMaterialFiles.Entry): Boolean =
        item.raster.width != entry.raster.width || item.raster.height != entry.raster.height || item.placement != entry.placement ||
            item.placementWidth != entry.placementWidth || item.placementHeight != entry.placementHeight

    /** A deliberately separate import gets a distinct container, rather than merging into a same-named folder. */
    fun separate(library: MaterialLibrary, entries: List<WorkspaceMaterialFiles.Entry>, destination: String?,
                 reserved: MutableSet<Pair<String?, List<String>>> = HashSet()): List<WorkspaceMaterialFiles.Entry> {
        val first = entries.first()
        val index = first.fileFolder
        val prefix = first.folder.take(index)
        var parent = destination
        for (name in prefix) parent = library.folders.firstOrNull { it.parentId == parent && it.name == name }?.id ?: break
        val occupied = library.folders.filter { it.parentId == parent }.mapTo(HashSet()) { it.name }
        val base = first.folder[index]
        var name = base
        var suffix = 2
        while (name in occupied || destination to (prefix + name) in reserved) name = "${base.take(115)} (${suffix++})"
        reserved += destination to (prefix + name)
        return entries.map { it.copy(folder = it.folder.toMutableList().also { path -> path[index] = name }) }
    }

    fun apply(document: WorkspaceDocument, model: RigPreviewModel, request: JsonObject, work: WorkspaceRasterWork): WorkspaceDocument {
        val entries = WorkspaceMaterialStaging.entries(request)
        val folderId = request.getValue("folder_id").jsonPrimitive.content
        val plan = plan(document.layout.materials, folderId, entries, WorkspaceMaterialEdits.referencedMaterialIds(document.layout))
        val choices = request["choices"]?.jsonObject?.mapKeys { it.key.toInt() }?.mapValues { it.value.jsonPrimitive.content }.orEmpty()
        val fit = request["fit_changed_bounds"]?.jsonPrimitive?.boolean == true
        val adapt = request["adapt_changed_bounds"]?.jsonPrimitive?.boolean == true
        require(!(fit && adapt)) { "Choose one placement for changed artwork" }
        val resolved = plan.resolve(choices, fit, adapt)
        val refresh = mutableSetOf<String>()
        var next = document
        val additions = ArrayList<WorkspaceMaterialFiles.Entry>()
        for ((index, entry) in entries.withIndex()) {
            work.checkpoint()
            when (val target = resolved.getValue(index)) {
                SKIP -> Unit
                NEW -> additions += entry.copy(folder = relativeFolders(entry), fileFolder = -1)
                else -> {
                    val item = next.layout.materials.item(target)!!
                    val samePixels = WorkspaceMaterialLinks.shows(item.raster, entry.raster)
                    val updated = item.copy(raster = if (samePixels) item.raster else entry.raster, kind = entry.kind, svg = entry.svg,
                        placement = entry.placement, placementWidth = entry.placementWidth, placementHeight = entry.placementHeight,
                        origin = entry.origin, sourceLayerId = entry.sourceLayerId, sourceLayerPath = entry.sourceLayerPath)
                    if (adapt && changedBounds(item, entry)) {
                        for (id in WorkspaceMaterialLinks.users(next, item.id)) {
                            work.checkpoint()
                            val layer = next.source.layers.single { it.id.raw == id }
                            val rect = WorkspaceTextureEdits.artworkPlacement(next, layer, updated)
                            next = LayerImageReplace.replace(next, id, entry.raster, LayerImageReplace.Fit.STRETCH, rect)
                            if (next.layout.generation.generated && id !in WorkspaceLayoutPreview.pendingIds(next) &&
                                id !in next.layout.referenceLayerIds) refresh += id
                        }
                    } else if (!samePixels)
                        next = WorkspaceMaterialPixels.replace(next, model, item, entry.raster)
                    val current = next.layout.materials.item(target)!!
                    if (updated != current) next = next.copy(layout = next.layout.copy(materials = next.layout.materials.copy(
                        items = next.layout.materials.items.map { if (it.id == target) updated else it })))
                }
            }
        }
        // Do not merge a source group into a nested, independently imported PSD container.
        for (entry in additions) {
            var parent: String? = folderId
            for (name in entry.folder) {
                val existing = next.layout.materials.folders.firstOrNull { it.parentId == parent && it.name == name } ?: break
                require(!isPsdFolder(existing)) { "PSD group $name conflicts with another imported PSD folder; rename or move that folder first" }
                parent = existing.id
            }
        }
        if (refresh.isNotEmpty()) next = WorkspaceGenerationEdits.refreshArtwork(next, refresh)
        if (additions.isNotEmpty()) next = WorkspaceMaterialEdits.add(next, additions, folderId, work::checkpoint)
        val source = entries.withIndex().firstOrNull { resolved[it.index] != SKIP }?.value ?: return next
        val folder = next.layout.materials.folder(folderId)!!
        val updatedFolder = folder.copy(origin = source.origin.substringBefore('#'), sourcePath = source.sourcePath)
        if (updatedFolder != folder) next = next.copy(layout = next.layout.copy(materials = next.layout.materials.copy(
            folders = next.layout.materials.folders.map { if (it.id == folderId) updatedFolder else it })))
        return next
    }
}
