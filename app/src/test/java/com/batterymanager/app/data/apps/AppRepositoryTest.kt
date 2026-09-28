package com.batterymanager.app.data.apps

import com.batterymanager.app.platform.apps.FakeDrawable
import com.batterymanager.app.platform.apps.LaunchableAppInfo
import com.batterymanager.app.platform.apps.PackageManagerWrapper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Test double standing in for the real PackageManager-backed wrapper. */
private class FakePackageManagerWrapper(
    private val apps: List<LaunchableAppInfo>
) : PackageManagerWrapper {
    override fun getLaunchableApps(): List<LaunchableAppInfo> = apps
}

private fun launchableApp(
    packageName: String,
    appName: String,
    launchable: Boolean = true
) = LaunchableAppInfo(
    packageName = packageName,
    appName = appName,
    icon = FakeDrawable(),
    launchIntent = if (launchable) android.content.Intent() else null
)

class AppRepositoryTest {

    @Test
    fun `returns apps sorted alphabetically by name, case-insensitively`() = runTest {
        val wrapper = FakePackageManagerWrapper(
            listOf(
                launchableApp("com.example.zeta", "zebra app"),
                launchableApp("com.example.alpha", "Apple App"),
                launchableApp("com.example.mid", "Mango App")
            )
        )
        val repository = DefaultAppRepository(wrapper, dispatcher = Dispatchers.Unconfined)

        val result = repository.getLaunchableApps()

        assertEquals(
            listOf("Apple App", "Mango App", "zebra app"),
            result.map { it.appName }
        )
    }

    @Test
    fun `deduplicates apps that appear more than once for the same package`() = runTest {
        val duplicatePackage = "com.example.duplicate"
        val wrapper = FakePackageManagerWrapper(
            listOf(
                launchableApp(duplicatePackage, "Duplicate App"),
                launchableApp(duplicatePackage, "Duplicate App")
            )
        )
        val repository = DefaultAppRepository(wrapper, dispatcher = Dispatchers.Unconfined)

        val result = repository.getLaunchableApps()

        assertEquals(1, result.size)
        assertEquals(duplicatePackage, result.first().packageName)
    }

    @Test
    fun `apps without a resolvable launch intent are kept, not dropped`() = runTest {
        val wrapper = FakePackageManagerWrapper(
            listOf(
                launchableApp("com.example.launchable", "Launchable App", launchable = true),
                launchableApp("com.example.unlaunchable", "Unlaunchable App", launchable = false)
            )
        )
        val repository = DefaultAppRepository(wrapper, dispatcher = Dispatchers.Unconfined)

        val result = repository.getLaunchableApps()

        assertEquals(2, result.size)
        assertTrue(result.single { it.packageName == "com.example.launchable" }.isLaunchable)
        assertFalse(result.single { it.packageName == "com.example.unlaunchable" }.isLaunchable)
    }

    @Test
    fun `empty discovery result produces an empty list, not an error`() = runTest {
        val wrapper = FakePackageManagerWrapper(emptyList())
        val repository = DefaultAppRepository(wrapper, dispatcher = Dispatchers.Unconfined)

        assertTrue(repository.getLaunchableApps().isEmpty())
    }
}
