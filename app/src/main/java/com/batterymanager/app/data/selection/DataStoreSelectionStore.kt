package com.batterymanager.app.data.selection

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringSetPreferencesKey
import kotlinx.coroutines.flow.first

/**
 * [SelectionStore] backed by Jetpack Preferences DataStore.
 *
 * The persisted data is a single `Set<String>` of package names - simple
 * key-value data, not structured/relational data - so per the Architecture
 * doc's Component 6 guidance (Room for structured data like presets and
 * run history; DataStore for simple settings/key-value data) DataStore is
 * the right fit and Room is not introduced. There is exactly one key in
 * this store; nothing else is written here.
 *
 * The `DataStore<Preferences>` instance itself is constructed by the
 * caller (see `MainActivity`'s `Context.selectionDataStore` delegate) -
 * this class does not own a [android.content.Context] reference, so it
 * stays trivially fakeable in JVM unit tests.
 */
class DataStoreSelectionStore(
    private val dataStore: DataStore<Preferences>
) : SelectionStore {

    override suspend fun getSelectedPackageNames(): Set<String> =
        dataStore.data.first()[SELECTED_PACKAGE_NAMES_KEY] ?: emptySet()

    override suspend fun saveSelectedPackageNames(packageNames: Set<String>) {
        dataStore.edit { preferences ->
            preferences[SELECTED_PACKAGE_NAMES_KEY] = packageNames
        }
    }

    private companion object {
        val SELECTED_PACKAGE_NAMES_KEY = stringSetPreferencesKey("selected_package_names")
    }
}
