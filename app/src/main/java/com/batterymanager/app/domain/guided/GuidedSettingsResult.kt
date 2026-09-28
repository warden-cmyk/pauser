package com.batterymanager.app.domain.guided

/**
 * The honest, non-overstating result vocabulary for the guided Settings
 * workflow (Architecture doc Component 9, Phase B; PRD §11.1).
 *
 * Battery Manager never taps Force Stop, never reads the Settings screen,
 * and has no documented API to confirm another app was actually
 * force-stopped after the user completes that screen on their own. So
 * this enum must not contain (and no caller may map any of these cases
 * to) language like "stopped" or "force-stopped" - see each case's doc
 * comment for the exact claim it is and is not making, and
 * [com.batterymanager.app.domain.processing.CleanupOutcome] for the
 * identical philosophy already applied to Phase A.
 */
enum class GuidedOutcome {
    /**
     * The user was shown this app's `ACTION_APPLICATION_DETAILS_SETTINGS`
     * page and returned to Battery Manager afterward. This means exactly
     * "the user completed the guided flow and returned" (PRD §11.1's own
     * wording) - it is not a claim that the user tapped Force Stop, and
     * not a claim that the app is actually stopped. Battery Manager has
     * no way to observe either of those things.
     */
    CONFIRMED_VIA_SETTINGS,

    /**
     * The user chose to skip this app (either before its Settings page was
     * opened, or via "Skip All Remaining") - nothing was attempted for it.
     */
    SKIPPED,

    /**
     * This app was no longer installed by the time the guided workflow
     * reached it (or its Settings page could not be opened for any other
     * reason, e.g. no activity resolved the intent) - nothing was
     * attempted for it. Distinct from [SKIPPED]: the user did not choose
     * to skip this one, the workflow could not process it.
     */
    UNAVAILABLE,

    /**
     * The whole guided run was cancelled (Cancel, or the user pressing
     * Back) before this app was reached.
     */
    CANCELLED
}

/** One app's outcome from a guided Settings run. */
data class GuidedResult(val packageName: String, val outcome: GuidedOutcome)
