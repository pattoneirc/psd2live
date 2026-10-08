package io.github.psd2live.application

import io.github.psd2live.core.RigPreviewModel
import io.github.psd2live.core.VertexGroupJournal
import io.github.psd2live.format.compile.document.ContentHash
import io.github.psd2live.project.*
import io.github.psd2live.targets.cubism.PuppetIr
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipFile
import kotlin.test.*

/**
 * A saved project carries each revision's authored rig, and a reopened revision builds from it: no generation, no
 * replay - so a journal the current generation could no longer replay still opens.
 */
class MaterializedRigProjectTest {
	@TempDir lateinit var temporary: Path
	private val builder = WorkspacePreviewBuilder()

	private val language = io.github.psd2live.i18n.I18n.currentLanguage

	// Generated names follow the UI language; the fixture's journal was written on Chinese names.
	@BeforeTest fun pinLanguage() = io.github.psd2live.i18n.I18n.setLanguage(io.github.psd2live.i18n.AppLanguage.CHINESE, persist = false)
	@AfterTest fun forget() { MaterializedRigStore.clear(); io.github.psd2live.i18n.I18n.setLanguage(language, persist = false) }

	private fun hash(model: RigPreviewModel) = ContentHash.of(PuppetIr.toIr(model.rig.puppet))

	/** A project of four revisions with splits, saved by an earlier build ([LegacySplitReplayTest]). */
	private fun fixture(): Path = temporary.resolve("sample.psd2live").also { target ->
		MaterializedRigProjectTest::class.java.getResourceAsStream("/projects/legacy-split.psd2live")!!.use { Files.copy(it, target) }
	}

	private fun extract(file: Path, into: Path): Path {
		ZipFile(file.toFile()).use { zip ->
			for (entry in zip.entries()) {
				if (entry.isDirectory) continue
				val target = into.resolve(entry.name).normalize()
				Files.createDirectories(target.parent)
				zip.getInputStream(entry).use { Files.copy(it, target) }
			}
		}
		return into
	}

	@Test fun aReopenedRevisionBuildsFromItsStoredAuthoredRig() = runBlocking<Unit> {
		MaterializedRigStore.clear()
		val repository = ProjectRepository(writeHeadCache = false)
		val saved = temporary.resolve("saved.psd2live")
		val expected = repository.open(fixture()).use { opened ->
			val hashes = opened.history.selections().associate { WorkspaceRevisions.of(it.snapshot) to hash(builder.build(it.snapshot)) }
			repository.save(ProjectSaveCapture(opened.projectId, opened.history.state(), opened.presentation, opened.source, opened.store), saved)
			hashes
		}
		val names = ZipFile(saved.toFile()).use { zip -> zip.entries().asSequence().map { it.name }.toList() }
		assertEquals(expected.keys.map { "rig/revisions/$it.json" }.toSet(), names.filter { it.startsWith("rig/revisions/") }.toSet())
		assertTrue(names.any { it.startsWith("rig/objects/") })

		MaterializedRigStore.clear()
		repository.open(saved).use { opened ->
			for (selection in opened.history.selections()) {
				val model = builder.build(selection.snapshot)
				assertFalse(model.sources.baseKnown, "${selection.node.summary} builds from its authored rig")
				assertEquals(expected.getValue(WorkspaceRevisions.of(selection.snapshot)), hash(model), selection.node.summary)
			}
		}
	}

	@Test fun aJournalThatNoLongerReplaysStillOpensFromItsAuthoredRig() = runBlocking<Unit> {
		MaterializedRigStore.clear()
		val repository = ProjectRepository(writeHeadCache = false)
		val saved = temporary.resolve("saved.psd2live")
		val (head, model) = repository.open(fixture()).use { opened ->
			val head = opened.history.head()
			val model = builder.build(head.snapshot)
			repository.save(ProjectSaveCapture(opened.projectId, opened.history.state(), opened.presentation, opened.source, opened.store), saved)
			head to model
		}
		val expected = hash(model)
		// The same revision's journal as a later generation would see it: one entry addresses vertices that are not there.
		val mesh = model.rig.puppet.drawables.first { it.mesh != null }.id.raw
		val drifted = head.snapshot.copy(rigEdits = head.snapshot.rigEdits.copy(authoringJournal = head.snapshot.rigEdits.authoringJournal +
			buildJsonObject { put("op", VertexGroupJournal.PUT); put("target", "mesh:$mesh"); put("name", "pin"); put("kind", "pin"); putJsonArray("weights") { add(1f) } }))
		MaterializedRigStore.clear()
		assertFailsWith<IllegalArgumentException> { builder.build(drifted) }
		// Its stored authored rig is the head's.
		val root = extract(saved, Files.createDirectories(temporary.resolve("extracted")))
		Files.move(root.resolve("rig/revisions/${WorkspaceRevisions.of(head.snapshot)}.json"), root.resolve("rig/revisions/${WorkspaceRevisions.of(drifted)}.json"))
		MaterializedRigStore.adopt(root)
		val reopened = builder.build(drifted)
		assertFalse(reopened.sources.baseKnown)
		assertEquals(expected, hash(reopened))
	}
}
