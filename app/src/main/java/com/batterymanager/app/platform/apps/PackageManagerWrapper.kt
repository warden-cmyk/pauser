package com.batterymanager.app.platform.apps

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo

/**
 * Thin wrapper around [PackageManager]'s app-discovery APIs (Architecture
 * doc Component 1). This is meant to be the *only* place in the codebase
 * that calls `queryIntentActivities`/`getLaunchIntentForPackage`, so that
 * "how we discover apps" stays auditable in one file.
 *
 * Uses only the documented `<queries>`-scoped MAIN/LAUNCHER query - no
 * `QUERY_ALL_PACKAGES`, no hidden or private APIs.
 */
interface PackageManagerWrapper {
    /**
     * Returns every installed app that exposes a standard launcher entry
     * point, one entry per package. Order is unspecified; callers that need
     * a stable order (e.g. alphabetical) should sort the result themselves.
     */
    fun getLaunchableApps(): List<LaunchableAppInfo>
}

class AndroidPackageManagerWrapper(
    private val context: Context
) : PackageManagerWrapper {

    private val packageManager: PackageManager
        get() = context.packageManager

    override fun getLaunchableApps(): List<LaunchableAppInfo> {
        val launcherIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)

        val resolveInfos: List<ResolveInfo> =
            packageManager.queryIntentActivities(launcherIntent, 0)

        return resolveInfos.mapNotNull { toLaunchableAppInfo(it) }
    }

    private fun toLaunchableAppInfo(resolveInfo: ResolveInfo): LaunchableAppInfo? {
        val packageName = resolveInfo.activityInfo?.packageName ?: return null

        return try {
            val appName = resolveInfo.loadLabel(packageManager).toString()
            val icon = resolveInfo.loadIcon(packageManager)
            // getLaunchIntentForPackage can legitimately return null (e.g.
            // the package was uninstalled between the query above and this
            // call, or its launcher activity is disabled) - that is not an
            // error, it's handled by LaunchableAppInfo.isLaunchable.
            val launchIntent = packageManager.getLaunchIntentForPackage(packageName)

            LaunchableAppInfo(
                packageName = packageName,
                appName = appName,
                icon = icon,
                launchIntent = launchIntent
            )
        } catch (e: PackageManager.NameNotFoundException) {
            // App vanished between query and label/icon lookup. Skip it
            // rather than crash - there is nothing useful to show for it.
            null
        }
    }
}
