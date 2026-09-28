package com.batterymanager.app.ui.screens.main

import com.batterymanager.app.platform.apps.LaunchableAppInfo

/**
 * Whether [app] may be checked/unchecked in the selection UI.
 *
 * Three conditions, all enforced here so there is exactly one place that
 * can make an app selectable:
 *
 *  - It must be launchable ([LaunchableAppInfo.isLaunchable]). This remains
 *    the base "eligible for management" signal. The Architecture doc's
 *    fuller dedicated protected/system/critical classification (Components
 *    2 - Classification - and 3 - Protected-app filtering) is still not
 *    built (see PROJECT_STATE.md) - what exists below is a small, explicit
 *    name-based protection list, not that classification system.
 *  - It must not be Battery Manager's own package ([ownPackageName],
 *    normally the host app's `Context.getPackageName()`). The app must
 *    never be able to select/manage itself, independent of any other
 *    protection.
 *  - It must not be in [protectedPackageNames] - obvious critical system
 *    packages (Settings, System UI, "Android System", and the device's
 *    default launcher when it can be identified) that must never be
 *    selectable, never included in Select All, and never enter the Guided
 *    Settings workflow. See [com.batterymanager.app.platform.apps.ProtectedPackages]
 *    for how this set is built. Defaults to empty so existing call sites
 *    and tests that don't pass it are unaffected.
 */
fun isSelectable(
    app: LaunchableAppInfo,
    ownPackageName: String,
    protectedPackageNames: Set<String> = emptySet()
): Boolean =
    app.isLaunchable &&
        app.packageName != ownPackageName &&
        app.packageName !in protectedPackageNames
