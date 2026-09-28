package com.batterymanager.app.data.presets

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** In-memory [PresetStore] test double - mirrors [FakeSelectionStore]'s style in `MainViewModelTest`. */
private class FakePresetStore(initial: List<Preset> = emptyList()) : PresetStore {
    var saved: List<Preset> = initial
        private set

    override suspend fun getPresets(): List<Preset> = saved

    override suspend fun savePresets(presets: List<Preset>) {
        saved = presets
    }
}

class DefaultPresetRepositoryTest {

    @Test
    fun `getPresets returns an empty list when nothing was ever saved`() = runTest {
        val repository = DefaultPresetRepository(FakePresetStore())

        assertTrue(repository.getPresets().isEmpty())
    }

    @Test
    fun `createPreset saves and returns a new preset with the given name and packages`() = runTest {
        val store = FakePresetStore()
        val repository = DefaultPresetRepository(store)

        val created = repository.createPreset("Work", setOf("com.example.alpha", "com.example.beta"))

        assertNotNull(created)
        assertEquals("Work", created!!.name)
        assertEquals(setOf("com.example.alpha", "com.example.beta"), created.packageNames)
        assertEquals(listOf(created), store.saved)
    }

    @Test
    fun `createPreset generates a distinct id for each new preset`() = runTest {
        val repository = DefaultPresetRepository(FakePresetStore())

        val first = repository.createPreset("Work", emptySet())
        val second = repository.createPreset("Sleep", emptySet())

        assertNotNull(first)
        assertNotNull(second)
        assertTrue(first!!.id != second!!.id)
    }

    @Test
    fun `createPreset trims leading and trailing whitespace from the name`() = runTest {
        val repository = DefaultPresetRepository(FakePresetStore())

        val created = repository.createPreset("  Work  ", emptySet())

        assertEquals("Work", created!!.name)
    }

    @Test
    fun `createPreset rejects a blank name and saves nothing`() = runTest {
        val store = FakePresetStore()
        val repository = DefaultPresetRepository(store)

        val created = repository.createPreset("   ", setOf("com.example.alpha"))

        assertNull(created)
        assertTrue(store.saved.isEmpty())
    }

    @Test
    fun `createPreset does not remove existing presets`() = runTest {
        val existing = Preset(id = "p1", name = "Sleep", packageNames = emptySet())
        val store = FakePresetStore(initial = listOf(existing))
        val repository = DefaultPresetRepository(store)

        repository.createPreset("Work", setOf("com.example.alpha"))

        assertEquals(2, store.saved.size)
        assertTrue(existing in store.saved)
    }

    @Test
    fun `createPreset allows an empty package set`() = runTest {
        val repository = DefaultPresetRepository(FakePresetStore())

        val created = repository.createPreset("Custom", emptySet())

        assertNotNull(created)
        assertTrue(created!!.packageNames.isEmpty())
    }

    @Test
    fun `getPresets returns presets sorted by name, case-insensitively`() = runTest {
        val store = FakePresetStore(
            initial = listOf(
                Preset(id = "p1", name = "work", packageNames = emptySet()),
                Preset(id = "p2", name = "Battery Saver", packageNames = emptySet()),
                Preset(id = "p3", name = "Sleep", packageNames = emptySet())
            )
        )
        val repository = DefaultPresetRepository(store)

        val names = repository.getPresets().map { it.name }

        assertEquals(listOf("Battery Saver", "Sleep", "work"), names)
    }

    @Test
    fun `deletePreset removes the matching preset and leaves the others`() = runTest {
        val work = Preset(id = "p1", name = "Work", packageNames = emptySet())
        val sleep = Preset(id = "p2", name = "Sleep", packageNames = emptySet())
        val store = FakePresetStore(initial = listOf(work, sleep))
        val repository = DefaultPresetRepository(store)

        repository.deletePreset("p1")

        assertEquals(listOf(sleep), store.saved)
    }

    @Test
    fun `deletePreset with an unknown id is a no-op`() = runTest {
        val work = Preset(id = "p1", name = "Work", packageNames = emptySet())
        val store = FakePresetStore(initial = listOf(work))
        val repository = DefaultPresetRepository(store)

        repository.deletePreset("does-not-exist")

        assertEquals(listOf(work), store.saved)
    }
}
