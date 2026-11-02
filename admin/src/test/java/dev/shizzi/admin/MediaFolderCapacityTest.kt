package dev.shizzi.admin

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaFolderCapacityTest {
    @Test
    fun newRouterAllowsMoreThanTwentyFoldersAndDisplaysNoLimit() {
        val capacity = MediaFolderCapacity.from(JSONObject()
            .put("unlimitedFolders", true).put("maxFolders", Int.MAX_VALUE))
        for (count in listOf(0, 10, 20, 50, 100, 1_000)) {
            assertTrue(capacity.canAdd(count))
            assertEquals("Dossiers : $count · sans limite", capacity.label(count))
        }
    }

    @Test
    fun unlimitedFlagDoesNotDependOnLegacyIntegerHint() {
        val capacity = MediaFolderCapacity.from(JSONObject().put("unlimitedFolders", true))
        assertTrue(capacity.canAdd(100))
        assertEquals("Dossiers : 100 · sans limite", capacity.label(100))
    }

    @Test
    fun integerHintForOlderClientsAlsoMeansUnlimited() {
        val capacity = MediaFolderCapacity.from(JSONObject().put("maxFolders", Int.MAX_VALUE))
        assertTrue(capacity.canAdd(100))
        assertEquals("Dossiers : 100 · sans limite", capacity.label(100))
    }

    @Test
    fun olderRoutersKeepTheirAdvertisedTenOrTwentyFolderLimit() {
        for (limit in listOf(10, 20)) {
            val capacity = MediaFolderCapacity.from(JSONObject().put("maxFolders", limit))
            assertTrue(capacity.canAdd(limit - 1))
            assertFalse(capacity.canAdd(limit))
            assertFalse(capacity.canAdd(limit + 1))
            assertEquals("Dossiers : $limit/$limit", capacity.label(limit))
        }
    }

    @Test
    fun missingCapabilityRetainsLegacyTenFolderBehavior() {
        val capacity = MediaFolderCapacity.from(JSONObject())
        assertTrue(capacity.canAdd(9))
        assertFalse(capacity.canAdd(10))
        assertEquals("Dossiers : 10/10", capacity.label(10))
    }
}
