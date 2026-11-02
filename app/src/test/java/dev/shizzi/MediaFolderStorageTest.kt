package dev.shizzi

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaFolderStorageTest {
    private fun context(prefs: MemoryPreferences = MemoryPreferences()): Context =
        object : ContextWrapper(null) {
            override fun getSharedPreferences(name: String, mode: Int): SharedPreferences =
                object : SharedPreferences by prefs {
                    override fun edit(): SharedPreferences.Editor {
                        val editor = prefs.edit()
                        return object : SharedPreferences.Editor by editor {
                            override fun apply() {
                                check(editor.commit())
                            }
                        }
                    }
                }
        }

    private fun folder(index: Int) = MediaFolderConfig(
        id = "folder-$index",
        name = "Dossier $index",
        kind = MediaKind.entries[(index - 1) % MediaKind.entries.size],
        treeUri = "content://test/folder-$index",
        enabled = index % 5 != 0,
        allowedAccounts = if (index % 2 == 0) setOf("1001", "1003") else emptySet(),
    )

    @Test
    fun saveAndReloadKeepTwentyFiftyAndHundredFoldersWithTheirPermissions() {
        for (count in listOf(20, 50, 100)) {
            val prefs = MemoryPreferences()
            val source = (1..count).map(::folder)
            MediaFolderStore.save(context(prefs), source)
            val reloaded = MediaFolderStore.load(context(prefs))
            assertEquals(source, reloaded)
            assertEquals(source, MediaFolderStore.decodeSnapshot(
                MediaFolderStore.encodeSnapshot(reloaded)))
            val privateFolder = reloaded.last { it.enabled && it.allowedAccounts.isNotEmpty() }
            assertTrue(MediaFolderStore.canAccessSnapshot(reloaded, privateFolder.id, "1001"))
            assertFalse(MediaFolderStore.canAccessSnapshot(reloaded, privateFolder.id, "1002"))
            assertFalse(MediaFolderStore.canAccessSnapshot(reloaded, "folder-5", "1001"))
        }
    }

    @Test
    fun addingEditingAndRemovingPastTwentyNeverTruncatesOtherFolders() {
        val context = context()
        val source = (1..30).map(::folder)
        source.forEach { MediaFolderStore.upsert(context, it) }
        assertEquals(source, MediaFolderStore.load(context))

        val updated = source[27].copy(name = "Séries privées", allowedAccounts = setOf("1002"))
        MediaFolderStore.upsert(context, updated)
        MediaFolderStore.remove(context, "folder-3")
        val expected = source.filterNot { it.id == "folder-3" }
            .map { if (it.id == updated.id) updated else it }
        assertEquals(expected, MediaFolderStore.load(context))
        assertTrue(MediaFolderStore.canAccess(context, updated.id, "1002"))
        assertFalse(MediaFolderStore.canAccess(context, updated.id, "1001"))
    }

    @Test
    fun existingTenFolderPreferencesRemainReadableAndCanGrow() {
        val context = context()
        val legacy = (1..10).map(::folder)
        context.getSharedPreferences("shizzi_media", Context.MODE_PRIVATE).edit()
            .putString("folders_v2", MediaFolderStore.encodeSnapshot(legacy)).apply()
        assertEquals(legacy, MediaFolderStore.load(context))
        MediaFolderStore.upsert(context, folder(11))
        assertEquals(legacy + folder(11), MediaFolderStore.load(context))
    }

    @Test
    fun remoteAdminCreatesAndUpdatesFoldersBeyondTwenty() {
        val context = context()
        val store = CybercafeStore(MemoryPreferences())
        fun command(action: String, params: JSONObject): AdminCommandResult =
            processRemoteAdminCommand(
                LiveAdminCommand("media-test", "192.168.7.2", action, params.toString(), 1L),
                store, 1L, context,
            )

        for (index in 1..30) {
            val result = command("media.folder.create", JSONObject()
                .put("name", "Dossier $index").put("kind", "series"))
            assertTrue("folder $index: ${result.message}", result.success)
        }
        val created = MediaFolderStore.load(context)
        assertEquals(30, created.size)
        assertEquals(30, created.map { it.id }.toSet().size)
        val last = created.last()
        val result = command("media.folder.update", JSONObject()
            .put("id", last.id).put("name", "Séries privées")
            .put("allowedAccounts", org.json.JSONArray(listOf("1002"))))
        assertTrue(result.message, result.success)
        val reloaded = MediaFolderStore.load(context)
        assertEquals(created.dropLast(1), reloaded.dropLast(1))
        assertEquals("Séries privées", reloaded.last().name)
        assertTrue(MediaFolderStore.canAccess(context, last.id, "1002"))
        assertFalse(MediaFolderStore.canAccess(context, last.id, "1001"))
    }
}
