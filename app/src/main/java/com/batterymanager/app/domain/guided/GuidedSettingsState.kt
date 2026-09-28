package com.batterymanager.app.domain.guided

/**
 * State for the guided Settings workflow (Architecture doc Component 9,
 * Phase B), driven by [GuidedSettingsController] and exposed from
 * `MainViewModel.guidedSettingsState`.
 *
 * This file has no Android imports - it is plain Kotlin data, deliberately,
 * so [GuidedSettingsController]'s transitions can be unit-tested with no
 * Android framework/emulator involved. See PROJECT_STATE.md's testing
 * notes for this session: only the state machine itself is claimed to be
 * tested this way - actually opening the system Settings screen and
 * detecting the user's return is real platform interaction that this
 * project has no way to exercise here.
 */
sealed interface GuidedSettingsState {

    /** No guided run in progress and no summary to show. */
    data object Idle : GuidedSettingsState

    /**
     * A guided run is in progress.
     *
     * @property queue every package name this run is walking through, in
     *   the fixed order decided once at [GuidedSettingsController.start] -
     *   never reordered or mutated afterward.
     * @property index position of the app currently being shown, into
     *   [queue]. Always a valid index while in [Running] - the controller
     *   moves straight to [Complete] once there is nothing left to show.
     * @property results one [GuidedResult] per package already resolved,
     *   in the order they were resolved. Always has exactly [index]
     *   entries.
     * @property installedPackages the set of package names confirmed
     *   installed when this run started (see
     *   [GuidedSettingsController.start]). Used only to auto-resolve an
     *   already-uninstalled app as [com.batterymanager.app.domain.guided.GuidedOutcome.UNAVAILABLE]
     *   without ever showing it or attempting to open its Settings page -
     *   it is not re-queried mid-run, so an app uninstalled *during* the
     *   run (after this snapshot was taken) is instead caught at the
     *   point the UI layer actually tries to launch its Settings intent
     *   and fails - see `MainViewModel.markCurrentGuidedAppUnavailable`.
     */
    data class Running(
        val queue: List<String>,
        val index: Int,
        val results: List<GuidedResult>,
        val installedPackages: Set<String>
    ) : GuidedSettingsState {
        /** Total number of apps this run is processing. */
        val total: Int get() = queue.size

        /** 1-based position of [current] within [queue], for "N of total" progress copy. */
        val position: Int get() = index + 1

        /** The package name currently being shown to the user. */
        val current: String get() = queue[index]
    }

    /** The run finished - every app was processed, skipped, unavailable, or cancelled. */
    data class Complete(val results: List<GuidedResult>) : GuidedSettingsState
}
