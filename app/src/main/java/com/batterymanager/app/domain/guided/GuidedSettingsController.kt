package com.batterymanager.app.domain.guided

/**
 * The state machine for the guided Settings workflow (Architecture doc
 * Component 9, Phase B). Every function here is a pure transformation from
 * one [GuidedSettingsState] to the next - no Android imports, no
 * `Intent`/`startActivity`, no lifecycle observation. Those live in the UI
 * layer (`MainScreen`'s `GuidedSettingsOverlay`), which calls back into
 * `MainViewModel`'s thin wrappers around the functions here. See
 * PROJECT_STATE.md for why the split is drawn exactly at this line: this
 * is the part that can be (and is) unit-tested without an emulator or a
 * real Settings screen; the platform interaction cannot be.
 *
 * Every entry point below advances at most one step at a time except
 * [skipAllRemaining] and [cancel], which resolve every remaining app in
 * one call.
 */
object GuidedSettingsController {

    /**
     * Starts a new run over [queue] (already decided by the caller - see
     * `MainViewModel.startGuidedSettings` for how it orders/filters the
     * current selection). [installedPackages] is a snapshot of what's
     * actually installed right now, used only to skip already-uninstalled
     * apps automatically - see [GuidedSettingsState.Running.installedPackages].
     *
     * An empty [queue] goes straight to [GuidedSettingsState.Complete] with
     * no results, so callers don't need to special-case "nothing was
     * selected" themselves.
     */
    fun start(queue: List<String>, installedPackages: Set<String>): GuidedSettingsState {
        if (queue.isEmpty()) return GuidedSettingsState.Complete(emptyList())
        val initial = GuidedSettingsState.Running(
            queue = queue,
            index = 0,
            results = emptyList(),
            installedPackages = installedPackages
        )
        return resolveLeadingUnavailableApps(initial)
    }

    /**
     * Records that the user opened [GuidedSettingsState.Running.current]'s
     * Settings page and returned to Battery Manager, then advances - see
     * [GuidedOutcome.CONFIRMED_VIA_SETTINGS] for exactly what this claims
     * and does not claim.
     */
    fun confirmCurrentAndAdvance(state: GuidedSettingsState.Running): GuidedSettingsState =
        advance(state, GuidedOutcome.CONFIRMED_VIA_SETTINGS)

    /** Records the current app as skipped and advances to the next one. */
    fun skipCurrent(state: GuidedSettingsState.Running): GuidedSettingsState =
        advance(state, GuidedOutcome.SKIPPED)

    /**
     * Records the current app as unavailable (its Settings page could not
     * be opened - e.g. no activity resolved the intent, or it was
     * uninstalled between this run starting and reaching it) and advances.
     * This is the entry point the UI layer calls when launching the
     * Settings intent itself fails - distinct from
     * [resolveLeadingUnavailableApps], which handles apps already known to
     * be gone before they're ever shown.
     */
    fun markCurrentUnavailable(state: GuidedSettingsState.Running): GuidedSettingsState =
        advance(state, GuidedOutcome.UNAVAILABLE)

    /**
     * Ends the run immediately, recording every remaining app (including
     * the current one) as [GuidedOutcome.SKIPPED].
     */
    fun skipAllRemaining(state: GuidedSettingsState.Running): GuidedSettingsState.Complete {
        val remaining = state.queue.drop(state.index).map { GuidedResult(it, GuidedOutcome.SKIPPED) }
        return GuidedSettingsState.Complete(state.results + remaining)
    }

    /**
     * Ends the run immediately, recording every remaining app (including
     * the current one) as [GuidedOutcome.CANCELLED]. Used for both an
     * explicit Cancel action and the user pressing Back mid-run - see
     * `MainViewModel.cancelGuidedSettings`.
     */
    fun cancel(state: GuidedSettingsState.Running): GuidedSettingsState.Complete {
        val remaining = state.queue.drop(state.index).map { GuidedResult(it, GuidedOutcome.CANCELLED) }
        return GuidedSettingsState.Complete(state.results + remaining)
    }

    /** Shared by every one-step transition: record [outcome] for the current app, then move on. */
    private fun advance(state: GuidedSettingsState.Running, outcome: GuidedOutcome): GuidedSettingsState {
        val updatedResults = state.results + GuidedResult(state.current, outcome)
        val nextIndex = state.index + 1
        return if (nextIndex >= state.queue.size) {
            GuidedSettingsState.Complete(updatedResults)
        } else {
            resolveLeadingUnavailableApps(state.copy(index = nextIndex, results = updatedResults))
        }
    }

    /**
     * Starting at [state]'s current index, automatically records
     * [GuidedOutcome.UNAVAILABLE] for every consecutive app that is not in
     * [GuidedSettingsState.Running.installedPackages], without ever
     * surfacing it to the UI, until landing on one that is installed (the
     * new current app) or exhausting the queue (→ [GuidedSettingsState.Complete]).
     * Called after [start] and after every [advance], so a run that begins
     * with (or advances onto a run of) already-uninstalled apps skips past
     * all of them in one state update rather than requiring N no-op UI
     * frames.
     */
    private tailrec fun resolveLeadingUnavailableApps(state: GuidedSettingsState.Running): GuidedSettingsState {
        if (state.current in state.installedPackages) return state
        val updatedResults = state.results + GuidedResult(state.current, GuidedOutcome.UNAVAILABLE)
        val nextIndex = state.index + 1
        return if (nextIndex >= state.queue.size) {
            GuidedSettingsState.Complete(updatedResults)
        } else {
            resolveLeadingUnavailableApps(state.copy(index = nextIndex, results = updatedResults))
        }
    }
}
