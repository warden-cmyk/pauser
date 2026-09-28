package com.batterymanager.app.platform.process

import android.app.ActivityManager
import android.content.Context

/**
 * Thin wrapper around the single documented public API this session uses
 * (Architecture doc Component 8): `ActivityManager.killBackgroundProcesses`.
 * This is meant to be the *only* place in the codebase that calls it, so
 * "how we ask the system to clean up a background process" stays
 * auditable in one file - the same pattern [com.batterymanager.app.platform.apps.PackageManagerWrapper]
 * already established for discovery.
 *
 * No root, no Shizuku, no ADB, no Accessibility service, no hidden/private
 * APIs, no Device Owner APIs. This wrapper's entire surface area is one
 * call to one documented, public, SDK method.
 *
 * IMPORTANT - read before calling: [killBackgroundProcesses] is a
 * fire-and-forget request, not a guarantee. Per the platform's own
 * documented behavior, the system is free to ignore it, and even a
 * process that is killed can be relaunched by the platform (e.g. by a
 * pending broadcast, a foreground service restart policy, or the user
 * reopening the app) immediately afterward. This method does **not**:
 *  - confirm the target process was actually running or actually killed,
 *  - guarantee the app stays stopped after this call returns,
 *  - provide any callback or return value describing what happened.
 *
 * Callers must not describe this action to the user as "Force Stop" or
 * as a guarantee that an app "has been stopped" - see
 * [com.batterymanager.app.domain.processing.CleanupOutcome] for the
 * honest, non-overstating result vocabulary this project uses instead
 * ("Background cleanup requested" / "attempted").
 */
interface ActivityManagerWrapper {
    /**
     * Requests that the system kill background processes belonging to
     * [packageName]. See the interface doc comment above for exactly what
     * this call does and does not guarantee.
     *
     * May throw (e.g. [SecurityException] if the required permission is
     * missing, or another [RuntimeException] from the system service) -
     * callers are responsible for catching per-package failures so that
     * one bad package cannot abort a batch operation. This wrapper itself
     * does not swallow exceptions, so that behavior stays visible to (and
     * decided by) the caller rather than hidden here.
     */
    fun killBackgroundProcesses(packageName: String)
}

class AndroidActivityManagerWrapper(
    private val context: Context
) : ActivityManagerWrapper {

    private val activityManager: ActivityManager
        get() = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager

    override fun killBackgroundProcesses(packageName: String) {
        activityManager.killBackgroundProcesses(packageName)
    }
}
