package com.batterymanager.app.ui.screens.main

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.batterymanager.app.data.apps.AppRepository
import com.batterymanager.app.data.presets.Preset
import com.batterymanager.app.data.presets.PresetRepository
import com.batterymanager.app.data.selection.SelectionStore
import com.batterymanager.app.domain.guided.GuidedSettingsController
import com.batterymanager.app.domain.guided.GuidedSettingsState
import com.batterymanager.app.domain.processing.BackgroundCleanupUseCase
import com.batterymanager.app.domain.processing.CleanupOutcome
import com.batterymanager.app.domain.processing.CleanupResult
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Connects [AppRepository] (Architecture doc Component 1's data layer) to
 * [MainScreen], and owns the current-session selection state plus saved
 * presets.
 *
 * [AppRepository.getLaunchableApps] is a `suspend` function that the
 * repository itself dispatches off the main thread (see
 * `DefaultAppRepository`'s `withContext(Dispatchers.Default)`); this
 * ViewModel just launches it in [viewModelScope] and maps the outcome to
 * [MainUiState].
 *
 * Selection persists across cold starts via [selectionStore] (Session 6):
 * every load - both the initial one and [retry] - reads back the last
 * saved selection and restores exactly the part of it that is still
 * valid, and every change to the selection (manual toggle, Select All,
 * Deselect All, or loading a preset) is saved. See [restoreSelection] for
 * exactly what "still valid" means.
 *
 * Named presets (Session 7) are exposed separately via [presets] and
 * managed through [createPreset]/[deletePreset]/[loadPreset]. Loading a
 * preset reuses the exact same [sanitizeForRestore] intersection
 * [restoreSelection] already uses, so it inherits the same guarantees,
 * including [protectedPackageNames] exclusion (Protected applications
 * task) - see PROJECT_STATE.md for what that name-based protection does
 * and does not cover.
 *
 * Background-process cleanup (Session 8, Architecture doc Component 8
 * Phase A only) is exposed via [cleanupState] and driven by
 * [cleanSelected]/[dismissCleanupSummary]. This is the only automatic
 * action in the app so far, and it is deliberately narrow: one documented
 * call - `ActivityManager.killBackgroundProcesses` - per selected package,
 * with no guarantee an app stays stopped. See
 * [BackgroundCleanupUseCase]/[CleanupOutcome] for the honest result
 * vocabulary this deliberately does not overstate.
 *
 * The guided Settings workflow (Session 9, Architecture doc Component 9,
 * Phase B) is exposed via [guidedSettingsState] and driven by
 * [startGuidedSettings]/[confirmGuidedSettingsReturn]/[skipCurrentGuidedApp]/
 * [skipAllRemainingGuided]/[cancelGuidedSettings]/[markCurrentGuidedAppUnavailable]/
 * [dismissGuidedSettingsSummary]. This ViewModel owns only the *sequence*
 * (via the pure [GuidedSettingsController] state machine) - it never
 * builds an `Intent`, never calls `startActivity`, and never reads
 * anything back from the Settings screen. Launching each app's Settings
 * page and noticing the user returned (`Activity.onResume`) happens in
 * `MainScreen`'s `GuidedSettingsOverlay`, which calls back into the
 * wrappers here. See PROJECT_STATE.md for exactly why the split is drawn
 * there and what that does and doesn't let this session claim was tested.
 */
class MainViewModel(
    private val appRepository: AppRepository,
    private val ownPackageName: String,
    private val selectionStore: SelectionStore,
    private val presetRepository: PresetRepository,
    private val backgroundCleanupUseCase: BackgroundCleanupUseCase,
    private val protectedPackageNames: Set<String> = emptySet()
) : ViewModel() {

    private val _uiState = MutableStateFlow<MainUiState>(MainUiState.Loading)
    val uiState: StateFlow<MainUiState> = _uiState.asStateFlow()

    private val _presets = MutableStateFlow<List<Preset>>(emptyList())

    /** Every saved preset, sorted by name. Loaded once at init, refreshed after every create/delete. */
    val presets: StateFlow<List<Preset>> = _presets.asStateFlow()

    private val _cleanupState = MutableStateFlow<CleanupUiState>(CleanupUiState.Idle)

    /** Drives the "Clean Selected" button/progress/summary UI - see [cleanSelected]. */
    val cleanupState: StateFlow<CleanupUiState> = _cleanupState.asStateFlow()

    private val _guidedSettingsState = MutableStateFlow<GuidedSettingsState>(GuidedSettingsState.Idle)

    /** Drives the "Manage Selected" guided Settings overlay/summary UI - see [startGuidedSettings]. */
    val guidedSettingsState: StateFlow<GuidedSettingsState> = _guidedSettingsState.asStateFlow()

    init {
        loadApps()
        viewModelScope.launch { refreshPresets() }
    }

    /** Re-runs discovery. Safe to call again after a [MainUiState.Error]. */
    fun retry() {
        loadApps()
    }

    /**
     * Checks or unchecks [packageName].
     *
     * A no-op (rather than a crash) when there is no current [MainUiState.Success]
     * or when [packageName] is not currently selectable - see [isSelectable].
     * The UI is not expected to render a checkbox for a non-selectable app in
     * the first place; this is a defensive second guard, not the primary
     * enforcement point.
     */
    fun toggleSelection(packageName: String) {
        val current = _uiState.value
        if (current !is MainUiState.Success) return
        if (packageName !in current.selectableApps) return

        val updatedSelection = if (packageName in current.selectedPackageNames) {
            current.selectedPackageNames - packageName
        } else {
            current.selectedPackageNames + packageName
        }
        applySelection(current, updatedSelection)
    }

    /**
     * Selects every currently eligible app - i.e. sets
     * [MainUiState.Success.selectedPackageNames] to exactly
     * [MainUiState.Success.selectableApps].
     *
     * This does not compute eligibility itself; it reuses the same
     * [MainUiState.Success.selectableApps] set that [toggleSelection] and
     * [loadApps] already enforce via [isSelectable] - so it inherits, and
     * cannot weaken, that function's exclusions (the host app itself, any
     * non-launchable app, and every [protectedPackageNames] entry - see
     * PROJECT_STATE.md for what that small, explicit protection list does
     * and does not cover relative to the Architecture doc's fuller
     * protected/system/critical classification, which still doesn't
     * exist).
     *
     * A no-op when there is no current [MainUiState.Success].
     */
    fun selectAll() {
        val current = _uiState.value
        if (current !is MainUiState.Success) return
        applySelection(current, current.selectableApps)
    }

    /**
     * Clears every selection. A no-op when there is no current
     * [MainUiState.Success].
     */
    fun deselectAll() {
        val current = _uiState.value
        if (current !is MainUiState.Success) return
        applySelection(current, emptySet())
    }

    /**
     * Saves the apps currently checked
     * ([MainUiState.Success.selectedPackageNames]) as a new preset named
     * [name].
     *
     * A no-op when there is no current [MainUiState.Success]. A blank
     * (after trimming) [name] saves nothing - see
     * [PresetRepository.createPreset]. The package set handed to the
     * repository is intersected with [MainUiState.Success.selectableApps]
     * defensively before saving, the same "don't just trust the caller"
     * pattern [toggleSelection] already applies, even though
     * `selectedPackageNames` should already be a subset of it.
     */
    fun createPreset(name: String) {
        val current = _uiState.value
        if (current !is MainUiState.Success) return
        val packageNames = current.selectedPackageNames intersect current.selectableApps
        viewModelScope.launch {
            presetRepository.createPreset(name, packageNames)
            refreshPresets()
        }
    }

    /** Deletes the saved preset with id [presetId]. A no-op if no such preset exists. */
    fun deletePreset(presetId: String) {
        viewModelScope.launch {
            presetRepository.deletePreset(presetId)
            refreshPresets()
        }
    }

    /**
     * Applies preset [presetId] to the live selection.
     *
     * Every package name in the preset is re-checked against *this
     * load's* [MainUiState.Success.selectableApps] before anything is
     * selected - via [sanitizeForRestore], the same intersection
     * [restoreSelection] already uses for cold-start restore. This
     * guarantees a loaded preset can never select an app that is no
     * longer installed (it wouldn't be in `selectableApps`), Battery
     * Manager itself, or - once real protected/system-critical
     * classification exists (see PROJECT_STATE.md) - a
     * protected/system-critical app: `selectableApps` excludes exactly
     * what [isSelectable] excludes, today and after that classification
     * lands, with no change needed here or in [restoreSelection].
     *
     * A no-op when there is no current [MainUiState.Success] or when
     * [presetId] does not match any currently loaded [presets] entry.
     */
    fun loadPreset(presetId: String) {
        val current = _uiState.value
        if (current !is MainUiState.Success) return
        val preset = _presets.value.find { it.id == presetId } ?: return
        val sanitized = sanitizeForRestore(preset.packageNames, current.selectableApps)
        applySelection(current, sanitized)
    }

    /**
     * Runs Phase A background cleanup (Architecture doc Component 8) for
     * every currently checked package
     * ([MainUiState.Success.selectedPackageNames]).
     *
     * A no-op when there is no current [MainUiState.Success], when
     * nothing is selected, or while a run is already in progress
     * ([cleanupState] is already [CleanupUiState.InProgress]) - so a
     * double-tap of "Clean Selected" cannot start a second overlapping
     * batch.
     *
     * Before attempting anything, this re-discovers the current app set
     * (rather than trusting whatever [MainUiState.Success.selectableApps]
     * was computed from at selection time) so that a package uninstalled
     * or made ineligible since selection is correctly reported as
     * [CleanupOutcome.UNAVAILABLE]/[CleanupOutcome.SKIPPED] instead of
     * being killed on stale information - see
     * [BackgroundCleanupUseCase.cleanup]'s doc comment.
     *
     * One package's kill failing, or the re-discovery step itself
     * failing, does not abort the run: [BackgroundCleanupUseCase] already
     * catches per-package failures, and a re-discovery failure here is
     * mapped to [CleanupOutcome.ERROR] for every selected package rather
     * than left unhandled - the batch always ends in
     * [CleanupUiState.Complete], never stuck in
     * [CleanupUiState.InProgress] or silently dropped.
     */
    fun cleanSelected() {
        val current = _uiState.value
        if (current !is MainUiState.Success) return
        val targets = current.selectedPackageNames
        if (targets.isEmpty()) return
        if (_cleanupState.value is CleanupUiState.InProgress) return

        _cleanupState.value = CleanupUiState.InProgress(total = targets.size)
        viewModelScope.launch {
            val results: List<CleanupResult> = try {
                val freshApps = appRepository.getLaunchableApps()
                val installed = freshApps.map { it.packageName }.toSet()
                val selectable = freshApps
                    .filter { isSelectable(it, ownPackageName, protectedPackageNames) }
                    .map { it.packageName }
                    .toSet()
                backgroundCleanupUseCase.cleanup(
                    packageNames = targets,
                    installedPackages = installed,
                    selectablePackages = selectable,
                    ownPackageName = ownPackageName
                )
            } catch (e: Exception) {
                // Re-discovery itself failed (e.g. an unexpected
                // PackageManager error) - report every selected package as
                // an error for this run rather than crashing or leaving
                // the run stuck in InProgress.
                targets.map { CleanupResult(it, CleanupOutcome.ERROR, detail = e.message) }
            }
            _cleanupState.value = CleanupUiState.Complete(results)
        }
    }

    /** Dismisses a shown [CleanupUiState.Complete] summary, returning to [CleanupUiState.Idle]. */
    fun dismissCleanupSummary() {
        _cleanupState.value = CleanupUiState.Idle
    }

    /**
     * Starts a new guided Settings run (Architecture doc Component 9,
     * Phase B) over every currently checked package
     * ([MainUiState.Success.selectedPackageNames]).
     *
     * A no-op when there is no current [MainUiState.Success], when nothing
     * is selected, or while a run is already [GuidedSettingsState.Running]
     * - same double-tap guard [cleanSelected] already uses.
     *
     * Like [cleanSelected], this re-discovers the currently installed set
     * before building the queue, rather than trusting whatever
     * [MainUiState.Success.apps] was computed from at selection time - so
     * an app uninstalled between selecting it and tapping "Manage
     * Selected" is auto-resolved as [com.batterymanager.app.domain.guided.GuidedOutcome.UNAVAILABLE]
     * and never shown, instead of the workflow trying (and failing) to
     * open a Settings page for it. If re-discovery itself fails, this
     * falls back to treating every selected package as still installed
     * (rather than marking all of them unavailable, which would be a
     * worse guess) - any that actually are gone are still caught
     * one-by-one when the UI layer tries and fails to open their Settings
     * page, via [markCurrentGuidedAppUnavailable].
     *
     * The queue order follows [MainUiState.Success.apps]'s existing
     * display order (filtered to the current selection) rather than
     * selection order, so the sequence a user sees matches the list
     * they're already looking at.
     */
    fun startGuidedSettings() {
        val current = _uiState.value
        if (current !is MainUiState.Success) return
        val targets = current.selectedPackageNames
        if (targets.isEmpty()) return
        if (_guidedSettingsState.value is GuidedSettingsState.Running) return

        val queue = current.apps.map { it.packageName }.filter { it in targets }
        viewModelScope.launch {
            val installed = try {
                appRepository.getLaunchableApps().map { it.packageName }.toSet()
            } catch (e: Exception) {
                targets
            }
            _guidedSettingsState.value = GuidedSettingsController.start(queue, installed)
        }
    }

    /**
     * Called after the UI layer opened the current app's Settings page and
     * detected the user returned (`Activity.onResume`). Records
     * [com.batterymanager.app.domain.guided.GuidedOutcome.CONFIRMED_VIA_SETTINGS]
     * for it and advances. A no-op when [guidedSettingsState] is not
     * currently [GuidedSettingsState.Running].
     */
    fun confirmGuidedSettingsReturn() {
        val state = _guidedSettingsState.value
        if (state !is GuidedSettingsState.Running) return
        _guidedSettingsState.value = GuidedSettingsController.confirmCurrentAndAdvance(state)
    }

    /**
     * Called when the UI layer could not open the current app's Settings
     * page at all (e.g. no activity resolved the intent, or launching it
     * threw). Records it as unavailable and advances rather than leaving
     * the run stuck. A no-op when [guidedSettingsState] is not currently
     * [GuidedSettingsState.Running].
     */
    fun markCurrentGuidedAppUnavailable() {
        val state = _guidedSettingsState.value
        if (state !is GuidedSettingsState.Running) return
        _guidedSettingsState.value = GuidedSettingsController.markCurrentUnavailable(state)
    }

    /** Skips the current app in the guided run. A no-op unless [guidedSettingsState] is [GuidedSettingsState.Running]. */
    fun skipCurrentGuidedApp() {
        val state = _guidedSettingsState.value
        if (state !is GuidedSettingsState.Running) return
        _guidedSettingsState.value = GuidedSettingsController.skipCurrent(state)
    }

    /**
     * Ends the guided run immediately, recording every remaining app
     * (including the current one) as skipped. A no-op unless
     * [guidedSettingsState] is [GuidedSettingsState.Running].
     */
    fun skipAllRemainingGuided() {
        val state = _guidedSettingsState.value
        if (state !is GuidedSettingsState.Running) return
        _guidedSettingsState.value = GuidedSettingsController.skipAllRemaining(state)
    }

    /**
     * Ends the guided run immediately, recording every remaining app
     * (including the current one) as cancelled. Used for both an explicit
     * Cancel action and the user pressing Back mid-run. A no-op unless
     * [guidedSettingsState] is [GuidedSettingsState.Running] - safe to call
     * even when no run is active (e.g. a stray Back press elsewhere).
     */
    fun cancelGuidedSettings() {
        val state = _guidedSettingsState.value
        if (state !is GuidedSettingsState.Running) return
        _guidedSettingsState.value = GuidedSettingsController.cancel(state)
    }

    /** Dismisses a shown [GuidedSettingsState.Complete] summary, returning to [GuidedSettingsState.Idle]. */
    fun dismissGuidedSettingsSummary() {
        _guidedSettingsState.value = GuidedSettingsState.Idle
    }

    private suspend fun refreshPresets() {
        _presets.value = presetRepository.getPresets()
    }

    /**
     * Updates in-memory state to [updatedSelection] immediately (so the UI
     * and the selected count reflect the change with no delay), then
     * persists it via [selectionStore] on [viewModelScope]. Saving is
     * fire-and-forget from the caller's perspective - there is no loading
     * spinner or error surfaced for a save failure; the in-memory
     * selection (what the user sees and acts on this session) is not
     * affected by whether the save succeeds.
     */
    private fun applySelection(current: MainUiState.Success, updatedSelection: Set<String>) {
        _uiState.value = current.copy(selectedPackageNames = updatedSelection)
        viewModelScope.launch {
            selectionStore.saveSelectedPackageNames(updatedSelection)
        }
    }

    private fun loadApps() {
        viewModelScope.launch {
            _uiState.value = MainUiState.Loading
            _uiState.value = try {
                val apps = appRepository.getLaunchableApps()
                if (apps.isEmpty()) {
                    MainUiState.Empty
                } else {
                    val selectable = apps
                        .filter { isSelectable(it, ownPackageName, protectedPackageNames) }
                        .map { it.packageName }
                        .toSet()
                    MainUiState.Success(
                        apps = apps,
                        selectableApps = selectable,
                        selectedPackageNames = restoreSelection(selectable)
                    )
                }
            } catch (e: Exception) {
                // AppRepository/PackageManagerWrapper already swallow the
                // per-app NameNotFoundException case internally; this is a
                // last-resort net for anything else PackageManager could
                // throw (e.g. a transient system-server failure) so one bad
                // query surfaces as the Error state instead of crashing the
                // screen.
                MainUiState.Error(e.message)
            }
        }
    }

    /**
     * Reads the last-saved selection and returns the subset that is still
     * safe to restore right now, against [currentSelectableApps] (this
     * load's freshly computed [MainUiState.Success.selectableApps]).
     *
     * The intersection with [currentSelectableApps] is what satisfies all
     * three restore rules at once, using the exact same eligibility gate
     * [toggleSelection]/[selectAll] already trust rather than a second,
     * separately-maintained one:
     *
     *  - **Packages that no longer exist** are never in
     *    [currentSelectableApps] (it's built from this load's fresh
     *    [AppRepository.getLaunchableApps] result), so an uninstalled
     *    app's package name is silently dropped rather than restored.
     *  - **Battery Manager itself** is excluded from
     *    [currentSelectableApps] unconditionally (see [isSelectable]), so
     *    even a persisted file that somehow contained [ownPackageName] -
     *    e.g. hand-edited, or written by a future/different version of
     *    this app - could not be restored.
     *  - **Protected applications** ([protectedPackageNames] - Battery
     *    Manager itself, Android Settings, System UI, "Android System",
     *    and the device launcher when identifiable) are likewise excluded
     *    from [currentSelectableApps] by [isSelectable], so a persisted
     *    file that somehow contained one of them - e.g. hand-edited, or
     *    saved by a build predating this protection - could not be
     *    restored either. This remains a small, explicit name-based list,
     *    not the Architecture doc's fuller protected/system/critical
     *    classification (Components 2-3) - see PROJECT_STATE.md.
     */
    private suspend fun restoreSelection(currentSelectableApps: Set<String>): Set<String> {
        val persisted = try {
            selectionStore.getSelectedPackageNames()
        } catch (e: Exception) {
            // A corrupt or unreadable persisted file should not block app
            // discovery from succeeding - fall back to "nothing restored"
            // rather than surfacing MainUiState.Error for a persistence
            // problem unrelated to app discovery.
            emptySet()
        }
        return sanitizeForRestore(persisted, currentSelectableApps)
    }

    /**
     * The one place both [restoreSelection] (cold-start/retry restore)
     * and [loadPreset] (Session 7) narrow a candidate package-name set
     * down to what is actually safe to select right now: exactly its
     * intersection with [currentSelectableApps]. Extracted so both call
     * sites visibly share one safety guarantee instead of two separately
     * maintained copies of the same intersection.
     */
    private fun sanitizeForRestore(
        candidates: Set<String>,
        currentSelectableApps: Set<String>
    ): Set<String> = candidates intersect currentSelectableApps

    companion object {
        /**
         * Manual [ViewModelProvider.Factory]. The Architecture doc's
         * `AppContainer` (manual DI) is not wired up yet - with a single
         * screen, the caller constructs each dependency directly and hands
         * them here: the repository, the host app's own package name
         * (used to enforce that the app can never select itself - see
         * [isSelectable]), the [SelectionStore] used to persist and
         * restore the selection, the [PresetRepository] used for saved
         * presets, and the [BackgroundCleanupUseCase] used by
         * [cleanSelected]. [protectedPackageNames] (Protected applications
         * task) is normally
         * [com.batterymanager.app.platform.apps.ProtectedPackages.resolveProtectedPackageNames],
         * computed once in `MainActivity` and passed the same way
         * [ownPackageName] already is; it defaults to empty so existing
         * callers/tests that don't pass it are unaffected.
         */
        fun factory(
            appRepository: AppRepository,
            ownPackageName: String,
            selectionStore: SelectionStore,
            presetRepository: PresetRepository,
            backgroundCleanupUseCase: BackgroundCleanupUseCase,
            protectedPackageNames: Set<String> = emptySet()
        ): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T =
                    MainViewModel(
                        appRepository,
                        ownPackageName,
                        selectionStore,
                        presetRepository,
                        backgroundCleanupUseCase,
                        protectedPackageNames
                    ) as T
            }
    }
}
