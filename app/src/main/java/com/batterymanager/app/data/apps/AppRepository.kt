package com.batterymanager.app.data.apps

import com.batterymanager.app.platform.apps.LaunchableAppInfo
import com.batterymanager.app.platform.apps.PackageManagerWrapper
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Data-layer façade over app discovery (Architecture doc Component 1's
 * repository side). Classification and protected-app filtering
 * (Components 2-3) are not implemented yet - this repository only
 * discovers and normalizes what [PackageManagerWrapper] returns.
 */
interface AppRepository {
    /**
     * All discovered launchable apps, deduplicated by package name and
     * sorted by display name. Includes apps that cannot currently be
     * launched (see [LaunchableAppInfo.isLaunchable]) - callers decide how
     * to present those, this layer does not silently drop them.
     */
    suspend fun getLaunchableApps(): List<LaunchableAppInfo>
}

class DefaultAppRepository(
    private val packageManagerWrapper: PackageManagerWrapper,
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default
) : AppRepository {

    override suspend fun getLaunchableApps(): List<LaunchableAppInfo> =
        withContext(dispatcher) {
            normalize(packageManagerWrapper.getLaunchableApps())
        }

    /**
     * A single package can expose more than one MAIN/LAUNCHER activity
     * (rare, but allowed by the platform), which would otherwise surface
     * as duplicate rows for the same app. Keep the first entry per package
     * and present a stable, alphabetically sorted list.
     */
    private fun normalize(apps: List<LaunchableAppInfo>): List<LaunchableAppInfo> =
        apps.distinctBy { it.packageName }
            .sortedBy { it.appName.lowercase() }
}
