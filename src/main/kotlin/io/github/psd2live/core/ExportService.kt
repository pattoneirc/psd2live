package io.github.psd2live.core

import io.github.psd2live.application.WorkspacePreviewBuilder
import io.github.psd2live.format.compile.Compiler
import io.github.psd2live.format.compile.ExportOptions
import io.github.psd2live.format.compile.ExportRegistry
import io.github.psd2live.format.compile.ExportReport
import io.github.psd2live.format.compile.ExportTarget
import io.github.psd2live.project.ProjectRepository
import io.github.psd2live.targets.cubism.Cmo3Target
import io.github.psd2live.targets.cubism.Moc3Target
import io.github.psd2live.targets.raster.RasterTargets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

/** Every export target this build offers, compiled from one rig through the neutral IR. */
internal object ExportService {
	fun registry(config: PipelineConfig): ExportRegistry = ExportRegistry(listOf(
		Moc3Target,
		Cmo3Target { BezierWarp.configureEditor(it, config.rigEdits) },
		io.github.psd2live.targets.psd.PosedPsdTarget(IrFrameRenderer),
		io.github.psd2live.targets.spine.SpineTarget(IrGeometryEvaluator),
	) + RasterTargets(IrFrameRenderer).all)

	/** Built-in options for [target] from the document's export settings; explicit [settings] win. */
	fun options(target: ExportTarget, baseName: String, config: PipelineConfig, settings: Map<String, String> = emptyMap()): ExportOptions {
		val defaults = when (target.id) {
			Moc3Target.id -> PSD2LivePipeline().moc3ExportOptions(baseName, config).settings
			else -> emptyMap()
		}
		return ExportOptions(baseName, settings = defaults + settings)
	}

	/**
	 * Exports [preview] with [target] into [directory] and writes the loss report beside the files as
	 * `<baseName>.<target>.report.json`. Files are staged and moved in only after the export succeeds.
	 */
	fun export(preview: RigPreviewModel, target: ExportTarget, options: ExportOptions, directory: Path): ExportReport {
		val ir = RigIrCompiler.compile(preview, tileArt = target.id == "cmo3")
		val root = directory.toAbsolutePath().normalize()
		Files.createDirectories(root)
		val stage = Files.createTempDirectory(root, ".export-")
		try {
			val report = Compiler.export(target, ir, options) { path, bytes ->
				val file = stage.resolve(path).normalize()
				require(file.startsWith(stage)) { "Export path escapes the output: $path" }
				Files.createDirectories(file.parent)
				Files.write(file, bytes)
			}
			for (path in report.files) {
				val destination = root.resolve(path)
				Files.createDirectories(destination.parent)
				Files.move(stage.resolve(path), destination, StandardCopyOption.REPLACE_EXISTING)
			}
			Files.writeString(root.resolve("${options.baseName}.${target.id}.report.json"), report.toJson())
			return report
		} finally {
			stage.toFile().deleteRecursively()
		}
	}

	/** The evaluated rig of a `.psd2live` project (its current history state) or of a PSD with default settings. */
	suspend fun load(path: Path): RigPreviewModel {
		val name = path.fileName.toString().lowercase()
		return when {
			name.endsWith(".psd2live") -> ProjectRepository().open(path).use { opened ->
				WorkspacePreviewBuilder().build(opened.history.head().snapshot)
			}
			name.endsWith(".psd") -> PSD2LivePipeline().buildPreview(path)
			else -> throw IllegalArgumentException("Export input must be a .psd2live project or a .psd file: $path")
		}
	}
}
