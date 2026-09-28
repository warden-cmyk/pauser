package com.batterymanager.app.data.presets

/**
 * Persists the full list of saved [Preset]s across cold starts.
 *
 * Deliberately dumb, same philosophy as
 * [com.batterymanager.app.data.selection.SelectionStore]: it reads back
 * exactly what was last saved and saves exactly what it's given. It does
 * not generate ids, trim/validate names, or decide which package names
 * inside a preset are still safe to load - id/name handling is
 * [PresetRepository]'s job, and the installed/safety re-check at load
 * time is [com.batterymanager.app.ui.screens.main.MainViewModel]'s job
 * (see [Preset]'s doc comment). The store's only responsibility is
 * getting bytes on and off disk.
 */
interface PresetStore {

    /** Every saved preset, or an empty list if none have ever been saved. */
    suspend fun getPresets(): List<Preset>

    /**
     * Overwrites the persisted presets with exactly [presets] (not a
     * delta) - callers pass the full current list each time, the same
     * whole-collection-per-write pattern
     * [com.batterymanager.app.data.selection.SelectionStore.saveSelectedPackageNames]
     * already uses.
     */
    suspend fun savePresets(presets: List<Preset>)
}
