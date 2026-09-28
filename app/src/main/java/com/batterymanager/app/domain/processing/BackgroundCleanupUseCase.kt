package com.batterymanager.app.domain.processing

import com.batterymanager.app.platform.process.ActivityManagerWrapper

/**
 * Architecture doc Component 8 (Phase A) only: the legitimate,
 * documented-API background-process cleanup step. Phase B (the guided
 * Settings/Force Stop workflow, Architecture doc Component 9) is
 * explicitly out of scope for this use case and is not implemented here.
 *
 * This class contains no Android-framework imports beyond what
 * [ActivityManagerWrapper] already abstracts, so it is a plain JVM unit
 * test target (see `BackgroundCleanupUseCaseTest`) - no emulator or
 * Robolectric required, consistent with the Architecture doc §17 testing
 * table's "domain never imports Android framework classes directly"
 * principle.
 *
 * Never calls anything beyond [ActivityManagerWrapper.killBackgroundProcesses].
 * No root, no Shizuku, no ADB, no Accessibility, no hidden/private APIs,
 * no Device Owner.
 */
class BackgroundCleanupUseCase(
    private val activityManagerWrapper: ActivityManagerWrapper
) {

    /**
     * Attempts cleanup for every package in [packageNames], in order, and
     * returns one [CleanupResult] per package - the batch never stops
     * partway through because one package failed (see [attempt]).
     *
     * [packageNames] is expected to be the user's current selection
     * (`MainUiState.Success.selectedPackageNames`); [installedPackages]
     * and [selectablePackages] are expected to be freshly recomputed by
     * the caller immediately before this call (not reused from whenever
     * the selection was originally made), so that a package uninstalled
     * or made ineligible in the meantime is correctly reported as
     * [CleanupOutcome.UNAVAILABLE] / [CleanupOutcome.SKIPPED] instead of
     * being killed based on stale information.
     *
     * @param packageNames the packages to attempt cleanup for.
     * @param installedPackages every package name currently discoverable
     *   on the device (a fresh snapshot) - used to distinguish "no longer
     *   installed" ([CleanupOutcome.UNAVAILABLE]) from "installed but not
     *   currently eligible" ([CleanupOutcome.SKIPPED]).
     * @param selectablePackages the current eligible-for-selection set
     *   (fresh, same eligibility [com.batterymanager.app.ui.screens.main.isSelectable]
     *   already enforces) - a package outside this set but still in
     *   [installedPackages] is reported [CleanupOutcome.SKIPPED] rather
     *   than attempted.
     * @param ownPackageName Battery Manager's own package name - always
     *   reported [CleanupOutcome.PROTECTED], regardless of the other two
     *   sets, mirroring [com.batterymanager.app.ui.screens.main.isSelectable]'s
     *   unconditional self-exclusion.
     */
    fun cleanup(
        packageNames: Set<String>,
        installedPackages: Set<String>,
        selectablePackages: Set<String>,
        ownPackageName: String
    ): List<CleanupResult> =
        packageNames.map { packageName ->
            attempt(packageName, installedPackages, selectablePackages, ownPackageName)
        }

    private fun attempt(
        packageName: String,
        installedPackages: Set<String>,
        selectablePackages: Set<String>,
        ownPackageName: String
    ): CleanupResult {
        if (packageName == ownPackageName) {
            return CleanupResult(packageName, CleanupOutcome.PROTECTED)
        }
        if (packageName !in installedPackages) {
            return CleanupResult(packageName, CleanupOutcome.UNAVAILABLE)
        }
        if (packageName !in selectablePackages) {
            return CleanupResult(packageName, CleanupOutcome.SKIPPED)
        }
        return try {
            activityManagerWrapper.killBackgroundProcesses(packageName)
            // "Requested" / "attempted" - not "confirmed stopped". See
            // CleanupOutcome.PROCESSED's doc comment.
            CleanupResult(packageName, CleanupOutcome.PROCESSED, detail = "Background cleanup requested")
        } catch (e: Exception) {
            // One package's failure (e.g. a SecurityException, or an
            // unexpected system-service error) must not abort the rest of
            // the batch - caught here, per package, rather than letting it
            // propagate out of cleanup().
            CleanupResult(packageName, CleanupOutcome.ERROR, detail = e.message)
        }
    }
}
