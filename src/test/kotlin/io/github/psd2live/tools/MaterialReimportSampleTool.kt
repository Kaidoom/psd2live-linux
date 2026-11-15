package io.github.psd2live.tools

import io.github.psd2live.application.*
import io.github.psd2live.project.*
import io.github.psd2live.scenario.MaterialReimportScenario
import io.github.psd2live.scenario.Studio
import io.github.psd2live.scenario.req
import kotlinx.coroutines.runBlocking
import org.umamo.format.art.LayerId
import org.umamo.format.art.LayerRaster
import org.umamo.format.art.SourceLayer
import org.umamo.format.psd.PsdReader
import org.umamo.format.psd.PsdWriter
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*

/** Opt-in local fixture check. Only reads the supplied files; writes test variants and archives under build/. */
class MaterialReimportSampleTool {
    @Test fun reimportAndCleanLocalProject() {
        requireTools()
        val project = Path.of(requireNotNull(System.getenv("PSD2LIVE_REIMPORT_PROJECT"))).toAbsolutePath()
        val psd = Path.of(requireNotNull(System.getenv("PSD2LIVE_REIMPORT_PSD"))).toAbsolutePath()
        val out = Path.of("build/psd-reimport/sample").toAbsolutePath().also { Files.createDirectories(it) }
        runBlocking { ProjectRepository().open(project) }.use { opened ->
            val document = opened.history.head().snapshot
            val notes = ArrayList<String>()
            Studio.run(out, document, checks = setOf(Studio.Check.UNDO, Studio.Check.REPLAY)) {
                val entries = WorkspaceMaterialFiles.read(listOf(psd))
                val original = now
                val target = WorkspaceMaterialReimport.targets(document.layout.materials, entries, null)
                val folder = requireNotNull(target.automatic) { "Sample source is ambiguous: ${target.folders.map { it.name }}" }
                val used = WorkspaceMaterialEdits.referencedMaterialIds(document.layout)
                val plan = WorkspaceMaterialReimport.plan(document.layout.materials, folder, entries, used)
                assertTrue(plan.issues.isEmpty(), "Sample needs manual resolution: ${plan.issues}")
                fun reimport(incoming: List<WorkspaceMaterialFiles.Entry>) = edit("reimport sample", WorkspaceMaterialReimport.OP,
                    req("folder_id" to folder, "staging" to WorkspaceMaterialStaging.stage(incoming)))
                reimport(entries)
                assertEquals(original.document.layout.materials.items.map { it.id }, now.document.layout.materials.items.map { it.id })
                val stable = now.historyHead
                reimport(entries)
                assertEquals(stable, now.historyHead)
                notes += "Unchanged PSD: ${entries.size} layers; repeated reimport creates no duplicates or history."

                val item = plan.items.filter { it.id in used }.let { items -> items.firstOrNull { it.name.contains("lash", true) } ?: items.first() }
                val index = plan.resolve(emptyMap(), false).entries.single { it.value == item.id }.key
                val art = PsdReader.read(Files.readAllBytes(psd))
                val source = art.layers.single { it.id.raw == entries[index].origin.substringAfterLast('#') }
                val pixels = source.raster.rgba.copyOf().also { for (i in it.indices step 4) it[i] = (it[i].toInt() xor 0x7f).toByte() }
                val painted = object : SourceLayer by source { override val raster = LayerRaster(source.raster.width, source.raster.height, pixels) }
                val nextId = art.layers.mapNotNull { it.id.raw.removePrefix("lyid:").toIntOrNull() }.maxOrNull()!! + 1
                val added = object : SourceLayer by painted {
                    override val id = LayerId("lyid:$nextId")
                    override val name = "LeftLeg"
                    override val groupPath = "Reimport test/New layers"
                }
                val variant = out.resolve("updated.psd")
                Files.write(variant, PsdWriter.write(art.widthPx, art.heightPx,
                    art.layers.map { if (it.id == source.id) painted else it } + added, art.groups))
                val before = now
                reimport(WorkspaceMaterialFiles.read(listOf(variant)))
                assertEquals(before.document.layout.layerMaterials, now.document.layout.layerMaterials)
                assertEquals(before.document.rigEdits, now.document.rigEdits)
                MaterialReimportScenario.assertGeometryPreserved(before.model.rig.puppet, puppet)
                val links = now.document.layout.layerMaterials.filterValues { it == item.id }.keys
                for (id in links) assertContentEquals(pixels, now.document.source.layers.single { it.id.raw == id }.raster.rgba)
                assertEquals(before.document.layout.materials.items.size + 1, now.document.layout.materials.items.size)
                notes += "Modified ${item.name}: updated ${links.size} canvas links; added LeftLeg; mesh, rig and animation data preserved."
                reopen()
                val folders = now.document.layout.materials.folders.filter { it.parentId == null }.map { it.id }
                val allUnused = folders.sumOf { WorkspaceMaterialEdits.unusedIds(now.document.layout, it).size }
                edits("clean every imported source folder", *folders.map { WorkspaceMaterialEdits.DELETE_UNUSED to req("folder_id" to it) }.toTypedArray())
                assertEquals(used, now.document.layout.materials.items.map { it.id }.toSet())
                assertEquals(original.document.layout.layerMaterials, now.document.layout.layerMaterials)
                assertEquals(before.document.rigEdits, now.document.rigEdits)
                reopen()
                notes += "Deleted $allUnused unused materials; ${used.size} referenced materials retained. Undo/redo and cold save/reload passed."
                notes += "Result: ${save()}"
            }
            Files.writeString(out.resolve("report.txt"), notes.joinToString("\n", postfix = "\n"))
            notes.forEach(::println)
        }
    }
}
