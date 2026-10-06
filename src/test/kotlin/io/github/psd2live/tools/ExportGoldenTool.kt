package io.github.psd2live.tools

import io.github.psd2live.core.MotionClip
import io.github.psd2live.core.MotionCurve
import io.github.psd2live.core.MotionInterpolation
import io.github.psd2live.core.MotionKey
import io.github.psd2live.core.PSD2LivePipeline
import io.github.psd2live.core.SkeletonAutoBuilder
import java.io.File
import java.security.MessageDigest
import kotlin.test.Test

/**
 * SHA-256 of every exported file for the bundled samples, plain and on their auto skeleton, so a refactor of
 * the export path can be checked byte for byte against the previous commit.
 *
 * PSD2LIVE_TOOLS=1 ./gradlew test --tests '*ExportGoldenTool'
 * Writes build/tools/export-golden/<label>.txt. The cmo3 carries an export timestamp, so it is listed by
 * size only; its content is compared through its read-back elsewhere.
 */
class ExportGoldenTool {
	@Test fun dump() {
		requireTools()
		val out = output("export-golden")
		val label = setting("PSD2LIVE_GOLDEN_LABEL", "current")
		val report = StringBuilder()
		for (sample in listOf("tml", "ds")) {
			val psd = File("examples/$sample/psd-input/$sample.psd").toPath()
			val plain = PSD2LivePipeline().buildPreview(psd)
			val skeletal = PSD2LivePipeline().buildPreview(plain.analysis, plain.config.copy(
				rigEdits = plain.config.rigEdits.copy(skeleton = SkeletonAutoBuilder.build(plain.analysis, plain.rig))))
			val wave = MotionClip("wave", "Wave", duration = 1.5f, curves = listOf(MotionCurve("ParamAngleX", listOf(MotionKey(0.2f, 0f),
				MotionKey(0.7f, 20f, MotionInterpolation.LINEAR), MotionKey(1.1f, -5f, MotionInterpolation.STEPPED), MotionKey(1.5f, -10f)))))
			val authored = PSD2LivePipeline().buildPreview(plain.analysis, plain.config.copy(
				rigEdits = plain.config.rigEdits.copy(motionClips = listOf(wave))))
			for ((variant, preview) in listOf("plain" to plain, "skeleton" to skeletal, "clip" to authored)) {
				val dir = File(out, "$label/$sample-$variant").apply { deleteRecursively(); mkdirs() }
				val result = PSD2LivePipeline().run(preview.analysis.source, sample, dir.toPath(), preview.config.copy(exportJson = false))
				for (file in result.exportedFiles.sortedBy { it.path.toString() }) {
					val bytes = file.path.toFile().readBytes()
					val name = dir.toPath().toAbsolutePath().relativize(file.path.toAbsolutePath()).toString().replace('\\', '/')
					val digest = if (name.endsWith(".cmo3")) "readback=" + cmo3Readback(bytes) else sha(bytes)
					report.appendLine("$sample-$variant $name $digest")
				}
				for (asset in preview.runtimeBundle.assets.sortedBy { it.path }) report.appendLine("$sample-$variant preview:${asset.path} ${sha(asset.bytes)}")
			}
			// An imported CMO3 keeps its own atlas page list and page bindings apart from the packed pages.
			val plainCmo3 = File(out, "$label/$sample-plain/$sample.cmo3").readBytes()
			val (incoming, settings) = io.github.psd2live.core.Cmo3ModelImport.prepare(plainCmo3, io.github.psd2live.core.Cmo3ImportMode.NEW, null,
				plain.config.copy(exportJson = false))
			val dir = File(out, "$label/$sample-imported").apply { deleteRecursively(); mkdirs() }
			val imported = PSD2LivePipeline().run(incoming, sample, dir.toPath(), settings.copy(exportJson = false))
			for (file in imported.exportedFiles.sortedBy { it.path.toString() }) {
				val bytes = file.path.toFile().readBytes()
				val name = dir.toPath().toAbsolutePath().relativize(file.path.toAbsolutePath()).toString().replace('\\', '/')
				report.appendLine("$sample-imported $name ${if (name.endsWith(".cmo3")) "readback=" + cmo3Readback(bytes) else sha(bytes)}")
			}
			for (asset in imported.previewModel.runtimeBundle.assets.sortedBy { it.path }) report.appendLine("$sample-imported preview:${asset.path} ${sha(asset.bytes)}")
		}
		File(out, "$label.txt").writeText(report.toString())
		println(report)
	}

	/** The cmo3's editor GUIDs are random; its read-back rig, lowered to moc3, is not. */
	private fun cmo3Readback(bytes: ByteArray): String = runCatching {
		val source = org.umamo.format.cmo3.Cmo3.read(bytes).root as org.umamo.format.cmo3.model.custom.CModelSource
		val puppet = org.umamo.interop.cmo3.Cmo3Import.fromModelSource(source)
		val bundle = org.umamo.interop.moc3.Moc3Sidecars.bundle(puppet, "readback", pages = emptyList())
		val physics = (source.physicsSettingsSourceSet as? org.umamo.format.cmo3.model.gen.CPhysicsSettingsSourceSet)
			?._sourceCubismPhysics.let { (it as? Iterable<*>)?.count() ?: 0 }
		sha(bundle.files.single { it.name.endsWith(".moc3") }.bytes) + " physics=$physics"
	}.getOrElse { "error:${it.javaClass.simpleName}" }

	private fun sha(bytes: ByteArray) = java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes))
}

