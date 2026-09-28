package com.batterymanager.app.data.presets

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringSetPreferencesKey
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

/**
 * Exercises [DataStorePresetStore] against a real `DataStore<Preferences>`
 * backed by a temp file - not a fake - the same approach
 * `DataStoreSelectionStoreTest` (Session 6) already uses, so this covers
 * the actual read/write round trip through Preferences DataStore
 * including the encode/decode step, not just the [PresetStore] interface
 * contract.
 */
class DataStorePresetStoreTest {

    private lateinit var tempFile: File
    private lateinit var dataStore: DataStore<Preferences>
    private lateinit var store: DataStorePresetStore

    @Before
    fun setUp() {
        tempFile = File.createTempFile("preset_test", ".preferences_pb")
        dataStore = PreferenceDataStoreFactory.create(
            produceFile = { tempFile }
        )
        store = DataStorePresetStore(dataStore)
    }

    @After
    fun tearDown() {
        tempFile.delete()
    }

    @Test
    fun `getPresets returns an empty list when nothing was ever saved`() = runTest {
        assertTrue(store.getPresets().isEmpty())
    }

    @Test
    fun `savePresets then getPresets round-trips a single preset`() = runTest {
        val preset = Preset(
            id = "p1",
            name = "Work",
            packageNames = setOf("com.example.alpha", "com.example.beta")
        )

        store.savePresets(listOf(preset))

        assertEquals(listOf(preset), store.getPresets())
    }

    @Test
    fun `savePresets then getPresets round-trips multiple presets`() = runTest {
        val work = Preset(id = "p1", name = "Work", packageNames = setOf("com.example.alpha"))
        val sleep = Preset(id = "p2", name = "Sleep", packageNames = setOf("com.example.beta"))

        store.savePresets(listOf(work, sleep))

        assertEquals(setOf(work, sleep), store.getPresets().toSet())
    }

    @Test
    fun `a preset with an empty package set round-trips correctly`() = runTest {
        val emptyPreset = Preset(id = "p1", name = "Custom", packageNames = emptySet())

        store.savePresets(listOf(emptyPreset))

        assertEquals(listOf(emptyPreset), store.getPresets())
    }

    @Test
    fun `a later save fully replaces the previous list, not merges it`() = runTest {
        val work = Preset(id = "p1", name = "Work", packageNames = setOf("com.example.alpha"))
        store.savePresets(listOf(work))

        val sleep = Preset(id = "p2", name = "Sleep", packageNames = setOf("com.example.beta"))
        store.savePresets(listOf(sleep))

        assertEquals(listOf(sleep), store.getPresets())
    }

    @Test
    fun `saving an empty list clears previously saved presets`() = runTest {
        store.savePresets(listOf(Preset(id = "p1", name = "Work", packageNames = emptySet())))

        store.savePresets(emptyList())

        assertTrue(store.getPresets().isEmpty())
    }

    @Test
    fun `a second store instance backed by the same file sees the saved presets`() = runTest {
        // Simulates a cold start: a fresh DataStorePresetStore/DataStore
        // pair pointed at the same on-disk file the previous process
        // instance wrote to.
        val preset = Preset(id = "p1", name = "Work", packageNames = setOf("com.example.alpha"))
        store.savePresets(listOf(preset))

        val reopenedDataStore = PreferenceDataStoreFactory.create(
            produceFile = { tempFile }
        )
        val reopenedStore = DataStorePresetStore(reopenedDataStore)

        assertEquals(listOf(preset), reopenedStore.getPresets())
    }

    @Test
    fun `a malformed entry written outside this class is dropped rather than crashing the read`() = runTest {
        // Simulates hand-edited/corrupted data: a raw string that doesn't
        // match the id/name/packages encoding at all, written directly
        // into the same underlying Preferences key this store uses.
        val presetsKey = stringSetPreferencesKey("saved_presets")
        dataStore.edit { preferences ->
            preferences[presetsKey] = setOf("not-a-valid-encoded-preset")
        }

        assertTrue(store.getPresets().isEmpty())
    }

    @Test
    fun `a malformed entry is dropped while valid entries alongside it are kept`() = runTest {
        val presetsKey = stringSetPreferencesKey("saved_presets")
        val validPreset = Preset(id = "p1", name = "Work", packageNames = setOf("com.example.alpha"))
        store.savePresets(listOf(validPreset))
        // Add a corrupt entry directly, alongside the valid one just saved.
        dataStore.edit { preferences ->
            val current = preferences[presetsKey] ?: emptySet()
            preferences[presetsKey] = current + "garbage"
        }

        assertEquals(listOf(validPreset), store.getPresets())
    }
}
