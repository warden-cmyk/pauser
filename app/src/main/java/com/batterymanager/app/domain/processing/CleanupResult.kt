package com.batterymanager.app.domain.processing

/**
 * What happened when [BackgroundCleanupUseCase] considered one selected
 * package. Deliberately does **not** include anything implying a
 * confirmed, permanent stop (e.g. no "FORCE_STOPPED" case) - see
 * [ActivityManagerWrapper.killBackgroundProcesses][com.batterymanager.app.platform.process.ActivityManagerWrapper]
 * for why that guarantee does not exist on this API.
 */
enum class CleanupOutcome {
    /**
     * `ActivityManager.killBackgroundProcesses` was called for this
     * package and returned normally. This means cleanup was **requested**
     * / **attempted** - not that the app is confirmed stopped, and not
     * that it will stay stopped. Present this to the user as "Background
     * cleanup requested" or "Background process cleanup attempted," never
     * as "Force Stop" or "stopped."
     */
    PROCESSED,

    /**
     * The package was still installed but was no longer in the current,
     * freshly-recomputed set of selectable apps at the moment cleanup
     * ran (e.g. it became non-launchable, or was disabled, between
     * selection and this run) - so no kill was attempted for it.
     */
    SKIPPED,

    /**
     * The package is Battery Manager's own package. The app can never
     * request cleanup of itself, independent of any other classification -
     * this mirrors the same guarantee
     * [com.batterymanager.app.ui.screens.main.isSelectable] already
     * enforces for selection itself.
     */
    PROTECTED,

    /**
     * The package was no longer installed/discoverable when cleanup ran
     * (e.g. it was uninstalled after being selected, but before "Clean
     * Selected" was tapped) - so no kill was attempted for it.
     */
    UNAVAILABLE,

    /**
     * Calling `killBackgroundProcesses` for this package threw (e.g. a
     * [SecurityException] or another unexpected system-service failure).
     * The failure is scoped to this one package and does not stop the
     * rest of the batch - see [BackgroundCleanupUseCase].
     */
    ERROR
}

/**
 * The result of attempting cleanup for one package.
 *
 * @property packageName the package this result is about.
 * @property outcome see [CleanupOutcome].
 * @property detail optional, best-effort diagnostic text (e.g. an
 *   exception message for [CleanupOutcome.ERROR]). Not guaranteed to be
 *   present or user-friendly - UI should show fixed copy per [outcome],
 *   not display this directly, same convention as
 *   [com.batterymanager.app.ui.screens.main.MainUiState.Error.message].
 */
data class CleanupResult(
    val packageName: String,
    val outcome: CleanupOutcome,
    val detail: String? = null
)
