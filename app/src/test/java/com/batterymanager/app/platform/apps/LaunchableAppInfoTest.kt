package com.batterymanager.app.platform.apps

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LaunchableAppInfoTest {

    @Test
    fun `isLaunchable is true when a launch intent is present`() {
        val app = LaunchableAppInfo(
            packageName = "com.example.alpha",
            appName = "Alpha",
            icon = FakeDrawable(),
            launchIntent = android.content.Intent()
        )

        assertTrue(app.isLaunchable)
    }

    @Test
    fun `isLaunchable is false when no launch intent could be resolved`() {
        val app = LaunchableAppInfo(
            packageName = "com.example.beta",
            appName = "Beta",
            icon = FakeDrawable(),
            launchIntent = null
        )

        assertFalse(app.isLaunchable)
    }

    @Test
    fun `data class equality is based on all constructor properties`() {
        val icon = FakeDrawable()
        val first = LaunchableAppInfo("com.example.gamma", "Gamma", icon, null)
        val second = LaunchableAppInfo("com.example.gamma", "Gamma", icon, null)

        assertEquals(first, second)
    }
}
