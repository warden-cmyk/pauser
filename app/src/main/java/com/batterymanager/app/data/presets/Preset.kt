package com.batterymanager.app.data.presets

/**
 * A named, user-defined set of application package names, saved so a
 * previously-made selection can be re-applied later without re-checking
 * every box by hand.
 *
 * [packageNames] is stored exactly as saved - it is not filtered here.
 * Whether every name in it is still safe to actually select right now
 * (still installed, still not Battery Manager itself, still not
 * protected/system-critical) is re-checked at *load* time against the
 * current selectable-apps set, not at save time or read time - see
 * [com.batterymanager.app.ui.screens.main.MainViewModel.loadPreset]. A
 * [Preset] on disk can therefore legitimately reference a package that is
 * no longer safe to restore; that is expected, not an error.
 */
data class Preset(
    val id: String,
    val name: String,
    val packageNames: Set<String>
)
