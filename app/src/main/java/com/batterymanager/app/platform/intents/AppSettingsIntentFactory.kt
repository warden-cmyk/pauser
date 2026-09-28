package com.batterymanager.app.platform.intents

import android.content.Intent
import android.net.Uri
import android.provider.Settings

/**
 * The entire Android-interaction surface of the guided Settings workflow
 * (Architecture doc Component 9, Phase B): building one documented
 * `Intent` per app. Nothing in this file - or anything that calls it -
 * reads a View hierarchy, registers an `AccessibilityService`, injects
 * input events, or queries the Settings app's UI state; there is no code
 * path here capable of any of that. The caller (`MainScreen`'s
 * `GuidedSettingsOverlay`) does exactly two more things with the result:
 * `startActivity(intent)`, and later notices its own `Activity.onResume()`
 * fired to know the user came back - both ordinary, documented Android
 * mechanisms available to any app, not anything Settings-specific.
 *
 * This is deliberately a plain interface with no suspend functions and no
 * dependencies to fake - unlike [com.batterymanager.app.platform.process.ActivityManagerWrapper],
 * there is nothing here worth unit-testing with a fake: the only
 * meaningful check (does the built `Intent` actually resolve to a real
 * Settings screen and does the user's return get detected) requires a
 * real device/emulator - see PROJECT_STATE.md.
 */
interface AppSettingsIntentFactory {
    /**
     * Builds an [Intent] for [packageName]'s
     * `ACTION_APPLICATION_DETAILS_SETTINGS` page - the same system screen
     * Android's own Settings app uses, with a Force Stop button the user
     * must tap themselves. Battery Manager never taps it, reads it, or
     * waits for a particular outcome from it.
     */
    fun buildAppDetailsIntent(packageName: String): Intent
}

class AndroidAppSettingsIntentFactory : AppSettingsIntentFactory {
    override fun buildAppDetailsIntent(packageName: String): Intent =
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = Uri.fromParts("package", packageName, null)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
}
