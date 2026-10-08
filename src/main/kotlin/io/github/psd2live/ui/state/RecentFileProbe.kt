package io.github.psd2live.ui.state

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap

/**
 * Whether recent files still exist, checked off the UI thread: a path on an unplugged drive or an
 * offline share can stall the check for seconds. The last answer per path is kept for the session,
 * so the start screen and the File menu show what is known at once and settle when the check returns.
 */
object RecentFileProbe {
	private val seen = ConcurrentHashMap<String, Boolean>()

	fun lastSeen(path: String): Boolean? = seen[path]

	fun check(path: String): Boolean =
		runCatching { Files.isRegularFile(Path.of(path)) }.getOrDefault(false).also { seen[path] = it }
}

/**
 * The recent files of [paths] that exist, or null until every path has been checked once.
 * Each change of [paths] or [refreshKey] checks them again, every path on its own IO task.
 */
@Composable
fun rememberExistingRecentFiles(paths: List<String>, refreshKey: Any? = null): List<RecentFile>? {
	val files = remember(paths) { recentFilesFrom(paths) }
	var existing by remember(files) { mutableStateOf(known(files)) }
	LaunchedEffect(files, refreshKey) {
		val present = coroutineScope {
			files.map { file -> async(Dispatchers.IO) { RecentFileProbe.check(file.path) } }.awaitAll()
		}
		existing = files.filterIndexed { index, _ -> present[index] }
	}
	return existing
}

private fun known(files: List<RecentFile>): List<RecentFile>? {
	val seen = files.map { RecentFileProbe.lastSeen(it.path) }
	if (seen.any { it == null }) return null
	return files.filterIndexed { index, _ -> seen[index] == true }
}
