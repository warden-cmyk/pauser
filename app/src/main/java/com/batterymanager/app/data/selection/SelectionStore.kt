package com.batterymanager.app.data.selection

/**
 * Persists the user's application-selection checkboxes across cold starts.
 *
 * This is the only thing this store is responsible for: reading back
 * exactly what was last saved, and saving exactly what it's given. It does
 * **not** decide which package names are safe to restore - that's
 * [MainViewModel]'s job (via the same [com.batterymanager.app.ui.screens.main.isSelectable]
 * check/`selectableApps` set already used for manual selection and Select
 * All), so a persisted set can freely contain package names that are no
 * longer valid (app uninstalled), no longer eligible, or were saved by a
 * previous, less careful version of this app - the caller is expected to
 * filter, never the store.
 */
interface SelectionStore {

    /**
     * The package names selected as of the last [saveSelectedPackageNames]
     * call, or an empty set if nothing has ever been saved. Never throws
     * for "nothing saved yet" - that's a normal, empty result.
     */
    suspend fun getSelectedPackageNames(): Set<String>

    /**
     * Overwrites the persisted selection with exactly [packageNames].
     * Callers pass the full current selection each time (not a delta) -
     * this mirrors how [com.batterymanager.app.ui.screens.main.MainUiState.Success.selectedPackageNames]
     * already works in memory.
     */
    suspend fun saveSelectedPackageNames(packageNames: Set<String>)
}
