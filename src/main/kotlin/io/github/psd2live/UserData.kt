package io.github.psd2live

import io.github.psd2live.project.WorkspaceStore
import java.nio.file.Files
import java.nio.file.Path
import java.util.prefs.Preferences

/**
 * What the app keeps for the user outside projects: the workspace store, downloaded runtimes and caches under
 * `~/.psd2live`, and the preference nodes (settings, layout, language, MCP credentials, physics presets).
 * `--clear-user-data` deletes it, which the Windows uninstaller runs when asked to remove user data too.
 */
internal class UserData(
	private val store: Path = WorkspaceStore.defaultRoot(),
	private val folders: List<Path> = listOf(Path.of(System.getProperty("user.home"), ".psd2live")),
	private val clearPreferences: () -> Unit = ::clearPreferenceNodes,
) {
	/** Deletes it all, or nothing while an editor holds the store: returns false then. */
	fun clear(): Boolean {
		if (Files.isDirectory(store)) (AppInstanceLock.acquire(store) ?: return false).close()
		(listOf(store) + folders).forEach(::deleteTree)
		// The store's parent is the app's own folder (LOCALAPPDATA\PSD2Live); it goes once nothing else is in it.
		store.parent?.let { parent -> runCatching { Files.deleteIfExists(parent) } }
		clearPreferences()
		return true
	}

	private fun deleteTree(root: Path) {
		if (!Files.exists(root)) return
		Files.walk(root).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach { runCatching { Files.deleteIfExists(it) } } }
	}

	companion object {
		/** `io/github/psd2live` (package nodes) and the root nodes named `io.github.psd2live.*`. */
		fun clearPreferenceNodes() {
			val root = Preferences.userRoot()
			if (root.nodeExists("io/github/psd2live")) root.node("io/github/psd2live").removeNode()
			root.childrenNames().filter { it == "io.github.psd2live" || it.startsWith("io.github.psd2live.") }
				.forEach { root.node(it).removeNode() }
			root.flush()
		}
	}
}
