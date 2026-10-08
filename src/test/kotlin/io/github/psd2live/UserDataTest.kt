package io.github.psd2live

import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.writeText
import kotlin.test.*

class UserDataTest {
	@TempDir lateinit var root: Path

	@Test fun clearingDeletesTheStoreItsEmptyFolderTheDataFoldersAndThePreferences() {
		val app = root.resolve("PSD2Live")
		val store = app.resolve("agent-workspaces").also { Files.createDirectories(it.resolve("w1/history")) }
		store.resolve("w1/history/node.json").writeText("{}")
		val dot = root.resolve(".psd2live").also { Files.createDirectories(it.resolve("cache")) }
		dot.resolve("cache/x.bin").writeText("x")
		var preferences = 0
		assertTrue(UserData(store, listOf(dot)) { preferences++ }.clear())
		assertFalse(app.exists()); assertFalse(dot.exists())
		assertEquals(1, preferences)
	}

	@Test fun theAppsFolderStaysWhenItHoldsMoreThanTheStore() {
		val app = root.resolve("PSD2Live")
		val store = app.resolve("agent-workspaces").also(Files::createDirectories)
		app.resolve("PSD2Live.exe").writeText("installed here")
		assertTrue(UserData(store, emptyList()) {}.clear())
		assertFalse(store.exists()); assertTrue(app.resolve("PSD2Live.exe").exists())
	}

	@Test fun nothingIsDeletedWhileAnEditorHoldsTheStore() {
		val store = root.resolve("PSD2Live/agent-workspaces")
		val dot = root.resolve(".psd2live").also(Files::createDirectories)
		var preferences = 0
		AppInstanceLock.acquire(store)!!.use {
			assertFalse(UserData(store, listOf(dot)) { preferences++ }.clear())
		}
		assertTrue(store.exists()); assertTrue(dot.exists()); assertEquals(0, preferences)
	}
}
