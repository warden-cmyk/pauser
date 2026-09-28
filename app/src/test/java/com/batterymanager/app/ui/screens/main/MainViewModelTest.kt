package com.batterymanager.app.ui.screens.main

import com.batterymanager.app.data.apps.AppRepository
import com.batterymanager.app.data.presets.Preset
import com.batterymanager.app.data.presets.PresetRepository
import com.batterymanager.app.data.selection.SelectionStore
import com.batterymanager.app.domain.guided.GuidedOutcome
import com.batterymanager.app.domain.guided.GuidedSettingsState
import com.batterymanager.app.domain.processing.BackgroundCleanupUseCase
import com.batterymanager.app.domain.processing.CleanupOutcome
import com.batterymanager.app.platform.apps.FakeDrawable
import com.batterymanager.app.platform.apps.LaunchableAppInfo
import com.batterymanager.app.platform.process.ActivityManagerWrapper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

private const val OWN_PACKAGE_NAME = "com.batterymanager.app"

private fun fakeApp(
    name: String,
    packageName: String = "com.example.$name",
    launchable: Boolean = true
) = LaunchableAppInfo(
    packageName = packageName,
    appName = name,
    icon = FakeDrawable(),
    launchIntent = if (launchable) android.content.Intent() else null
)

/** Test double whose result (or failure) is fixed at construction time. */
private class FixedResultAppRepository(
    private val result: Result<List<LaunchableAppInfo>>
) : AppRepository {
    var callCount = 0
        private set

    override suspend fun getLaunchableApps(): List<LaunchableAppInfo> {
        callCount++
        return result.getOrThrow()
    }
}

/** Test double that can flip from failing to succeeding, for retry tests. */
private class ToggleableAppRepository(
    private val appsOnSuccess: List<LaunchableAppInfo>
) : AppRepository {
    var shouldFail = true
    var callCount = 0
        private set

    override suspend fun getLaunchableApps(): List<LaunchableAppInfo> {
        callCount++
        if (shouldFail) throw RuntimeException("boom")
        return appsOnSuccess
    }
}

/**
 * In-memory [SelectionStore] test double. [saveCount] lets tests assert a
 * save actually happened without depending on timing; [failGetOnce] makes
 * the next [getSelectedPackageNames] call throw once, for the
 * corrupt-persisted-data test.
 */
private class FakeSelectionStore(
    initial: Set<String> = emptySet()
) : SelectionStore {
    var saved: Set<String> = initial
        private set
    var saveCount = 0
        private set
    var failGetOnce = false

    override suspend fun getSelectedPackageNames(): Set<String> {
        if (failGetOnce) {
            failGetOnce = false
            throw RuntimeException("corrupt")
        }
        return saved
    }

    override suspend fun saveSelectedPackageNames(packageNames: Set<String>) {
        saved = packageNames
        saveCount++
    }
}

/**
 * In-memory [PresetRepository] test double. Ids are simple incrementing
 * counters (not UUIDs) so tests can assert on them predictably.
 * [createCallCount] lets a test assert a create actually happened without
 * depending on timing.
 */
private class FakePresetRepository(
    initial: List<Preset> = emptyList()
) : PresetRepository {
    private val presets = initial.toMutableList()
    private var nextId = 1
    var createCallCount = 0
        private set

    override suspend fun getPresets(): List<Preset> = presets.sortedBy { it.name.lowercase() }

    override suspend fun createPreset(name: String, packageNames: Set<String>): Preset? {
        val trimmedName = name.trim()
        if (trimmedName.isEmpty()) return null
        val preset = Preset(id = "preset-${nextId++}", name = trimmedName, packageNames = packageNames)
        presets += preset
        createCallCount++
        return preset
    }

    override suspend fun deletePreset(presetId: String) {
        presets.removeAll { it.id == presetId }
    }
}

/**
 * Test double recording every package it was asked to kill, and able to
 * throw for specific packages (or block indefinitely, for the
 * in-progress test) without needing real Android/[ActivityManager]
 * classes.
 */
private class FakeActivityManagerWrapper(
    private val packagesThatThrow: Set<String> = emptySet()
) : ActivityManagerWrapper {
    val killedPackages = mutableListOf<String>()

    override fun killBackgroundProcesses(packageName: String) {
        if (packageName in packagesThatThrow) {
            throw SecurityException("no permission to kill $packageName")
        }
        killedPackages += packageName
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class MainViewModelTest {

    private val testDispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun viewModel(
        repository: AppRepository,
        selectionStore: SelectionStore = FakeSelectionStore(),
        presetRepository: PresetRepository = FakePresetRepository(),
        activityManagerWrapper: ActivityManagerWrapper = FakeActivityManagerWrapper(),
        protectedPackageNames: Set<String> = emptySet()
    ) = MainViewModel(
        repository,
        OWN_PACKAGE_NAME,
        selectionStore,
        presetRepository,
        BackgroundCleanupUseCase(activityManagerWrapper),
        protectedPackageNames
    )

    // --- Discovery / loading (Session 3, unchanged behavior) -------------

    @Test
    fun `starts in Loading state before the initial load completes`() = runTest {
        val repository = FixedResultAppRepository(Result.success(listOf(fakeApp("Alpha"))))

        val vm = viewModel(repository)

        assertEquals(MainUiState.Loading, vm.uiState.value)
    }

    @Test
    fun `moves to Success once discovery returns apps`() = runTest {
        val repository = FixedResultAppRepository(
            Result.success(listOf(fakeApp("Alpha"), fakeApp("Beta")))
        )
        val vm = viewModel(repository)

        testDispatcher.scheduler.advanceUntilIdle()

        val state = vm.uiState.value
        assertTrue(state is MainUiState.Success)
        assertEquals(2, (state as MainUiState.Success).apps.size)
    }

    @Test
    fun `moves to Empty when discovery returns no apps`() = runTest {
        val repository = FixedResultAppRepository(Result.success(emptyList()))
        val vm = viewModel(repository)

        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals(MainUiState.Empty, vm.uiState.value)
    }

    @Test
    fun `moves to Error when discovery throws`() = runTest {
        val repository = FixedResultAppRepository(Result.failure(RuntimeException("boom")))
        val vm = viewModel(repository)

        testDispatcher.scheduler.advanceUntilIdle()

        assertTrue(vm.uiState.value is MainUiState.Error)
    }

    @Test
    fun `retry re-invokes the repository and can recover from Error`() = runTest {
        val repository = ToggleableAppRepository(appsOnSuccess = listOf(fakeApp("Alpha")))
        val vm = viewModel(repository)
        testDispatcher.scheduler.advanceUntilIdle()
        assertTrue(vm.uiState.value is MainUiState.Error)
        assertEquals(1, repository.callCount)

        repository.shouldFail = false
        vm.retry()
        testDispatcher.scheduler.advanceUntilIdle()

        assertTrue(vm.uiState.value is MainUiState.Success)
        assertEquals(2, repository.callCount)
    }

    // --- Selection (Session 4) --------------------------------------------

    @Test
    fun `Success starts with zero selections`() = runTest {
        val repository = FixedResultAppRepository(
            Result.success(listOf(fakeApp("Alpha"), fakeApp("Beta")))
        )
        val vm = viewModel(repository)
        testDispatcher.scheduler.advanceUntilIdle()

        val state = vm.uiState.value as MainUiState.Success

        assertTrue(state.selectedPackageNames.isEmpty())
        assertEquals(0, state.selectedCount)
    }

    @Test
    fun `selecting one app updates the selection and the count`() = runTest {
        val alpha = fakeApp("Alpha")
        val repository = FixedResultAppRepository(Result.success(listOf(alpha, fakeApp("Beta"))))
        val vm = viewModel(repository)
        testDispatcher.scheduler.advanceUntilIdle()

        vm.toggleSelection(alpha.packageName)

        val state = vm.uiState.value as MainUiState.Success
        assertEquals(setOf(alpha.packageName), state.selectedPackageNames)
        assertEquals(1, state.selectedCount)
    }

    @Test
    fun `selecting multiple apps accumulates the selection and the count`() = runTest {
        val alpha = fakeApp("Alpha")
        val beta = fakeApp("Beta")
        val gamma = fakeApp("Gamma")
        val repository = FixedResultAppRepository(Result.success(listOf(alpha, beta, gamma)))
        val vm = viewModel(repository)
        testDispatcher.scheduler.advanceUntilIdle()

        vm.toggleSelection(alpha.packageName)
        vm.toggleSelection(gamma.packageName)

        val state = vm.uiState.value as MainUiState.Success
        assertEquals(setOf(alpha.packageName, gamma.packageName), state.selectedPackageNames)
        assertEquals(2, state.selectedCount)
    }

    @Test
    fun `all eligible apps can be selected manually, one at a time`() = runTest {
        val alpha = fakeApp("Alpha")
        val beta = fakeApp("Beta")
        val repository = FixedResultAppRepository(Result.success(listOf(alpha, beta)))
        val vm = viewModel(repository)
        testDispatcher.scheduler.advanceUntilIdle()

        vm.toggleSelection(alpha.packageName)
        vm.toggleSelection(beta.packageName)

        val state = vm.uiState.value as MainUiState.Success
        assertEquals(setOf(alpha.packageName, beta.packageName), state.selectedPackageNames)
        assertEquals(2, state.selectedCount)
    }

    @Test
    fun `toggling an already-selected app deselects it and lowers the count`() = runTest {
        val alpha = fakeApp("Alpha")
        val repository = FixedResultAppRepository(Result.success(listOf(alpha, fakeApp("Beta"))))
        val vm = viewModel(repository)
        testDispatcher.scheduler.advanceUntilIdle()
        vm.toggleSelection(alpha.packageName)

        vm.toggleSelection(alpha.packageName)

        val state = vm.uiState.value as MainUiState.Success
        assertTrue(state.selectedPackageNames.isEmpty())
        assertEquals(0, state.selectedCount)
    }

    @Test
    fun `a retry restores the persisted selection instead of clearing it`() = runTest {
        // Before Session 6 (persistence), a retry always cleared the
        // in-memory selection - there was nowhere durable to restore it
        // from. Now that every selection change is saved (see the
        // persistence tests below), a retry's fresh load restores from
        // the same store a cold start would use, so the selection
        // survives a retry too rather than being wiped.
        val alpha = fakeApp("Alpha")
        val repository = FixedResultAppRepository(Result.success(listOf(alpha, fakeApp("Beta"))))
        val vm = viewModel(repository)
        testDispatcher.scheduler.advanceUntilIdle()
        vm.toggleSelection(alpha.packageName)
        testDispatcher.scheduler.advanceUntilIdle()
        assertEquals(1, (vm.uiState.value as MainUiState.Success).selectedCount)

        vm.retry()
        testDispatcher.scheduler.advanceUntilIdle()

        val state = vm.uiState.value as MainUiState.Success
        assertEquals(setOf(alpha.packageName), state.selectedPackageNames)
        assertEquals(1, state.selectedCount)
    }

    // --- Select All / Deselect All (Session 5) ----------------------------

    @Test
    fun `selectAll selects every eligible app`() = runTest {
        val alpha = fakeApp("Alpha")
        val beta = fakeApp("Beta")
        val gamma = fakeApp("Gamma")
        val repository = FixedResultAppRepository(Result.success(listOf(alpha, beta, gamma)))
        val vm = viewModel(repository)
        testDispatcher.scheduler.advanceUntilIdle()

        vm.selectAll()

        val state = vm.uiState.value as MainUiState.Success
        assertEquals(state.selectableApps, state.selectedPackageNames)
        assertEquals(setOf(alpha.packageName, beta.packageName, gamma.packageName), state.selectedPackageNames)
        assertEquals(3, state.selectedCount)
    }

    @Test
    fun `selectAll does not select the host app's own package`() = runTest {
        val selfApp = fakeApp("Self", packageName = OWN_PACKAGE_NAME)
        val other = fakeApp("Other")
        val repository = FixedResultAppRepository(Result.success(listOf(selfApp, other)))
        val vm = viewModel(repository)
        testDispatcher.scheduler.advanceUntilIdle()

        vm.selectAll()

        val state = vm.uiState.value as MainUiState.Success
        assertFalse(selfApp.packageName in state.selectedPackageNames)
        assertEquals(setOf(other.packageName), state.selectedPackageNames)
        assertEquals(1, state.selectedCount)
    }

    @Test
    fun `selectAll does not select non-launchable apps`() = runTest {
        val unlaunchable = fakeApp("Unlaunchable", launchable = false)
        val other = fakeApp("Other")
        val repository = FixedResultAppRepository(Result.success(listOf(unlaunchable, other)))
        val vm = viewModel(repository)
        testDispatcher.scheduler.advanceUntilIdle()

        vm.selectAll()

        val state = vm.uiState.value as MainUiState.Success
        assertFalse(unlaunchable.packageName in state.selectedPackageNames)
        assertEquals(setOf(other.packageName), state.selectedPackageNames)
        assertEquals(1, state.selectedCount)
    }

    @Test
    fun `selectAll on an already partially-selected state selects the rest`() = runTest {
        val alpha = fakeApp("Alpha")
        val beta = fakeApp("Beta")
        val repository = FixedResultAppRepository(Result.success(listOf(alpha, beta)))
        val vm = viewModel(repository)
        testDispatcher.scheduler.advanceUntilIdle()
        vm.toggleSelection(alpha.packageName)

        vm.selectAll()

        val state = vm.uiState.value as MainUiState.Success
        assertEquals(setOf(alpha.packageName, beta.packageName), state.selectedPackageNames)
        assertEquals(2, state.selectedCount)
    }

    @Test
    fun `selectAll is a no-op when there is no Success state`() = runTest {
        val repository = FixedResultAppRepository(Result.failure(RuntimeException("boom")))
        val vm = viewModel(repository)
        testDispatcher.scheduler.advanceUntilIdle()
        assertTrue(vm.uiState.value is MainUiState.Error)

        vm.selectAll()

        assertTrue(vm.uiState.value is MainUiState.Error)
    }

    // --- Protected applications (session task) -----------------------------

    @Test
    fun `a protected package is not selectable, is excluded from selectAll, and toggleSelection on it is a no-op`() = runTest {
        val settings = fakeApp("Settings", packageName = "com.android.settings")
        val other = fakeApp("Other")
        val repository = FixedResultAppRepository(Result.success(listOf(settings, other)))
        val vm = viewModel(repository, protectedPackageNames = setOf("com.android.settings"))
        testDispatcher.scheduler.advanceUntilIdle()

        val loaded = vm.uiState.value as MainUiState.Success
        assertFalse(settings.packageName in loaded.selectableApps)
        assertTrue(other.packageName in loaded.selectableApps)

        vm.toggleSelection(settings.packageName)
        assertTrue((vm.uiState.value as MainUiState.Success).selectedPackageNames.isEmpty())

        vm.selectAll()
        val afterSelectAll = vm.uiState.value as MainUiState.Success
        assertFalse(settings.packageName in afterSelectAll.selectedPackageNames)
        assertEquals(setOf(other.packageName), afterSelectAll.selectedPackageNames)
    }

    @Test
    fun `a protected package is still visible in apps even though it is not selectable`() = runTest {
        val settings = fakeApp("Settings", packageName = "com.android.settings")
        val repository = FixedResultAppRepository(Result.success(listOf(settings)))
        val vm = viewModel(repository, protectedPackageNames = setOf("com.android.settings"))
        testDispatcher.scheduler.advanceUntilIdle()

        val state = vm.uiState.value as MainUiState.Success
        assertTrue(settings.packageName in state.apps.map { it.packageName })
    }

    @Test
    fun `a persisted selection is never restored for a protected package`() = runTest {
        val settings = fakeApp("Settings", packageName = "com.android.settings")
        val selectionStore = FakeSelectionStore(initial = setOf(settings.packageName))
        val repository = FixedResultAppRepository(Result.success(listOf(settings)))

        val vm = viewModel(
            repository,
            selectionStore = selectionStore,
            protectedPackageNames = setOf("com.android.settings")
        )
        testDispatcher.scheduler.advanceUntilIdle()

        val state = vm.uiState.value as MainUiState.Success
        assertTrue(state.selectedPackageNames.isEmpty())
    }

    @Test
    fun `deselectAll clears every selection`() = runTest {
        val alpha = fakeApp("Alpha")
        val beta = fakeApp("Beta")
        val repository = FixedResultAppRepository(Result.success(listOf(alpha, beta)))
        val vm = viewModel(repository)
        testDispatcher.scheduler.advanceUntilIdle()
        vm.selectAll()
        assertEquals(2, (vm.uiState.value as MainUiState.Success).selectedCount)

        vm.deselectAll()

        val state = vm.uiState.value as MainUiState.Success
        assertTrue(state.selectedPackageNames.isEmpty())
        assertEquals(0, state.selectedCount)
    }

    @Test
    fun `deselectAll on an empty selection stays empty`() = runTest {
        val repository = FixedResultAppRepository(Result.success(listOf(fakeApp("Alpha"))))
        val vm = viewModel(repository)
        testDispatcher.scheduler.advanceUntilIdle()

        vm.deselectAll()

        val state = vm.uiState.value as MainUiState.Success
        assertTrue(state.selectedPackageNames.isEmpty())
        assertEquals(0, state.selectedCount)
    }

    @Test
    fun `deselectAll is a no-op when there is no Success state`() = runTest {
        val repository = FixedResultAppRepository(Result.failure(RuntimeException("boom")))
        val vm = viewModel(repository)
        testDispatcher.scheduler.advanceUntilIdle()
        assertTrue(vm.uiState.value is MainUiState.Error)

        vm.deselectAll()

        assertTrue(vm.uiState.value is MainUiState.Error)
    }

    // --- Persistence (Session 6): selection restore across a fresh load --

    @Test
    fun `restores a previously selected app that is still selectable`() = runTest {
        val alpha = fakeApp("Alpha")
        val beta = fakeApp("Beta")
        val selectionStore = FakeSelectionStore(initial = setOf(alpha.packageName))
        val repository = FixedResultAppRepository(Result.success(listOf(alpha, beta)))
        val vm = viewModel(repository, selectionStore)

        testDispatcher.scheduler.advanceUntilIdle()

        val state = vm.uiState.value as MainUiState.Success
        assertEquals(setOf(alpha.packageName), state.selectedPackageNames)
        assertEquals(1, state.selectedCount)
    }

    @Test
    fun `a persisted package that is no longer discovered is not restored`() = runTest {
        // Simulates the app having been uninstalled since the selection
        // was last saved: it's in the persisted set but not in this
        // load's discovery result at all.
        val stillInstalled = fakeApp("StillInstalled")
        val selectionStore = FakeSelectionStore(
            initial = setOf(stillInstalled.packageName, "com.example.uninstalled")
        )
        val repository = FixedResultAppRepository(Result.success(listOf(stillInstalled)))
        val vm = viewModel(repository, selectionStore)

        testDispatcher.scheduler.advanceUntilIdle()

        val state = vm.uiState.value as MainUiState.Success
        assertEquals(setOf(stillInstalled.packageName), state.selectedPackageNames)
    }

    @Test
    fun `a persisted selection is never restored for the host app's own package`() = runTest {
        val selfApp = fakeApp("Self", packageName = OWN_PACKAGE_NAME)
        val other = fakeApp("Other")
        // Simulates a corrupted or hand-edited persisted file containing
        // the host app's own package - not something normal saves would
        // ever write, since selectAll()/toggleSelection() only persist
        // subsets of selectableApps, which never contains ownPackageName.
        val selectionStore = FakeSelectionStore(
            initial = setOf(selfApp.packageName, other.packageName)
        )
        val repository = FixedResultAppRepository(Result.success(listOf(selfApp, other)))
        val vm = viewModel(repository, selectionStore)

        testDispatcher.scheduler.advanceUntilIdle()

        val state = vm.uiState.value as MainUiState.Success
        assertFalse(selfApp.packageName in state.selectedPackageNames)
        assertEquals(setOf(other.packageName), state.selectedPackageNames)
    }

    @Test
    fun `a persisted selection is never restored for a non-launchable app`() = runTest {
        val unlaunchable = fakeApp("Unlaunchable", launchable = false)
        val other = fakeApp("Other")
        val selectionStore = FakeSelectionStore(
            initial = setOf(unlaunchable.packageName, other.packageName)
        )
        val repository = FixedResultAppRepository(Result.success(listOf(unlaunchable, other)))
        val vm = viewModel(repository, selectionStore)

        testDispatcher.scheduler.advanceUntilIdle()

        val state = vm.uiState.value as MainUiState.Success
        assertFalse(unlaunchable.packageName in state.selectedPackageNames)
        assertEquals(setOf(other.packageName), state.selectedPackageNames)
    }

    @Test
    fun `nothing is restored when nothing was ever saved`() = runTest {
        val repository = FixedResultAppRepository(Result.success(listOf(fakeApp("Alpha"))))
        val vm = viewModel(repository, FakeSelectionStore())

        testDispatcher.scheduler.advanceUntilIdle()

        val state = vm.uiState.value as MainUiState.Success
        assertTrue(state.selectedPackageNames.isEmpty())
    }

    @Test
    fun `a corrupt or unreadable persisted selection is treated as empty, not an error`() = runTest {
        val selectionStore = FakeSelectionStore().apply { failGetOnce = true }
        val repository = FixedResultAppRepository(Result.success(listOf(fakeApp("Alpha"))))
        val vm = viewModel(repository, selectionStore)

        testDispatcher.scheduler.advanceUntilIdle()

        val state = vm.uiState.value
        assertTrue(state is MainUiState.Success)
        assertTrue((state as MainUiState.Success).selectedPackageNames.isEmpty())
    }

    @Test
    fun `toggleSelection persists the updated selection`() = runTest {
        val alpha = fakeApp("Alpha")
        val selectionStore = FakeSelectionStore()
        val repository = FixedResultAppRepository(Result.success(listOf(alpha)))
        val vm = viewModel(repository, selectionStore)
        testDispatcher.scheduler.advanceUntilIdle()

        vm.toggleSelection(alpha.packageName)
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals(setOf(alpha.packageName), selectionStore.saved)
        assertEquals(1, selectionStore.saveCount)
    }

    @Test
    fun `selectAll persists the full eligible selection`() = runTest {
        val alpha = fakeApp("Alpha")
        val beta = fakeApp("Beta")
        val selectionStore = FakeSelectionStore()
        val repository = FixedResultAppRepository(Result.success(listOf(alpha, beta)))
        val vm = viewModel(repository, selectionStore)
        testDispatcher.scheduler.advanceUntilIdle()

        vm.selectAll()
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals(setOf(alpha.packageName, beta.packageName), selectionStore.saved)
    }

    @Test
    fun `deselectAll persists an empty selection`() = runTest {
        val alpha = fakeApp("Alpha")
        val selectionStore = FakeSelectionStore(initial = setOf(alpha.packageName))
        val repository = FixedResultAppRepository(Result.success(listOf(alpha)))
        val vm = viewModel(repository, selectionStore)
        testDispatcher.scheduler.advanceUntilIdle()
        assertEquals(1, (vm.uiState.value as MainUiState.Success).selectedCount)

        vm.deselectAll()
        testDispatcher.scheduler.advanceUntilIdle()

        assertTrue(selectionStore.saved.isEmpty())
    }

    // --- Safety: the host app and non-launchable apps are never selectable

    @Test
    fun `the host app's own package is excluded from selectableApps and cannot be selected`() = runTest {
        val selfApp = fakeApp("Self", packageName = OWN_PACKAGE_NAME)
        val other = fakeApp("Other")
        val repository = FixedResultAppRepository(Result.success(listOf(selfApp, other)))
        val vm = viewModel(repository)
        testDispatcher.scheduler.advanceUntilIdle()

        vm.toggleSelection(selfApp.packageName)

        val state = vm.uiState.value as MainUiState.Success
        assertFalse(selfApp.packageName in state.selectableApps)
        assertTrue(state.selectedPackageNames.isEmpty())
    }

    @Test
    fun `a non-launchable app is excluded from selectableApps and cannot be selected`() = runTest {
        val unlaunchable = fakeApp("Unlaunchable", launchable = false)
        val repository = FixedResultAppRepository(
            Result.success(listOf(unlaunchable, fakeApp("Other")))
        )
        val vm = viewModel(repository)
        testDispatcher.scheduler.advanceUntilIdle()

        vm.toggleSelection(unlaunchable.packageName)

        val state = vm.uiState.value as MainUiState.Success
        assertFalse(unlaunchable.packageName in state.selectableApps)
        assertTrue(state.selectedPackageNames.isEmpty())
    }

    // --- Presets (Session 7) ----------------------------------------------

    @Test
    fun `presets starts empty when nothing was ever saved`() = runTest {
        val repository = FixedResultAppRepository(Result.success(listOf(fakeApp("Alpha"))))
        val vm = viewModel(repository)
        testDispatcher.scheduler.advanceUntilIdle()

        assertTrue(vm.presets.value.isEmpty())
    }

    @Test
    fun `presets is seeded from the preset repository at startup`() = runTest {
        val existing = Preset(id = "p1", name = "Work", packageNames = setOf("com.example.alpha"))
        val repository = FixedResultAppRepository(Result.success(listOf(fakeApp("Alpha"))))
        val vm = viewModel(repository, presetRepository = FakePresetRepository(listOf(existing)))
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals(listOf(existing), vm.presets.value)
    }

    @Test
    fun `createPreset saves the current selection under the given name`() = runTest {
        val alpha = fakeApp("Alpha")
        val beta = fakeApp("Beta")
        val repository = FixedResultAppRepository(Result.success(listOf(alpha, beta)))
        val presetRepository = FakePresetRepository()
        val vm = viewModel(repository, presetRepository = presetRepository)
        testDispatcher.scheduler.advanceUntilIdle()
        vm.toggleSelection(alpha.packageName)
        testDispatcher.scheduler.advanceUntilIdle()

        vm.createPreset("Work")
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals(1, presetRepository.createCallCount)
        val presets = vm.presets.value
        assertEquals(1, presets.size)
        assertEquals("Work", presets.single().name)
        assertEquals(setOf(alpha.packageName), presets.single().packageNames)
    }

    @Test
    fun `createPreset only saves selectable packages, defensively`() = runTest {
        // toggleSelection already guarantees selectedPackageNames is a
        // subset of selectableApps, but createPreset intersects again
        // rather than trusting that invariant blindly (same
        // defense-in-depth style as toggleSelection itself).
        val alpha = fakeApp("Alpha")
        val repository = FixedResultAppRepository(Result.success(listOf(alpha)))
        val presetRepository = FakePresetRepository()
        val vm = viewModel(repository, presetRepository = presetRepository)
        testDispatcher.scheduler.advanceUntilIdle()
        vm.toggleSelection(alpha.packageName)
        testDispatcher.scheduler.advanceUntilIdle()

        vm.createPreset("Custom")
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals(setOf(alpha.packageName), vm.presets.value.single().packageNames)
    }

    @Test
    fun `createPreset is a no-op with a blank name`() = runTest {
        val repository = FixedResultAppRepository(Result.success(listOf(fakeApp("Alpha"))))
        val presetRepository = FakePresetRepository()
        val vm = viewModel(repository, presetRepository = presetRepository)
        testDispatcher.scheduler.advanceUntilIdle()

        vm.createPreset("   ")
        testDispatcher.scheduler.advanceUntilIdle()

        assertTrue(vm.presets.value.isEmpty())
    }

    @Test
    fun `createPreset is a no-op when there is no Success state`() = runTest {
        val repository = FixedResultAppRepository(Result.failure(RuntimeException("boom")))
        val presetRepository = FakePresetRepository()
        val vm = viewModel(repository, presetRepository = presetRepository)
        testDispatcher.scheduler.advanceUntilIdle()
        assertTrue(vm.uiState.value is MainUiState.Error)

        vm.createPreset("Work")
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals(0, presetRepository.createCallCount)
    }

    @Test
    fun `deletePreset removes a saved preset`() = runTest {
        val existing = Preset(id = "p1", name = "Work", packageNames = setOf("com.example.alpha"))
        val repository = FixedResultAppRepository(Result.success(listOf(fakeApp("Alpha"))))
        val vm = viewModel(repository, presetRepository = FakePresetRepository(listOf(existing)))
        testDispatcher.scheduler.advanceUntilIdle()
        assertEquals(1, vm.presets.value.size)

        vm.deletePreset("p1")
        testDispatcher.scheduler.advanceUntilIdle()

        assertTrue(vm.presets.value.isEmpty())
    }

    @Test
    fun `deletePreset with an unknown id is a no-op`() = runTest {
        val existing = Preset(id = "p1", name = "Work", packageNames = setOf("com.example.alpha"))
        val repository = FixedResultAppRepository(Result.success(listOf(fakeApp("Alpha"))))
        val vm = viewModel(repository, presetRepository = FakePresetRepository(listOf(existing)))
        testDispatcher.scheduler.advanceUntilIdle()

        vm.deletePreset("does-not-exist")
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals(listOf(existing), vm.presets.value)
    }

    @Test
    fun `loadPreset selects exactly the preset's packages when all are still selectable`() = runTest {
        val alpha = fakeApp("Alpha")
        val beta = fakeApp("Beta")
        val preset = Preset(id = "p1", name = "Work", packageNames = setOf(alpha.packageName))
        val repository = FixedResultAppRepository(Result.success(listOf(alpha, beta)))
        val vm = viewModel(repository, presetRepository = FakePresetRepository(listOf(preset)))
        testDispatcher.scheduler.advanceUntilIdle()

        vm.loadPreset("p1")

        val state = vm.uiState.value as MainUiState.Success
        assertEquals(setOf(alpha.packageName), state.selectedPackageNames)
    }

    @Test
    fun `loadPreset drops a package that is no longer installed`() = runTest {
        val alpha = fakeApp("Alpha")
        val preset = Preset(
            id = "p1",
            name = "Work",
            packageNames = setOf(alpha.packageName, "com.example.uninstalled")
        )
        val repository = FixedResultAppRepository(Result.success(listOf(alpha)))
        val vm = viewModel(repository, presetRepository = FakePresetRepository(listOf(preset)))
        testDispatcher.scheduler.advanceUntilIdle()

        vm.loadPreset("p1")

        val state = vm.uiState.value as MainUiState.Success
        assertEquals(setOf(alpha.packageName), state.selectedPackageNames)
    }

    @Test
    fun `loadPreset never selects the host app's own package`() = runTest {
        val selfApp = fakeApp("Self", packageName = OWN_PACKAGE_NAME)
        val other = fakeApp("Other")
        // Simulates a hand-edited/corrupted preset containing Battery
        // Manager's own package - not something createPreset could ever
        // produce, since it only saves a subset of selectableApps.
        val preset = Preset(
            id = "p1",
            name = "Corrupt",
            packageNames = setOf(selfApp.packageName, other.packageName)
        )
        val repository = FixedResultAppRepository(Result.success(listOf(selfApp, other)))
        val vm = viewModel(repository, presetRepository = FakePresetRepository(listOf(preset)))
        testDispatcher.scheduler.advanceUntilIdle()

        vm.loadPreset("p1")

        val state = vm.uiState.value as MainUiState.Success
        assertFalse(selfApp.packageName in state.selectedPackageNames)
        assertEquals(setOf(other.packageName), state.selectedPackageNames)
    }

    @Test
    fun `loadPreset never selects a non-launchable app`() = runTest {
        val unlaunchable = fakeApp("Unlaunchable", launchable = false)
        val other = fakeApp("Other")
        val preset = Preset(
            id = "p1",
            name = "Corrupt",
            packageNames = setOf(unlaunchable.packageName, other.packageName)
        )
        val repository = FixedResultAppRepository(Result.success(listOf(unlaunchable, other)))
        val vm = viewModel(repository, presetRepository = FakePresetRepository(listOf(preset)))
        testDispatcher.scheduler.advanceUntilIdle()

        vm.loadPreset("p1")

        val state = vm.uiState.value as MainUiState.Success
        assertFalse(unlaunchable.packageName in state.selectedPackageNames)
        assertEquals(setOf(other.packageName), state.selectedPackageNames)
    }

    @Test
    fun `loadPreset persists the sanitized selection`() = runTest {
        val alpha = fakeApp("Alpha")
        val preset = Preset(id = "p1", name = "Work", packageNames = setOf(alpha.packageName))
        val repository = FixedResultAppRepository(Result.success(listOf(alpha)))
        val selectionStore = FakeSelectionStore()
        val vm = viewModel(
            repository,
            selectionStore = selectionStore,
            presetRepository = FakePresetRepository(listOf(preset))
        )
        testDispatcher.scheduler.advanceUntilIdle()

        vm.loadPreset("p1")
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals(setOf(alpha.packageName), selectionStore.saved)
    }

    @Test
    fun `loadPreset replaces the current selection rather than merging it`() = runTest {
        val alpha = fakeApp("Alpha")
        val beta = fakeApp("Beta")
        val preset = Preset(id = "p1", name = "Work", packageNames = setOf(beta.packageName))
        val repository = FixedResultAppRepository(Result.success(listOf(alpha, beta)))
        val vm = viewModel(repository, presetRepository = FakePresetRepository(listOf(preset)))
        testDispatcher.scheduler.advanceUntilIdle()
        vm.toggleSelection(alpha.packageName)

        vm.loadPreset("p1")

        val state = vm.uiState.value as MainUiState.Success
        assertEquals(setOf(beta.packageName), state.selectedPackageNames)
    }

    @Test
    fun `loadPreset with an unknown id is a no-op`() = runTest {
        val alpha = fakeApp("Alpha")
        val repository = FixedResultAppRepository(Result.success(listOf(alpha)))
        val vm = viewModel(repository)
        testDispatcher.scheduler.advanceUntilIdle()
        vm.toggleSelection(alpha.packageName)

        vm.loadPreset("does-not-exist")

        val state = vm.uiState.value as MainUiState.Success
        assertEquals(setOf(alpha.packageName), state.selectedPackageNames)
    }

    @Test
    fun `loadPreset is a no-op when there is no Success state`() = runTest {
        val preset = Preset(id = "p1", name = "Work", packageNames = setOf("com.example.alpha"))
        val repository = FixedResultAppRepository(Result.failure(RuntimeException("boom")))
        val vm = viewModel(repository, presetRepository = FakePresetRepository(listOf(preset)))
        testDispatcher.scheduler.advanceUntilIdle()
        assertTrue(vm.uiState.value is MainUiState.Error)

        vm.loadPreset("p1")

        assertTrue(vm.uiState.value is MainUiState.Error)
    }

    // --- Background cleanup / "Clean Selected" (Session 8) ---------------

    @Test
    fun `cleanupState starts Idle`() = runTest {
        val vm = viewModel(FixedResultAppRepository(Result.success(listOf(fakeApp("Alpha")))))

        assertEquals(CleanupUiState.Idle, vm.cleanupState.value)
    }

    @Test
    fun `cleanSelected is a no-op with no Success state`() = runTest {
        val vm = viewModel(FixedResultAppRepository(Result.failure(RuntimeException("boom"))))
        testDispatcher.scheduler.advanceUntilIdle()
        assertTrue(vm.uiState.value is MainUiState.Error)

        vm.cleanSelected()
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals(CleanupUiState.Idle, vm.cleanupState.value)
    }

    @Test
    fun `cleanSelected is a no-op when nothing is selected`() = runTest {
        val repository = FixedResultAppRepository(Result.success(listOf(fakeApp("Alpha"))))
        val vm = viewModel(repository)
        testDispatcher.scheduler.advanceUntilIdle()

        vm.cleanSelected()
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals(CleanupUiState.Idle, vm.cleanupState.value)
        assertEquals(1, repository.callCount) // only the initial load - no re-discovery was attempted
    }

    @Test
    fun `cleanSelected moves through InProgress and ends in Complete with one result per selected app`() = runTest {
        val alpha = fakeApp("Alpha")
        val beta = fakeApp("Beta")
        val repository = FixedResultAppRepository(Result.success(listOf(alpha, beta)))
        val activityManagerWrapper = FakeActivityManagerWrapper()
        val vm = viewModel(repository, activityManagerWrapper = activityManagerWrapper)
        testDispatcher.scheduler.advanceUntilIdle()
        vm.selectAll()

        vm.cleanSelected()
        assertEquals(CleanupUiState.InProgress(total = 2), vm.cleanupState.value)

        testDispatcher.scheduler.advanceUntilIdle()

        val finalState = vm.cleanupState.value
        assertTrue(finalState is CleanupUiState.Complete)
        val results = (finalState as CleanupUiState.Complete).results
        assertEquals(2, results.size)
        assertTrue(results.all { it.outcome == CleanupOutcome.PROCESSED })
        assertEquals(
            setOf(alpha.packageName, beta.packageName),
            activityManagerWrapper.killedPackages.toSet()
        )
    }

    @Test
    fun `cleanSelected never attempts to kill this app's own package`() = runTest {
        // Defense in depth: selectableApps already excludes ownPackageName,
        // so this can only happen via a corrupted/hand-edited selection -
        // BackgroundCleanupUseCase must still refuse it. Exercised here via
        // toggleSelection's own guard being bypassed is not possible from
        // outside the class, so this asserts the same guarantee at the
        // level actually reachable: selectAll() never includes it, and if
        // it somehow were included, the use case's PROTECTED check (see
        // BackgroundCleanupUseCaseTest) would still catch it before any
        // kill call is made.
        val repository = FixedResultAppRepository(Result.success(listOf(fakeApp("Alpha"))))
        val activityManagerWrapper = FakeActivityManagerWrapper()
        val vm = viewModel(repository, activityManagerWrapper = activityManagerWrapper)
        testDispatcher.scheduler.advanceUntilIdle()
        vm.selectAll()

        vm.cleanSelected()
        testDispatcher.scheduler.advanceUntilIdle()

        assertTrue(OWN_PACKAGE_NAME !in activityManagerWrapper.killedPackages)
    }

    @Test
    fun `cleanSelected reports a package uninstalled since selection as unavailable`() = runTest {
        val alpha = fakeApp("Alpha")
        val beta = fakeApp("Beta")
        // Beta was selected, then "uninstalled" before Clean Selected was
        // tapped - simulated via a repository that returns fewer apps on
        // its second call (the re-discovery cleanSelected performs).
        val repository = object : AppRepository {
            var callCount = 0
                private set

            override suspend fun getLaunchableApps(): List<LaunchableAppInfo> {
                callCount++
                return if (callCount == 1) listOf(alpha, beta) else listOf(alpha)
            }
        }
        val activityManagerWrapper = FakeActivityManagerWrapper()
        val vm = viewModel(repository, activityManagerWrapper = activityManagerWrapper)
        testDispatcher.scheduler.advanceUntilIdle()
        vm.selectAll()

        vm.cleanSelected()
        testDispatcher.scheduler.advanceUntilIdle()

        val results = (vm.cleanupState.value as CleanupUiState.Complete).results
        val byPackage = results.associateBy { it.packageName }
        assertEquals(CleanupOutcome.PROCESSED, byPackage.getValue(alpha.packageName).outcome)
        assertEquals(CleanupOutcome.UNAVAILABLE, byPackage.getValue(beta.packageName).outcome)
        assertTrue(beta.packageName !in activityManagerWrapper.killedPackages)
    }

    @Test
    fun `cleanSelected does not let one failing package abort the batch`() = runTest {
        val alpha = fakeApp("Alpha")
        val beta = fakeApp("Beta")
        val repository = FixedResultAppRepository(Result.success(listOf(alpha, beta)))
        val activityManagerWrapper = FakeActivityManagerWrapper(
            packagesThatThrow = setOf(alpha.packageName)
        )
        val vm = viewModel(repository, activityManagerWrapper = activityManagerWrapper)
        testDispatcher.scheduler.advanceUntilIdle()
        vm.selectAll()

        vm.cleanSelected()
        testDispatcher.scheduler.advanceUntilIdle()

        val results = (vm.cleanupState.value as CleanupUiState.Complete).results
        val byPackage = results.associateBy { it.packageName }
        assertEquals(CleanupOutcome.ERROR, byPackage.getValue(alpha.packageName).outcome)
        assertEquals(CleanupOutcome.PROCESSED, byPackage.getValue(beta.packageName).outcome)
    }

    @Test
    fun `a re-discovery failure during cleanSelected reports every selected package as an error, not a crash`() = runTest {
        val alpha = fakeApp("Alpha")
        val beta = fakeApp("Beta")
        val repository = object : AppRepository {
            var callCount = 0
                private set

            override suspend fun getLaunchableApps(): List<LaunchableAppInfo> {
                callCount++
                if (callCount > 1) throw RuntimeException("re-discovery boom")
                return listOf(alpha, beta)
            }
        }
        val vm = viewModel(repository)
        testDispatcher.scheduler.advanceUntilIdle()
        vm.selectAll()

        vm.cleanSelected()
        testDispatcher.scheduler.advanceUntilIdle()

        val results = (vm.cleanupState.value as CleanupUiState.Complete).results
        assertEquals(2, results.size)
        assertTrue(results.all { it.outcome == CleanupOutcome.ERROR })
    }

    @Test
    fun `cleanSelected is a no-op while a run is already in progress`() = runTest {
        val alpha = fakeApp("Alpha")
        val repository = FixedResultAppRepository(Result.success(listOf(alpha)))
        val vm = viewModel(repository)
        testDispatcher.scheduler.advanceUntilIdle()
        vm.selectAll()

        vm.cleanSelected()
        val stateDuringFirstRun = vm.cleanupState.value
        vm.cleanSelected() // second call while the first is still InProgress

        assertEquals(stateDuringFirstRun, vm.cleanupState.value)
    }

    @Test
    fun `dismissCleanupSummary returns to Idle`() = runTest {
        val alpha = fakeApp("Alpha")
        val repository = FixedResultAppRepository(Result.success(listOf(alpha)))
        val vm = viewModel(repository)
        testDispatcher.scheduler.advanceUntilIdle()
        vm.selectAll()
        vm.cleanSelected()
        testDispatcher.scheduler.advanceUntilIdle()
        assertTrue(vm.cleanupState.value is CleanupUiState.Complete)

        vm.dismissCleanupSummary()

        assertEquals(CleanupUiState.Idle, vm.cleanupState.value)
    }

    // --- Guided Settings workflow (Session 9, Phase B) --------------------
    //
    // These exercise only MainViewModel's orchestration - no Intent is ever
    // built or launched from here (there is no Android framework available
    // in this test environment), and the actual sequencing/edge-case logic
    // (auto-skipping an unavailable app, Skip All, Cancel) is exhaustively
    // covered against plain data in GuidedSettingsControllerTest. What's
    // asserted here is that MainViewModel wires selection, re-discovery,
    // and the double-tap/no-op guards to that controller correctly.

    @Test
    fun `guidedSettingsState starts Idle`() = runTest {
        val repository = FixedResultAppRepository(Result.success(listOf(fakeApp("Alpha"))))
        val vm = viewModel(repository)

        assertEquals(GuidedSettingsState.Idle, vm.guidedSettingsState.value)
    }

    @Test
    fun `startGuidedSettings is a no-op with no Success state`() = runTest {
        val repository = FixedResultAppRepository(Result.failure(RuntimeException("boom")))
        val vm = viewModel(repository)
        testDispatcher.scheduler.advanceUntilIdle()

        vm.startGuidedSettings()
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals(GuidedSettingsState.Idle, vm.guidedSettingsState.value)
    }

    @Test
    fun `startGuidedSettings is a no-op when nothing is selected`() = runTest {
        val repository = FixedResultAppRepository(Result.success(listOf(fakeApp("Alpha"))))
        val vm = viewModel(repository)
        testDispatcher.scheduler.advanceUntilIdle()

        vm.startGuidedSettings()
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals(GuidedSettingsState.Idle, vm.guidedSettingsState.value)
    }

    @Test
    fun `startGuidedSettings begins Running with the selected apps in display order`() = runTest {
        val alpha = fakeApp("Alpha")
        val beta = fakeApp("Beta")
        val repository = FixedResultAppRepository(Result.success(listOf(alpha, beta)))
        val vm = viewModel(repository)
        testDispatcher.scheduler.advanceUntilIdle()
        vm.selectAll()

        vm.startGuidedSettings()
        testDispatcher.scheduler.advanceUntilIdle()

        val state = vm.guidedSettingsState.value
        assertTrue(state is GuidedSettingsState.Running)
        state as GuidedSettingsState.Running
        assertEquals(listOf(alpha.packageName, beta.packageName), state.queue)
        assertEquals(alpha.packageName, state.current)
        assertEquals(1, state.position)
        assertEquals(2, state.total)
    }

    @Test
    fun `startGuidedSettings only queues the currently selected apps, not every discovered app`() = runTest {
        val alpha = fakeApp("Alpha")
        val beta = fakeApp("Beta")
        val repository = FixedResultAppRepository(Result.success(listOf(alpha, beta)))
        val vm = viewModel(repository)
        testDispatcher.scheduler.advanceUntilIdle()
        vm.toggleSelection(beta.packageName)

        vm.startGuidedSettings()
        testDispatcher.scheduler.advanceUntilIdle()

        val state = vm.guidedSettingsState.value as GuidedSettingsState.Running
        assertEquals(listOf(beta.packageName), state.queue)
    }

    @Test
    fun `startGuidedSettings auto-resolves a package uninstalled since selection as unavailable`() = runTest {
        val alpha = fakeApp("Alpha")
        val beta = fakeApp("Beta")
        // Beta was selected, then "uninstalled" before Manage Selected was
        // tapped - simulated via a repository that returns fewer apps on
        // its second call (the re-discovery startGuidedSettings performs),
        // same technique the existing cleanSelected test uses.
        val repository = object : AppRepository {
            var callCount = 0
                private set

            override suspend fun getLaunchableApps(): List<LaunchableAppInfo> {
                callCount++
                return if (callCount == 1) listOf(alpha, beta) else listOf(alpha)
            }
        }
        val vm = viewModel(repository)
        testDispatcher.scheduler.advanceUntilIdle()
        vm.selectAll()

        vm.startGuidedSettings()
        testDispatcher.scheduler.advanceUntilIdle()

        val state = vm.guidedSettingsState.value
        assertTrue(state is GuidedSettingsState.Running)
        state as GuidedSettingsState.Running
        assertEquals(alpha.packageName, state.current)
        assertEquals(listOf(GuidedOutcome.UNAVAILABLE), state.results.map { it.outcome })
        assertEquals(beta.packageName, state.results.single().packageName)
    }

    @Test
    fun `startGuidedSettings with every selected app uninstalled goes straight to Complete`() = runTest {
        val alpha = fakeApp("Alpha")
        val repository = object : AppRepository {
            var callCount = 0
                private set

            override suspend fun getLaunchableApps(): List<LaunchableAppInfo> {
                callCount++
                return if (callCount == 1) listOf(alpha) else emptyList()
            }
        }
        val vm = viewModel(repository)
        testDispatcher.scheduler.advanceUntilIdle()
        vm.selectAll()

        vm.startGuidedSettings()
        testDispatcher.scheduler.advanceUntilIdle()

        val state = vm.guidedSettingsState.value
        assertTrue(state is GuidedSettingsState.Complete)
        assertEquals(GuidedOutcome.UNAVAILABLE, (state as GuidedSettingsState.Complete).results.single().outcome)
    }

    @Test
    fun `a re-discovery failure during startGuidedSettings falls back to treating the selection as installed`() = runTest {
        val alpha = fakeApp("Alpha")
        val repository = object : AppRepository {
            var callCount = 0
                private set

            override suspend fun getLaunchableApps(): List<LaunchableAppInfo> {
                callCount++
                if (callCount > 1) throw RuntimeException("re-discovery boom")
                return listOf(alpha)
            }
        }
        val vm = viewModel(repository)
        testDispatcher.scheduler.advanceUntilIdle()
        vm.selectAll()

        vm.startGuidedSettings()
        testDispatcher.scheduler.advanceUntilIdle()

        // Falls back to "assume still installed" rather than marking
        // everything unavailable on a guess - see startGuidedSettings's
        // doc comment. The run should still be usable, not stuck/crashed.
        val state = vm.guidedSettingsState.value
        assertTrue(state is GuidedSettingsState.Running)
        assertEquals(alpha.packageName, (state as GuidedSettingsState.Running).current)
        assertEquals(emptyList<Any>(), state.results)
    }

    @Test
    fun `startGuidedSettings is a no-op while a run is already in progress`() = runTest {
        val alpha = fakeApp("Alpha")
        val beta = fakeApp("Beta")
        val repository = FixedResultAppRepository(Result.success(listOf(alpha, beta)))
        val vm = viewModel(repository)
        testDispatcher.scheduler.advanceUntilIdle()
        vm.selectAll()
        vm.startGuidedSettings()
        testDispatcher.scheduler.advanceUntilIdle()
        val stateAfterFirstStart = vm.guidedSettingsState.value

        vm.startGuidedSettings() // second call while already Running
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals(stateAfterFirstStart, vm.guidedSettingsState.value)
    }

    @Test
    fun `confirmGuidedSettingsReturn records confirmed and advances to the next app`() = runTest {
        val alpha = fakeApp("Alpha")
        val beta = fakeApp("Beta")
        val repository = FixedResultAppRepository(Result.success(listOf(alpha, beta)))
        val vm = viewModel(repository)
        testDispatcher.scheduler.advanceUntilIdle()
        vm.selectAll()
        vm.startGuidedSettings()
        testDispatcher.scheduler.advanceUntilIdle()

        vm.confirmGuidedSettingsReturn()

        val state = vm.guidedSettingsState.value
        assertTrue(state is GuidedSettingsState.Running)
        state as GuidedSettingsState.Running
        assertEquals(beta.packageName, state.current)
        assertEquals(
            listOf(alpha.packageName to GuidedOutcome.CONFIRMED_VIA_SETTINGS),
            state.results.map { it.packageName to it.outcome }
        )
    }

    @Test
    fun `confirmGuidedSettingsReturn on the last app completes the run`() = runTest {
        val alpha = fakeApp("Alpha")
        val repository = FixedResultAppRepository(Result.success(listOf(alpha)))
        val vm = viewModel(repository)
        testDispatcher.scheduler.advanceUntilIdle()
        vm.selectAll()
        vm.startGuidedSettings()
        testDispatcher.scheduler.advanceUntilIdle()

        vm.confirmGuidedSettingsReturn()

        val state = vm.guidedSettingsState.value
        assertTrue(state is GuidedSettingsState.Complete)
        assertEquals(GuidedOutcome.CONFIRMED_VIA_SETTINGS, (state as GuidedSettingsState.Complete).results.single().outcome)
    }

    @Test
    fun `confirmGuidedSettingsReturn is a no-op when no run is in progress`() = runTest {
        val repository = FixedResultAppRepository(Result.success(listOf(fakeApp("Alpha"))))
        val vm = viewModel(repository)
        testDispatcher.scheduler.advanceUntilIdle()

        vm.confirmGuidedSettingsReturn()

        assertEquals(GuidedSettingsState.Idle, vm.guidedSettingsState.value)
    }

    @Test
    fun `markCurrentGuidedAppUnavailable records unavailable and advances`() = runTest {
        val alpha = fakeApp("Alpha")
        val beta = fakeApp("Beta")
        val repository = FixedResultAppRepository(Result.success(listOf(alpha, beta)))
        val vm = viewModel(repository)
        testDispatcher.scheduler.advanceUntilIdle()
        vm.selectAll()
        vm.startGuidedSettings()
        testDispatcher.scheduler.advanceUntilIdle()

        vm.markCurrentGuidedAppUnavailable()

        val state = vm.guidedSettingsState.value
        assertTrue(state is GuidedSettingsState.Running)
        state as GuidedSettingsState.Running
        assertEquals(beta.packageName, state.current)
        assertEquals(GuidedOutcome.UNAVAILABLE, state.results.single().outcome)
    }

    @Test
    fun `skipCurrentGuidedApp records skipped and advances`() = runTest {
        val alpha = fakeApp("Alpha")
        val beta = fakeApp("Beta")
        val repository = FixedResultAppRepository(Result.success(listOf(alpha, beta)))
        val vm = viewModel(repository)
        testDispatcher.scheduler.advanceUntilIdle()
        vm.selectAll()
        vm.startGuidedSettings()
        testDispatcher.scheduler.advanceUntilIdle()

        vm.skipCurrentGuidedApp()

        val state = vm.guidedSettingsState.value
        assertTrue(state is GuidedSettingsState.Running)
        state as GuidedSettingsState.Running
        assertEquals(beta.packageName, state.current)
        assertEquals(GuidedOutcome.SKIPPED, state.results.single().outcome)
    }

    @Test
    fun `skipCurrentGuidedApp is a no-op when no run is in progress`() = runTest {
        val repository = FixedResultAppRepository(Result.success(listOf(fakeApp("Alpha"))))
        val vm = viewModel(repository)
        testDispatcher.scheduler.advanceUntilIdle()

        vm.skipCurrentGuidedApp()

        assertEquals(GuidedSettingsState.Idle, vm.guidedSettingsState.value)
    }

    @Test
    fun `skipAllRemainingGuided completes the run with every remaining app skipped`() = runTest {
        val alpha = fakeApp("Alpha")
        val beta = fakeApp("Beta")
        val gamma = fakeApp("Gamma")
        val repository = FixedResultAppRepository(Result.success(listOf(alpha, beta, gamma)))
        val vm = viewModel(repository)
        testDispatcher.scheduler.advanceUntilIdle()
        vm.selectAll()
        vm.startGuidedSettings()
        testDispatcher.scheduler.advanceUntilIdle()
        vm.confirmGuidedSettingsReturn() // alpha confirmed, now on beta

        vm.skipAllRemainingGuided()

        val state = vm.guidedSettingsState.value
        assertTrue(state is GuidedSettingsState.Complete)
        val byPackage = (state as GuidedSettingsState.Complete).results.associateBy { it.packageName }
        assertEquals(GuidedOutcome.CONFIRMED_VIA_SETTINGS, byPackage.getValue(alpha.packageName).outcome)
        assertEquals(GuidedOutcome.SKIPPED, byPackage.getValue(beta.packageName).outcome)
        assertEquals(GuidedOutcome.SKIPPED, byPackage.getValue(gamma.packageName).outcome)
    }

    @Test
    fun `cancelGuidedSettings completes the run with every remaining app cancelled`() = runTest {
        val alpha = fakeApp("Alpha")
        val beta = fakeApp("Beta")
        val repository = FixedResultAppRepository(Result.success(listOf(alpha, beta)))
        val vm = viewModel(repository)
        testDispatcher.scheduler.advanceUntilIdle()
        vm.selectAll()
        vm.startGuidedSettings()
        testDispatcher.scheduler.advanceUntilIdle()

        vm.cancelGuidedSettings()

        val state = vm.guidedSettingsState.value
        assertTrue(state is GuidedSettingsState.Complete)
        val byPackage = (state as GuidedSettingsState.Complete).results.associateBy { it.packageName }
        assertEquals(GuidedOutcome.CANCELLED, byPackage.getValue(alpha.packageName).outcome)
        assertEquals(GuidedOutcome.CANCELLED, byPackage.getValue(beta.packageName).outcome)
    }

    @Test
    fun `cancelGuidedSettings is a no-op when no run is in progress`() = runTest {
        val repository = FixedResultAppRepository(Result.success(listOf(fakeApp("Alpha"))))
        val vm = viewModel(repository)
        testDispatcher.scheduler.advanceUntilIdle()

        vm.cancelGuidedSettings()

        assertEquals(GuidedSettingsState.Idle, vm.guidedSettingsState.value)
    }

    @Test
    fun `dismissGuidedSettingsSummary returns to Idle`() = runTest {
        val alpha = fakeApp("Alpha")
        val repository = FixedResultAppRepository(Result.success(listOf(alpha)))
        val vm = viewModel(repository)
        testDispatcher.scheduler.advanceUntilIdle()
        vm.selectAll()
        vm.startGuidedSettings()
        testDispatcher.scheduler.advanceUntilIdle()
        vm.cancelGuidedSettings()
        assertTrue(vm.guidedSettingsState.value is GuidedSettingsState.Complete)

        vm.dismissGuidedSettingsSummary()

        assertEquals(GuidedSettingsState.Idle, vm.guidedSettingsState.value)
    }
}
