package com.batterymanager.app.domain.processing

import com.batterymanager.app.platform.process.ActivityManagerWrapper
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

private const val OWN_PACKAGE_NAME = "com.batterymanager.app"

/**
 * Test double recording every package it was asked to kill, and able to
 * throw for specific packages to exercise [CleanupOutcome.ERROR] without
 * affecting the rest of a batch.
 */
private class FakeActivityManagerWrapper(
    private val packagesThatThrow: Set<String> = emptySet()
) : ActivityManagerWrapper {
    val killedPackages = mutableListOf<String>()

    override fun killBackgroundProcesses(packageName: String) {
        if (packageName in packagesThatThrow) {
            throw SecurityException("no permission to kill $packageName")
        }
        killedPackages += packageName
    }
}

class BackgroundCleanupUseCaseTest {

    @Test
    fun `a normal eligible installed package is processed and actually killed`() {
        val activityManagerWrapper = FakeActivityManagerWrapper()
        val useCase = BackgroundCleanupUseCase(activityManagerWrapper)

        val results = useCase.cleanup(
            packageNames = setOf("com.example.alpha"),
            installedPackages = setOf("com.example.alpha"),
            selectablePackages = setOf("com.example.alpha"),
            ownPackageName = OWN_PACKAGE_NAME
        )

        assertEquals(1, results.size)
        assertEquals(CleanupOutcome.PROCESSED, results.single().outcome)
        assertEquals(listOf("com.example.alpha"), activityManagerWrapper.killedPackages)
    }

    @Test
    fun `battery manager's own package is always protected and never killed`() {
        val activityManagerWrapper = FakeActivityManagerWrapper()
        val useCase = BackgroundCleanupUseCase(activityManagerWrapper)

        val results = useCase.cleanup(
            packageNames = setOf(OWN_PACKAGE_NAME),
            installedPackages = setOf(OWN_PACKAGE_NAME),
            selectablePackages = setOf(OWN_PACKAGE_NAME),
            ownPackageName = OWN_PACKAGE_NAME
        )

        assertEquals(CleanupOutcome.PROTECTED, results.single().outcome)
        assertTrue(activityManagerWrapper.killedPackages.isEmpty())
    }

    @Test
    fun `own package is protected even if it somehow appears selectable`() {
        // Defense in depth: even if a caller passed a stale/corrupt
        // selectablePackages set that included the host app's own
        // package, PROTECTED must still win over PROCESSED.
        val activityManagerWrapper = FakeActivityManagerWrapper()
        val useCase = BackgroundCleanupUseCase(activityManagerWrapper)

        val results = useCase.cleanup(
            packageNames = setOf(OWN_PACKAGE_NAME),
            installedPackages = setOf(OWN_PACKAGE_NAME),
            selectablePackages = setOf(OWN_PACKAGE_NAME),
            ownPackageName = OWN_PACKAGE_NAME
        )

        assertEquals(CleanupOutcome.PROTECTED, results.single().outcome)
    }

    @Test
    fun `a package no longer installed is unavailable and never killed`() {
        val activityManagerWrapper = FakeActivityManagerWrapper()
        val useCase = BackgroundCleanupUseCase(activityManagerWrapper)

        val results = useCase.cleanup(
            packageNames = setOf("com.example.uninstalled"),
            installedPackages = emptySet(),
            selectablePackages = emptySet(),
            ownPackageName = OWN_PACKAGE_NAME
        )

        assertEquals(CleanupOutcome.UNAVAILABLE, results.single().outcome)
        assertTrue(activityManagerWrapper.killedPackages.isEmpty())
    }

    @Test
    fun `an installed but no-longer-selectable package is skipped and never killed`() {
        val activityManagerWrapper = FakeActivityManagerWrapper()
        val useCase = BackgroundCleanupUseCase(activityManagerWrapper)

        val results = useCase.cleanup(
            packageNames = setOf("com.example.nowdisabled"),
            installedPackages = setOf("com.example.nowdisabled"),
            selectablePackages = emptySet(),
            ownPackageName = OWN_PACKAGE_NAME
        )

        assertEquals(CleanupOutcome.SKIPPED, results.single().outcome)
        assertTrue(activityManagerWrapper.killedPackages.isEmpty())
    }

    @Test
    fun `a kill that throws is reported as an error, not a crash`() {
        val activityManagerWrapper = FakeActivityManagerWrapper(
            packagesThatThrow = setOf("com.example.denied")
        )
        val useCase = BackgroundCleanupUseCase(activityManagerWrapper)

        val results = useCase.cleanup(
            packageNames = setOf("com.example.denied"),
            installedPackages = setOf("com.example.denied"),
            selectablePackages = setOf("com.example.denied"),
            ownPackageName = OWN_PACKAGE_NAME
        )

        val result = results.single()
        assertEquals(CleanupOutcome.ERROR, result.outcome)
        assertTrue(result.detail!!.contains("com.example.denied"))
    }

    @Test
    fun `one package throwing does not stop the rest of the batch`() {
        val activityManagerWrapper = FakeActivityManagerWrapper(
            packagesThatThrow = setOf("com.example.denied")
        )
        val useCase = BackgroundCleanupUseCase(activityManagerWrapper)

        val results = useCase.cleanup(
            packageNames = setOf("com.example.alpha", "com.example.denied", "com.example.beta"),
            installedPackages = setOf("com.example.alpha", "com.example.denied", "com.example.beta"),
            selectablePackages = setOf("com.example.alpha", "com.example.denied", "com.example.beta"),
            ownPackageName = OWN_PACKAGE_NAME
        )

        assertEquals(3, results.size)
        val byPackage = results.associateBy { it.packageName }
        assertEquals(CleanupOutcome.PROCESSED, byPackage.getValue("com.example.alpha").outcome)
        assertEquals(CleanupOutcome.ERROR, byPackage.getValue("com.example.denied").outcome)
        assertEquals(CleanupOutcome.PROCESSED, byPackage.getValue("com.example.beta").outcome)
        assertEquals(
            setOf("com.example.alpha", "com.example.beta"),
            activityManagerWrapper.killedPackages.toSet()
        )
    }

    @Test
    fun `a mixed batch produces one result per package with the right outcome each`() {
        val activityManagerWrapper = FakeActivityManagerWrapper(
            packagesThatThrow = setOf("com.example.errorapp")
        )
        val useCase = BackgroundCleanupUseCase(activityManagerWrapper)

        val results = useCase.cleanup(
            packageNames = setOf(
                "com.example.normal",
                "com.example.errorapp",
                "com.example.uninstalled",
                "com.example.nowdisabled",
                OWN_PACKAGE_NAME
            ),
            installedPackages = setOf(
                "com.example.normal",
                "com.example.errorapp",
                "com.example.nowdisabled",
                OWN_PACKAGE_NAME
                // "com.example.uninstalled" is deliberately absent.
            ),
            selectablePackages = setOf(
                "com.example.normal",
                "com.example.errorapp"
                // "com.example.nowdisabled" is deliberately absent -
                // installed but no longer selectable.
            ),
            ownPackageName = OWN_PACKAGE_NAME
        )

        val byPackage = results.associateBy { it.packageName }
        assertEquals(5, results.size)
        assertEquals(CleanupOutcome.PROCESSED, byPackage.getValue("com.example.normal").outcome)
        assertEquals(CleanupOutcome.ERROR, byPackage.getValue("com.example.errorapp").outcome)
        assertEquals(CleanupOutcome.UNAVAILABLE, byPackage.getValue("com.example.uninstalled").outcome)
        assertEquals(CleanupOutcome.SKIPPED, byPackage.getValue("com.example.nowdisabled").outcome)
        assertEquals(CleanupOutcome.PROTECTED, byPackage.getValue(OWN_PACKAGE_NAME).outcome)
    }

    @Test
    fun `an empty selection produces an empty result list`() {
        val activityManagerWrapper = FakeActivityManagerWrapper()
        val useCase = BackgroundCleanupUseCase(activityManagerWrapper)

        val results = useCase.cleanup(
            packageNames = emptySet(),
            installedPackages = setOf("com.example.alpha"),
            selectablePackages = setOf("com.example.alpha"),
            ownPackageName = OWN_PACKAGE_NAME
        )

        assertTrue(results.isEmpty())
        assertTrue(activityManagerWrapper.killedPackages.isEmpty())
    }
}
