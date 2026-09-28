package com.batterymanager.app.data.selection

import android.content.Context
import androidx.datastore.preferences.preferencesDataStore

/**
 * The single `DataStore<Preferences>` instance for the whole app, exposed
 * via the standard `by preferencesDataStore(...)` delegate so there is
 * exactly one file on disk and exactly one in-memory instance, however
 * many times this property is accessed.
 *
 * `"selections"` is the DataStore's file name (`selections.preferences_pb`
 * under the app's `datastore/` directory) - not a preference key. The one
 * key actually stored inside it lives in [DataStoreSelectionStore].
 */
val Context.selectionDataStore by preferencesDataStore(name = "selections")
