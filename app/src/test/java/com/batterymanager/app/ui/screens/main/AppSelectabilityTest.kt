package com.batterymanager.app.ui.screens.main

import com.batterymanager.app.platform.apps.FakeDrawable
import com.batterymanager.app.platform.apps.LaunchableAppInfo
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

private const val OWN_PACKAGE_NAME = "com.batterymanager.app"

private fun app(packageName: String, launchable: Boolean = true) = LaunchableAppInfo(
    packageName = packageName,
    appName = packageName,
    icon = FakeDrawable(),
    launchIntent = if (launchable) android.content.Intent() else null
)

class AppSelectabilityTest {

    @Test
    fun `a launchable app that is not the host app is selectable`() {
        assertTrue(isSelectable(app("com.example.other"), ownPackageName = OWN_PACKAGE_NAME))
    }

    @Test
    fun `the host app's own package is never selectable`() {
        assertFalse(isSelectable(app(OWN_PACKAGE_NAME), ownPackageName = OWN_PACKAGE_NAME))
    }

    @Test
    fun `a non-launchable app is not selectable`() {
        assertFalse(
            isSelectable(app("com.example.other", launchable = false), ownPackageName = OWN_PACKAGE_NAME)
        )
    }

    @Test
    fun `a protected package is not selectable even if launchable and not the host app`() {
        assertFalse(
            isSelectable(
                app("com.android.settings"),
                ownPackageName = OWN_PACKAGE_NAME,
                protectedPackageNames = setOf("com.android.settings", "com.android.systemui", "android")
            )
        )
    }

    @Test
    fun `a package not in protectedPackageNames is unaffected by it`() {
        assertTrue(
            isSelectable(
                app("com.example.other"),
                ownPackageName = OWN_PACKAGE_NAME,
                protectedPackageNames = setOf("com.android.settings")
            )
        )
    }

    @Test
    fun `protectedPackageNames defaults to empty so existing callers are unaffected`() {
        assertTrue(isSelectable(app("com.android.settings"), ownPackageName = OWN_PACKAGE_NAME))
    }
}
