package com.batterymanager.app.platform.apps

import android.graphics.ColorFilter
import android.graphics.PixelFormat
import android.graphics.drawable.Drawable

/**
 * [Drawable] is an abstract Android framework class; the real android.jar
 * used in JVM unit tests only contains method stubs that throw. This test
 * double overrides just the abstract methods with no-ops so a
 * [LaunchableAppInfo] can be constructed in a plain JVM unit test without
 * Robolectric or an instrumented device/emulator.
 */
class FakeDrawable : Drawable() {
    override fun draw(canvas: android.graphics.Canvas) = Unit
    override fun setAlpha(alpha: Int) = Unit
    override fun setColorFilter(colorFilter: ColorFilter?) = Unit
    @Deprecated("Deprecated in Java", ReplaceWith("PixelFormat.OPAQUE", "android.graphics.PixelFormat"))
    override fun getOpacity(): Int = PixelFormat.OPAQUE
}
