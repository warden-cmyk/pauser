package com.batterymanager.app.domain.guided

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests [GuidedSettingsController] entirely against plain data - no
 * Android imports anywhere in this file or in the production code it
 * exercises. This is the "workflow state machine tested independently of
 * Android Settings" the task asked for; opening a real Settings screen and
 * detecting the user's return is not exercised here or anywhere else in
 * this project (no emulator available) - see PROJECT_STATE.md.
 */
class GuidedSettingsControllerTest {

    private val allInstalled = setOf("a", "b", "c")

    @Test
    fun `start with an empty queue goes straight to Complete with no results`() {
        val state = GuidedSettingsController.start(queue = emptyList(), installedPackages = emptySet())

        assertEquals(GuidedSettingsState.Complete(emptyList()), state)
    }

    @Test
    fun `start with a non-empty queue of installed apps begins Running at index 0`() {
        val state = GuidedSettingsController.start(queue = listOf("a", "b"), installedPackages = allInstalled)

        assertTrue(state is GuidedSettingsState.Running)
        state as GuidedSettingsState.Running
        assertEquals("a", state.current)
        assertEquals(0, state.index)
        assertEquals(1, state.position)
        assertEquals(2, state.total)
        assertEquals(emptyList<GuidedResult>(), state.results)
    }

    @Test
    fun `start skips a leading app that is not installed and reports it unavailable`() {
        val state = GuidedSettingsController.start(
            queue = listOf("gone", "a"),
            installedPackages = setOf("a")
        )

        assertTrue(state is GuidedSettingsState.Running)
        state as GuidedSettingsState.Running
        assertEquals("a", state.current)
        assertEquals(listOf(GuidedResult("gone", GuidedOutcome.UNAVAILABLE)), state.results)
    }

    @Test
    fun `start skips every leading uninstalled app, not just the first`() {
        val state = GuidedSettingsController.start(
            queue = listOf("gone1", "gone2", "a"),
            installedPackages = setOf("a")
        )

        assertTrue(state is GuidedSettingsState.Running)
        state as GuidedSettingsState.Running
        assertEquals("a", state.current)
        assertEquals(
            listOf(
                GuidedResult("gone1", GuidedOutcome.UNAVAILABLE),
                GuidedResult("gone2", GuidedOutcome.UNAVAILABLE)
            ),
            state.results
        )
    }

    @Test
    fun `start with every app uninstalled goes straight to Complete, all unavailable`() {
        val state = GuidedSettingsController.start(
            queue = listOf("gone1", "gone2"),
            installedPackages = emptySet()
        )

        assertEquals(
            GuidedSettingsState.Complete(
                listOf(
                    GuidedResult("gone1", GuidedOutcome.UNAVAILABLE),
                    GuidedResult("gone2", GuidedOutcome.UNAVAILABLE)
                )
            ),
            state
        )
    }

    @Test
    fun `confirmCurrentAndAdvance records confirmed and moves to the next app`() {
        val running = GuidedSettingsController.start(listOf("a", "b"), allInstalled) as GuidedSettingsState.Running

        val next = GuidedSettingsController.confirmCurrentAndAdvance(running)

        assertTrue(next is GuidedSettingsState.Running)
        next as GuidedSettingsState.Running
        assertEquals("b", next.current)
        assertEquals(listOf(GuidedResult("a", GuidedOutcome.CONFIRMED_VIA_SETTINGS)), next.results)
    }

    @Test
    fun `confirmCurrentAndAdvance on the last app completes the run`() {
        val running = GuidedSettingsController.start(listOf("a"), allInstalled) as GuidedSettingsState.Running

        val next = GuidedSettingsController.confirmCurrentAndAdvance(running)

        assertEquals(
            GuidedSettingsState.Complete(listOf(GuidedResult("a", GuidedOutcome.CONFIRMED_VIA_SETTINGS))),
            next
        )
    }

    @Test
    fun `skipCurrent records skipped and advances`() {
        val running = GuidedSettingsController.start(listOf("a", "b"), allInstalled) as GuidedSettingsState.Running

        val next = GuidedSettingsController.skipCurrent(running)

        assertTrue(next is GuidedSettingsState.Running)
        next as GuidedSettingsState.Running
        assertEquals("b", next.current)
        assertEquals(listOf(GuidedResult("a", GuidedOutcome.SKIPPED)), next.results)
    }

    @Test
    fun `markCurrentUnavailable records unavailable and advances`() {
        val running = GuidedSettingsController.start(listOf("a", "b"), allInstalled) as GuidedSettingsState.Running

        val next = GuidedSettingsController.markCurrentUnavailable(running)

        assertTrue(next is GuidedSettingsState.Running)
        next as GuidedSettingsState.Running
        assertEquals("b", next.current)
        assertEquals(listOf(GuidedResult("a", GuidedOutcome.UNAVAILABLE)), next.results)
    }

    @Test
    fun `advancing onto a run of uninstalled apps skips past all of them in one step`() {
        val running = GuidedSettingsController.start(
            queue = listOf("a", "gone1", "gone2", "b"),
            installedPackages = setOf("a", "b")
        ) as GuidedSettingsState.Running

        val next = GuidedSettingsController.confirmCurrentAndAdvance(running)

        assertTrue(next is GuidedSettingsState.Running)
        next as GuidedSettingsState.Running
        assertEquals("b", next.current)
        assertEquals(
            listOf(
                GuidedResult("a", GuidedOutcome.CONFIRMED_VIA_SETTINGS),
                GuidedResult("gone1", GuidedOutcome.UNAVAILABLE),
                GuidedResult("gone2", GuidedOutcome.UNAVAILABLE)
            ),
            next.results
        )
    }

    @Test
    fun `skipAllRemaining records every remaining app including the current one as skipped`() {
        val running = GuidedSettingsController.start(
            listOf("a", "b", "c"),
            allInstalled
        ) as GuidedSettingsState.Running
        val advanced = GuidedSettingsController.confirmCurrentAndAdvance(running) as GuidedSettingsState.Running // now on "b"

        val complete = GuidedSettingsController.skipAllRemaining(advanced)

        assertEquals(
            listOf(
                GuidedResult("a", GuidedOutcome.CONFIRMED_VIA_SETTINGS),
                GuidedResult("b", GuidedOutcome.SKIPPED),
                GuidedResult("c", GuidedOutcome.SKIPPED)
            ),
            complete.results
        )
    }

    @Test
    fun `cancel records every remaining app including the current one as cancelled`() {
        val running = GuidedSettingsController.start(
            listOf("a", "b", "c"),
            allInstalled
        ) as GuidedSettingsState.Running

        val complete = GuidedSettingsController.cancel(running)

        assertEquals(
            listOf(
                GuidedResult("a", GuidedOutcome.CANCELLED),
                GuidedResult("b", GuidedOutcome.CANCELLED),
                GuidedResult("c", GuidedOutcome.CANCELLED)
            ),
            complete.results
        )
    }

    @Test
    fun `cancel on the last remaining app records just that one`() {
        val running = GuidedSettingsController.start(listOf("a", "b"), allInstalled) as GuidedSettingsState.Running
        val onLast = GuidedSettingsController.confirmCurrentAndAdvance(running) as GuidedSettingsState.Running

        val complete = GuidedSettingsController.cancel(onLast)

        assertEquals(
            listOf(
                GuidedResult("a", GuidedOutcome.CONFIRMED_VIA_SETTINGS),
                GuidedResult("b", GuidedOutcome.CANCELLED)
            ),
            complete.results
        )
    }

    @Test
    fun `a full run through every outcome ends Complete with one result per app in order`() {
        var state = GuidedSettingsController.start(
            queue = listOf("a", "b", "gone", "d"),
            installedPackages = setOf("a", "b", "d")
        )
        state = GuidedSettingsController.confirmCurrentAndAdvance(state as GuidedSettingsState.Running) // a -> confirmed
        state = GuidedSettingsController.skipCurrent(state as GuidedSettingsState.Running) // b -> skipped, auto-skips "gone"

        assertTrue(state is GuidedSettingsState.Running)
        state as GuidedSettingsState.Running
        assertEquals("d", state.current)

        val complete = GuidedSettingsController.confirmCurrentAndAdvance(state)

        assertEquals(
            listOf(
                GuidedResult("a", GuidedOutcome.CONFIRMED_VIA_SETTINGS),
                GuidedResult("b", GuidedOutcome.SKIPPED),
                GuidedResult("gone", GuidedOutcome.UNAVAILABLE),
                GuidedResult("d", GuidedOutcome.CONFIRMED_VIA_SETTINGS)
            ),
            (complete as GuidedSettingsState.Complete).results
        )
    }
}
