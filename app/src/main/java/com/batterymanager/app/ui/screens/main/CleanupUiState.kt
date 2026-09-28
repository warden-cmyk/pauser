package com.batterymanager.app.ui.screens.main

import com.batterymanager.app.domain.processing.CleanupResult

/**
 * Drives the "Clean Selected" button / progress / summary UI in
 * [MainScreen]. Independent of [MainUiState] - the app list can keep
 * loading/reloading while a cleanup run is idle, in progress, or showing
 * its completed summary.
 */
sealed interface CleanupUiState {

    /** No cleanup run is in progress and no summary is being shown. */
    data object Idle : CleanupUiState

    /**
     * A cleanup run is in progress.
     *
     * @property total how many packages were selected for this run - used
     *   for the progress message ("Cleaning up N selected apps…"). There
     *   is no documented, reliable per-package progress callback for
     *   `killBackgroundProcesses`, so this is a single indeterminate
     *   progress indicator for the whole batch, not a step counter.
     */
    data class InProgress(val total: Int) : CleanupUiState

    /**
     * The run finished (every package got a result - see
     * [BackgroundCleanupUseCase][com.batterymanager.app.domain.processing.BackgroundCleanupUseCase],
     * which never lets one package's failure abort the batch). [results]
     * is shown as the completion summary until the user dismisses it via
     * [MainViewModel.dismissCleanupSummary].
     */
    data class Complete(val results: List<CleanupResult>) : CleanupUiState
}
