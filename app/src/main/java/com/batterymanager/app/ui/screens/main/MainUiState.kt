package com.batterymanager.app.ui.screens.main

import com.batterymanager.app.platform.apps.LaunchableAppInfo

/**
 * UI state for [MainScreen], driven by [MainViewModel].
 *
 * [Success] now also carries selection state (which apps are selectable
 * and which are currently selected). There is still no Select All, no
 * presets, and no battery/processing actions - those belong to later
 * sessions.
 */
sealed interface MainUiState {

    /** Discovery is in progress; nothing to show yet. */
    data object Loading : MainUiState

    /**
     * Discovery finished and returned at least one app.
     *
     * @property apps every discovered app, in display order. Includes
     *   non-selectable apps (see [selectableApps]) - they are still shown
     *   in the list, just without a checkbox.
     * @property selectableApps package names of apps that may be
     *   checked/unchecked - see [isSelectable]. Not all discovered [apps]
     *   are selectable.
     * @property selectedPackageNames package names currently checked by
     *   the user. Always a subset of [selectableApps]; [MainViewModel]
     *   enforces this on every toggle rather than the UI state trusting
     *   callers to maintain it.
     */
    data class Success(
        val apps: List<LaunchableAppInfo>,
        val selectableApps: Set<String>,
        val selectedPackageNames: Set<String> = emptySet()
    ) : MainUiState {
        val selectedCount: Int get() = selectedPackageNames.size
    }

    /** Discovery finished successfully but found no launchable apps. */
    data object Empty : MainUiState

    /**
     * Discovery failed. [message] is best-effort diagnostic detail (e.g. an
     * exception message) - not guaranteed to be present or user-friendly,
     * so the UI shows a fixed string and does not display it directly.
     */
    data class Error(val message: String? = null) : MainUiState
}
