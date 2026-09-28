package com.batterymanager.app.data.selection

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

/**
 * Exercises [DataStoreSelectionStore] against a real
 * `DataStore<Preferences>` backed by a temp file - not a fake - so this
 * covers the actual read/write round trip through Preferences DataStore,
 * not just the [SelectionStore] interface contract. No Android framework
 * or instrumented environment is needed for this:
 * [PreferenceDataStoreFactory.create] is pure JVM code (the
 * `Context.selectionDataStore` delegate in `SelectionDataStore.kt` is the
 * only Android-specific piece, and is intentionally not exercised here).
 */
class DataStoreSelectionStoreTest {

    private lateinit var tempFile: File
    private lateinit var dataStore: DataStore<Preferences>
    private lateinit var store: DataStoreSelectionStore

    @Before
    fun setUp() {
        tempFile = File.createTempFile("selection_test", ".preferences_pb")
        dataStore = PreferenceDataStoreFactory.create(
            produceFile = { tempFile }
        )
        store = DataStoreSelectionStore(dataStore)
    }

    @After
    fun tearDown() {
        tempFile.delete()
    }

    @Test
    fun `getSelectedPackageNames returns empty set when nothing was ever saved`() = runTest {
        assertTrue(store.getSelectedPackageNames().isEmpty())
    }

    @Test
    fun `saveSelectedPackageNames then getSelectedPackageNames round-trips`() = runTest {
        val packages = setOf("com.example.alpha", "com.example.beta")

        store.saveSelectedPackageNames(packages)

        assertEquals(packages, store.getSelectedPackageNames())
    }

    @Test
    fun `a later save fully replaces the previous selection, not merges it`() = runTest {
        store.saveSelectedPackageNames(setOf("com.example.alpha", "com.example.beta"))

        store.saveSelectedPackageNames(setOf("com.example.gamma"))

        assertEquals(setOf("com.example.gamma"), store.getSelectedPackageNames())
    }

    @Test
    fun `saving an empty set clears a previously saved selection`() = runTest {
        store.saveSelectedPackageNames(setOf("com.example.alpha"))

        store.saveSelectedPackageNames(emptySet())

        assertTrue(store.getSelectedPackageNames().isEmpty())
    }

    @Test
    fun `a second store instance backed by the same file sees the saved selection`() = runTest {
        // Simulates a cold start: a fresh DataStoreSelectionStore/DataStore
        // pair pointed at the same on-disk file the previous process
        // instance wrote to.
        store.saveSelectedPackageNames(setOf("com.example.alpha"))

        val reopenedDataStore = PreferenceDataStoreFactory.create(
            produceFile = { tempFile }
        )
        val reopenedStore = DataStoreSelectionStore(reopenedDataStore)

        assertEquals(setOf("com.example.alpha"), reopenedStore.getSelectedPackageNames())
    }
}
