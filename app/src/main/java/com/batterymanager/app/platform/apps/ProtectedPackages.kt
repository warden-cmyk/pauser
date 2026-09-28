package com.batterymanager.app.platform.apps

import android.content.Context
import android.content.Intent

/**
 * Small, explicit identification of obviously-critical system packages
 * that must never be selectable in Battery Manager (session task
 * "Protected applications"). This is deliberately not a large blacklist
 * and not a new classification architecture - it is the minimum needed to
 * protect the apps the task named, layered on top of the existing
 * [com.batterymanager.app.ui.screens.main.isSelectable] check via its
 * `protectedPackageNames` parameter.
 *
 * Battery Manager's own package is intentionally *not* included here - it
 * is already excluded unconditionally by `isSelectable`'s separate
 * `ownPackageName` parameter, and that guarantee must not depend on this
 * object.
 */
object ProtectedPackages {

    /**
     * Package names for critical system components that are protected
     * regardless of device/OEM label variations. These AOSP package names
     * are stable identifiers (not the app's display label, which can vary
     * or be localized), so matching on them is reliable across devices:
     *
     *  - `com.android.settings` - Android Settings
     *  - `com.android.systemui` - System UI
     *  - `android` - "Android System" (the platform's own package)
     */
    val KNOWN_CRITICAL_PACKAGES: Set<String> = setOf(
        "com.android.settings",
        "com.android.systemui",
        "android"
    )

    /**
     * Identifies the device's current default launcher package using the
     * normal, documented `PackageManager` mechanism: resolving an
     * `ACTION_MAIN` / `CATEGORY_HOME` intent, exactly as the platform
     * itself does to decide what opens on a Home press. No hidden or
     * private APIs.
     *
     * Returns null if this can't be resolved (e.g. no default is set and
     * the platform would show a chooser, or the resolved activity exposes
     * no package info). Per the session task's instruction, an unreliable
     * launcher lookup is not worked around with a fragile heuristic -
     * callers simply fall back to protecting only [KNOWN_CRITICAL_PACKAGES]
     * in that case. See [resolveProtectedPackageNames].
     */
    fun resolveLauncherPackageName(context: Context): String? {
        val homeIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        return try {
            context.packageManager
                .resolveActivity(homeIntent, 0)
                ?.activityInfo
                ?.packageName
        } catch (e: Exception) {
            // Defensive only - PackageManager is not documented to throw
            // here for a plain resolveActivity call, but a lookup failure
            // must never crash discovery or leave the launcher
            // unprotected in a worse way than "not identified".
            null
        }
    }

    /**
     * The full set to pass as `isSelectable`'s `protectedPackageNames`:
     * [KNOWN_CRITICAL_PACKAGES] plus the resolved default launcher
     * package, when one could be identified.
     */
    fun resolveProtectedPackageNames(context: Context): Set<String> =
        KNOWN_CRITICAL_PACKAGES + listOfNotNull(resolveLauncherPackageName(context))
}
