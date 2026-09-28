package com.batterymanager.app.data.presets

import android.content.Context
import androidx.datastore.preferences.preferencesDataStore

/**
 * The single `DataStore<Preferences>` instance backing saved presets,
 * exposed via the standard `by preferencesDataStore(...)` delegate so
 * there is exactly one file on disk and exactly one in-memory instance,
 * however many times this property is accessed - mirrors
 * [com.batterymanager.app.data.selection.selectionDataStore] (Session 6).
 *
 * Kept in its own file (`presets.preferences_pb`), separate from the
 * current-session selection's file, rather than adding a second key to
 * that existing DataStore: presets and the live selection are saved and
 * cleared independently of one another (deleting a preset must never
 * touch the current on-screen selection, and vice versa), so giving them
 * separate files keeps that independence structural rather than relying
 * on remembering to use different keys inside one shared file.
 */
val Context.presetDataStore by preferencesDataStore(name = "presets")
