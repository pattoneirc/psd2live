package io.github.psd2live

import io.github.psd2live.core.ExportService
import io.github.psd2live.core.PipelineConfig
import kotlinx.coroutines.runBlocking
import java.nio.file.Path

/**
 * `psd2live export <input> --target <id> [--output <dir>] [--name <base>] [--set key=value]...`
 * `psd2live targets`
 *
 * Exports a `.psd2live` project (its current state) or a PSD through any export target, writing the files
 * and a loss report. Exit status: 0 on success, 1 on failure, 2 on a usage error.
 */
internal object ExportCli {
	private const val USAGE = """Usage:
  psd2live export <input.psd2live|input.psd> --target <id> [--output <dir>] [--name <base>] [--set key=value]...
  psd2live targets

Writes the target's files and <base>.<target>.report.json, which lists what the target could not keep."""

	fun run(arguments: List<String>): Int {
		val registry = ExportService.registry(PipelineConfig())
		if (arguments.first() == "targets") {
			registry.targets.forEach { println("%-14s %-7s %s".format(it.id, it.family.name.lowercase(), it.description)) }
			return 0
		}
		val rest = arguments.drop(1)
		if (rest.isEmpty() || rest.any { it == "--help" || it == "-h" }) { println(USAGE); return if (rest.isEmpty()) 2 else 0 }
		var input: Path? = null
		var targetId: String? = null
		var output: Path? = null
		var name: String? = null
		val settings = LinkedHashMap<String, String>()
		var index = 0
		fun value(flag: String): String = rest.getOrNull(++index) ?: throw IllegalArgumentException("$flag needs a value")
		try {
			while (index < rest.size) {
				when (val argument = rest[index]) {
					"--target" -> targetId = value(argument)
					"--output" -> output = Path.of(value(argument))
					"--name" -> name = value(argument)
					"--set" -> value(argument).let { pair ->
						val key = pair.substringBefore('=', "")
						require(key.isNotBlank() && '=' in pair) { "--set expects key=value: $pair" }
						settings[key] = pair.substringAfter('=')
					}
					else -> {
						require(!argument.startsWith("--") && input == null) { "Unexpected argument: $argument" }
						input = Path.of(argument)
					}
				}
				index++
			}
			requireNotNull(input) { "An input file is required" }
			requireNotNull(targetId) { "--target is required (see `psd2live targets`)" }
		} catch (failure: IllegalArgumentException) {
			System.err.println(failure.message); System.err.println(USAGE); return 2
		}
		return try {
			val source = input!!.toAbsolutePath()
			val preview = runBlocking { ExportService.load(source) }
			val target = ExportService.registry(preview.config)[targetId!!]
			val baseName = name ?: source.fileName.toString().substringBeforeLast('.').ifBlank { "model" }
			val directory = output ?: source.parent.resolve("${baseName}-${target.id}")
			val report = ExportService.export(preview, target, ExportService.options(target, baseName, preview.config, settings), directory)
			println("Exported ${report.files.size} files to ${directory.toAbsolutePath()} (compiler ${report.compiler})")
			report.files.forEach { println("  $it") }
			if (report.losses.isNotEmpty()) {
				println("${report.losses.size} losses (see ${baseName}.${target.id}.report.json):")
				report.losses.groupBy { it.feature to it.handling }.forEach { (key, entries) ->
					println("  ${key.first.name.lowercase()} ${key.second.name.lowercase()}: ${entries.size}  e.g. ${entries.first().note}")
				}
			}
			0
		} catch (failure: Exception) {
			System.err.println("Export failed: ${failure.message ?: failure.javaClass.simpleName}")
			1
		}
	}
}
