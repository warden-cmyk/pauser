package com.batterymanager.app.platform.apps

import android.content.Intent
import android.graphics.drawable.Drawable

/**
 * A single app discovered via [PackageManagerWrapper].
 *
 * This is the raw discovery-layer shape (Architecture doc Component 1). It
 * intentionally does not include classification or protected/eligible
 * status yet (Architecture doc Components 2-3) - those are built in a
 * later session on top of this.
 *
 * @property packageName Unique package identifier, e.g. "com.example.app".
 * @property appName Human-readable label to display to the user.
 * @property icon The app's launcher icon.
 * @property launchIntent An intent that can be used to (re-)launch this
 *   app's main/launcher activity, or null when the system could not supply
 *   one for this package. See [isLaunchable].
 */
data class LaunchableAppInfo(
    val packageName: String,
    val appName: String,
    val icon: Drawable,
    val launchIntent: Intent?
) {
    /**
     * Whether this app can actually be launched right now. A package can
     * appear in discovery results (it exposes a MAIN/LAUNCHER activity) and
     * still have no resolvable launch intent in edge cases - e.g. it was
     * uninstalled between the discovery query and this lookup, or its
     * launcher activity is disabled/restricted for the current user
     * profile. Callers should treat such apps as present-but-unlaunchable
     * (PRD §14's "no reliable is-it-stopped-style guarantee" principle
     * applies symmetrically here: don't claim launchability the platform
     * didn't confirm).
     */
    val isLaunchable: Boolean
        get() = launchIntent != null
}
