package io.github.psd2live.application

import io.github.psd2live.project.*
import io.github.psd2live.scenario.Characters
import org.umamo.format.art.LayerRaster
import kotlin.test.*

class WorkspaceMaterialReimportTest {
    private val raster = LayerRaster(2, 2, ByteArray(16) { -1 })
    private val root = MaterialFolder("root", "character", null, origin = "character.psd", sourcePath = "/art/character.psd")
    private fun entry(name: String = "eye", id: String? = "lyid:1", group: String = "face") = WorkspaceMaterialFiles.Entry(
        name, listOf("character", group), MaterialKind.BITMAP, raster, LayerCanvasRect(1f, 2f, 2f, 2f),
        origin = "character.psd#${id ?: name}", fileFolder = 0, placementWidth = 100, placementHeight = 100,
        sourceFileName = "character.psd", sourcePath = root.sourcePath, sourceLayerId = id, sourceLayerPath = listOf(group, name))
    private fun item(id: String, e: WorkspaceMaterialFiles.Entry) = MaterialItem(id, e.name, "group", e.kind, e.raster,
        e.placement, origin = e.origin, placementWidth = e.placementWidth, placementHeight = e.placementHeight,
        sourceLayerId = e.sourceLayerId, sourceLayerPath = e.sourceLayerPath)
    private fun library(vararg items: MaterialItem) = MaterialLibrary(listOf(root, MaterialFolder("group", "face", root.id)), items.toList())
    private fun plan(library: MaterialLibrary, vararg entries: WorkspaceMaterialFiles.Entry, used: Set<String> = emptySet()) =
        WorkspaceMaterialReimport.plan(library, root.id, entries.toList(), used)

    @Test fun stableIdsWinOverNamesAndKeepRenamedLayers() {
        val a = entry(); val b = entry("mouth", "lyid:2")
        val p = plan(library(item("a", a), item("b", b)), a.copy(name = "mouth", sourceLayerPath = listOf("face", "mouth")), b)
        assertEquals(mapOf(0 to "a", 1 to "b"), p.resolve(emptyMap(), false))
        assertTrue(p.issues.isEmpty())
    }

    @Test fun legacyHierarchySeparatesEqualNamesAndUnstableIdsAreIgnored() {
        val a = entry(id = null); val b = entry(id = null, group = "body")
        val lib = library(item("a", a).copy(sourceLayerPath = null), item("b", b).copy(folderId = "body", sourceLayerPath = null))
            .let { it.copy(folders = it.folders + MaterialFolder("body", "body", root.id)) }
        assertEquals(mapOf(0 to "a", 1 to "b"), plan(lib, a, b).resolve(emptyMap(), false))
    }

    @Test fun duplicatePathsRequireExplicitDistinctChoices() {
        val e = entry(id = null)
        val p = plan(library(item("a", e), item("b", e)), e, e)
        assertEquals(2, p.issues.size)
        assertFailsWith<IllegalArgumentException> { p.resolve(emptyMap(), false) }
        assertFailsWith<IllegalArgumentException> { p.resolve(mapOf(0 to "a", 1 to "a"), false) }
        assertEquals(mapOf(0 to "a", 1 to "b"), p.resolve(mapOf(0 to "a", 1 to "b"), false))
    }

    @Test fun uncertainRenamesAndReusedNamesNeedConfirmation() {
        val old = entry(id = null)
        val renamed = entry("eyelash", id = null)
        val p = plan(library(item("a", old)), renamed)
        assertEquals(WorkspaceMaterialReimport.Reason.POSSIBLE_RENAME, p.issues.single().reason)
        assertEquals("a", p.resolve(mapOf(0 to "a"), false)[0])
        val changedId = plan(library(item("a", entry())), entry(id = "lyid:9"))
        assertEquals(1, changedId.issues.size)
        val legacy = plan(library(item("a", entry()).copy(sourceLayerId = null, sourceLayerPath = null)), entry("eyelash"))
        assertEquals(1, legacy.issues.size)
    }

    @Test fun knownUnreliableIdsNeverBecomeLegacyStableIds() {
        val old = item("a", entry()).copy(sourceLayerId = null)
        val p = plan(library(old), entry("renamed"))
        assertEquals(WorkspaceMaterialReimport.Reason.POSSIBLE_RENAME, p.issues.single().reason)
        assertTrue(p.automatic.isEmpty())
        val exactPath = plan(library(old), entry(id = "lyid:2"))
        assertEquals("a", exactPath.resolve(emptyMap(), false)[0], "A known unreliable old ID must allow exact path matching")
    }

    @Test fun newIdsAddAndMissingLayersAreRetained() {
        val p = plan(library(item("a", entry())), entry("LeftLeg", "lyid:20", "legs"))
        val resolved = p.resolve(emptyMap(), false)
        assertEquals(WorkspaceMaterialReimport.NEW, resolved[0])
        assertEquals(1, p.retained(resolved))
        assertEquals(1, plan(library(item("a", entry()))).retained(emptyMap()))
    }

    @Test fun changedUsedBoundsRequireAPlacementDecisionAndSupportAdaptingOrFitting() {
        val old = entry()
        val lib = library(item("a", old))
        for (changed in listOf(old.copy(raster = LayerRaster(1, 4, raster.rgba)),
            old.copy(placement = old.placement!!.copy(top = 10f)), old.copy(placementWidth = 200))) {
            val p = plan(lib, changed, used = setOf("a"))
            assertEquals(WorkspaceMaterialReimport.Reason.CHANGED_BOUNDS, p.issues.single().reason)
            assertFailsWith<IllegalArgumentException> { p.resolve(mapOf(0 to "a"), false) }
            assertEquals("a", p.resolve(mapOf(0 to "a"), true)[0])
            assertEquals("a", p.resolve(mapOf(0 to "a"), false, adaptChangedBounds = true)[0])
            assertEquals(WorkspaceMaterialReimport.SKIP, p.resolve(mapOf(0 to WorkspaceMaterialReimport.SKIP), false)[0])
            assertEquals(WorkspaceMaterialReimport.NEW, p.resolve(mapOf(0 to WorkspaceMaterialReimport.NEW), false)[0])
            assertTrue(plan(lib, changed).issues.isEmpty())
        }
    }

    @Test fun aSharedInstanceKeepsItsOwnPlacementScaleAndFlipWhenPsdBoundsMove() {
        val old = item("a", entry())
        val doc = Characters.figure()
        val source = doc.source.layers.first()
        val flip = LayerTransform(-1f, 0f, 0f, 1f, 140f, 0f)
        val placed = (WorkspaceSourceLayer.copyOf(source, source.order) as WorkspaceSourceLayer).copy(
            bounds = org.umamo.format.art.LayerBounds(30, 40, 4, 6), raster = raster, layerTransform = flip, derived = true)
        val linked = doc.copy(source = WorkspaceSourceArt(100, 100, listOf(placed), emptyList()),
            layout = doc.layout.copy(materials = library(old), layerMaterials = mapOf(source.id.raw to old.id)))
        val moved = old.copy(placement = LayerCanvasRect(3f, 1f, 4f, 2f))
        val rect = WorkspaceTextureEdits.artworkPlacement(linked, placed, moved)
        assertEquals(LayerCanvasRect(34f, 37f, 8f, 6f), rect)
        val next = io.github.psd2live.core.LayerImageReplace.replace(linked, placed.id.raw, raster,
            io.github.psd2live.core.LayerImageReplace.Fit.STRETCH, rect)
        assertEquals(flip, next.source.layers.single().transform)
        assertEquals(rect, next.source.layers.single().canvasRect())
        assertContentEquals(raster.rgba, next.source.layers.single().raster.rgba)
    }

    @Test fun repeatedDropsUseSourceIdentityAndUncertainSourcesOfferSeparateImport() {
        val lib = library(item("a", entry()))
        repeat(3) { assertEquals(root.id, WorkspaceMaterialReimport.targets(lib, listOf(entry()), null).automatic) }
        val otherPath = entry().copy(sourcePath = "/other/character.psd")
        assertNull(WorkspaceMaterialReimport.targets(lib, listOf(otherPath), null).automatic)
        val duplicated = lib.copy(folders = lib.folders + root.copy(id = "copy", name = "character (2)"))
        assertEquals(2, WorkspaceMaterialReimport.targets(duplicated, listOf(entry()), null).folders.size)
        assertNull(WorkspaceMaterialReimport.targets(duplicated, listOf(entry()), null).automatic)
        assertEquals("character (2)", WorkspaceMaterialReimport.separate(lib, listOf(entry()), null).single().folder.first())
        val renamed = entry().copy(sourcePath = "/art/new.psd", sourceFileName = "new.psd", origin = "new.psd#lyid:1", folder = listOf("new", "face"))
        assertEquals(root.id, WorkspaceMaterialReimport.targets(lib, listOf(renamed), null).folders.single().id)
        assertNull(WorkspaceMaterialReimport.targets(lib, listOf(renamed), null).automatic)
    }

    @Test fun nestedIndependentPsdAndOtherImageImportsAreNotMatched() {
        val e = entry()
        val png = item("png", e).copy(origin = "character.psd#eye.png", placement = null, sourceLayerPath = null, sourceLayerId = null)
        val lib = library(item("a", e), item("nested", e).copy(folderId = "nested"), png)
            .let { it.copy(folders = it.folders + root.copy(id = "nested", parentId = "group")) }
        assertEquals(listOf("a"), WorkspaceMaterialReimport.sourceItems(lib, root.id).map { it.id })
    }

    @Test fun cleanupUsesAllDocumentLinksAndPrunesOnlyEmptyDescendants() {
        val lib = library(*(0..5).map { item("m$it", entry()).copy(folderId = if (it < 3) "group" else "empty") }.toTypedArray())
            .let { it.copy(folders = it.folders + MaterialFolder("empty", "empty", root.id)) }
        val layout = WorkspaceLayout(materials = lib, layerMaterials = mapOf("active" to "m0", "deleted" to "m1"),
            references = listOf(LayoutReference("m2", LayerCanvasRect(0f, 0f, 10f, 10f))))
        val document = Characters.figure().copy(layout = layout, deletedLayerIds = setOf("deleted"))
        val cleaned = WorkspaceMaterialEdits.deleteUnused(document, root.id)
        assertEquals(setOf("m3", "m4", "m5"), WorkspaceMaterialEdits.unusedIds(layout, root.id))
        assertEquals(listOf("m0", "m1", "m2"), cleaned.layout.materials.items.map { it.id })
        assertEquals(setOf(root.id, "group"), cleaned.layout.materials.folders.map { it.id }.toSet())
        assertEquals(layout.layerMaterials, cleaned.layout.layerMaterials)
        assertEquals(layout.references, cleaned.layout.references)
    }

    @Test fun cleanupKeepsPreExistingEmptyFoldersAndPrunesOnlyNewlyEmptiedAncestors() {
        val folders = listOf(root, MaterialFolder("unused", "Unused", root.id), MaterialFolder("leaf", "Leaf", "unused"),
            MaterialFolder("costumes", "Future costumes", root.id), MaterialFolder("reserved", "Reserved", "costumes"))
        val library = MaterialLibrary(folders, listOf(item("discard", entry()).copy(folderId = "leaf")))
        val document = Characters.figure().copy(layout = WorkspaceLayout(materials = library))
        val cleaned = WorkspaceMaterialEdits.deleteUnused(document, root.id)
        assertEquals(listOf(root.id, "costumes", "reserved"), cleaned.layout.materials.folders.map { it.id })
        assertSame(cleaned, WorkspaceMaterialEdits.deleteUnused(cleaned, root.id))
    }

    @Test fun truncatedSavedSourcesMatchWithoutLosingHashesInFilenamesOrLayerIds() {
        val filename = "Character.psd#2.PSD"
        val layerName = "Eye#Left"
        val incoming = entry(layerName, id = null).copy(sourceFileName = filename, origin = "$filename#$layerName#3",
            sourcePath = "/moved/$filename")
        val original = item("eye", incoming).copy(sourceLayerId = null, sourceLayerPath = null)
        for (savedPath in listOf("", "C:\\Art\\$filename")) {
            val folder = root.copy(name = filename.substringBeforeLast('.'), origin = "Character.psd", sourcePath = savedPath)
            val stored = MaterialLibrary(listOf(folder, MaterialFolder("group", "face", folder.id)), listOf(original))
            val encoded = WorkspaceLayoutCodec.encodeMaterials(stored) { "pixels" }
            val library = WorkspaceLayoutCodec.decode(null, encoded) { _, _, _ -> raster.rgba }.materials
            assertEquals(filename, library.sourceFileName(library.folders.first()))
            assertEquals(filename, library.sourceFileName(original))
            assertEquals("$layerName#3", library.originLayerId(original))
            assertEquals(folder.id, WorkspaceMaterialReimport.targets(library, listOf(incoming), null).folders.single().id)
            assertEquals(mapOf(0 to "eye"), plan(library, incoming).resolve(emptyMap(), false))
        }
    }

    @Test fun sourceMetadataRoundTripsAndOldProjectsNeedNoMigration() {
        for (lib in listOf(library(item("a", entry())), library(item("a", entry()).copy(sourceLayerId = null, sourceLayerPath = null))
            .let { it.copy(folders = it.folders.map { f -> f.copy(sourcePath = "") }) })) {
            val encoded = WorkspaceLayoutCodec.encodeMaterials(lib) { "pixels" }
            val decoded = WorkspaceLayoutCodec.decode(null, encoded) { _, _, _ -> raster.rgba }.materials
            assertEquals(encoded, WorkspaceLayoutCodec.encodeMaterials(decoded) { "pixels" })
        }
    }
}
