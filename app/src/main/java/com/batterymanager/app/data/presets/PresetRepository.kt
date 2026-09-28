package com.batterymanager.app.data.presets

import java.util.UUID

/**
 * CRUD over saved [Preset]s. Owns id generation and name handling;
 * [PresetStore] itself only reads/writes the raw list it's given (see its
 * class doc comment).
 *
 * Loading a preset - applying it to the live on-screen selection, with
 * the required installed/safety re-check - is deliberately **not** this
 * repository's responsibility. That crosses into
 * [com.batterymanager.app.ui.screens.main.MainViewModel] territory, which
 * is the only place that knows the current session's `selectableApps`
 * set to re-check against. See
 * [com.batterymanager.app.ui.screens.main.MainViewModel.loadPreset].
 */
interface PresetRepository {

    /** Every saved preset, sorted by name (case-insensitive) for a stable display order. */
    suspend fun getPresets(): List<Preset>

    /**
     * Saves a new preset named [name] containing [packageNames] and
     * returns it, or returns `null` and saves nothing if [name] is blank
     * after trimming. Does not check [packageNames] for duplicates or
     * validity - the caller (`MainViewModel`) is expected to only ever
     * pass a subset of that session's `selectableApps`, the same
     * defense-in-depth pattern used elsewhere in this codebase.
     */
    suspend fun createPreset(name: String, packageNames: Set<String>): Preset?

    /** Removes the preset with id [presetId], if one exists. A no-op otherwise. */
    suspend fun deletePreset(presetId: String)
}

class DefaultPresetRepository(
    private val presetStore: PresetStore
) : PresetRepository {

    override suspend fun getPresets(): List<Preset> =
        presetStore.getPresets().sortedBy { it.name.lowercase() }

    override suspend fun createPreset(name: String, packageNames: Set<String>): Preset? {
        val trimmedName = name.trim()
        if (trimmedName.isEmpty()) return null

        val preset = Preset(
            id = UUID.randomUUID().toString(),
            name = trimmedName,
            packageNames = packageNames
        )
        val current = presetStore.getPresets()
        presetStore.savePresets(current + preset)
        return preset
    }

    override suspend fun deletePreset(presetId: String) {
        val current = presetStore.getPresets()
        presetStore.savePresets(current.filterNot { it.id == presetId })
    }
}
