package com.batterymanager.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel
import com.batterymanager.app.data.apps.AppRepository
import com.batterymanager.app.data.apps.DefaultAppRepository
import com.batterymanager.app.data.presets.DataStorePresetStore
import com.batterymanager.app.data.presets.DefaultPresetRepository
import com.batterymanager.app.data.presets.PresetRepository
import com.batterymanager.app.data.presets.presetDataStore
import com.batterymanager.app.data.selection.DataStoreSelectionStore
import com.batterymanager.app.data.selection.selectionDataStore
import com.batterymanager.app.domain.processing.BackgroundCleanupUseCase
import com.batterymanager.app.platform.apps.AndroidPackageManagerWrapper
import com.batterymanager.app.platform.apps.ProtectedPackages
import com.batterymanager.app.platform.process.AndroidActivityManagerWrapper
import com.batterymanager.app.ui.screens.main.MainScreen
import com.batterymanager.app.ui.screens.main.MainViewModel
import com.batterymanager.app.ui.theme.BatteryManagerTheme

/**
 * Single-activity host, per the Architecture doc's "single Activity, Compose
 * Navigation" model (§0/§1). Navigation between screens is not wired up yet -
 * this session only hosts MainScreen directly.
 *
 * There is no `AppContainer`/DI wiring yet (Architecture doc §0 describes one
 * for later, once the graph is bigger). With a single screen, each
 * dependency - the repository, the [DataStoreSelectionStore] backing
 * persisted selection (Session 6), the [DefaultPresetRepository]
 * backing saved presets (Session 7), the [BackgroundCleanupUseCase]
 * backing "Clean Selected" (Session 8), and the protected-package set from
 * [ProtectedPackages] backing the Protected applications task - is
 * constructed directly here and handed to a manual [MainViewModel] factory
 * - introduce `AppContainer` when a second screen needs to share them.
 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val appRepository: AppRepository =
            DefaultAppRepository(AndroidPackageManagerWrapper(applicationContext))
        val selectionStore = DataStoreSelectionStore(applicationContext.selectionDataStore)
        val presetRepository: PresetRepository =
            DefaultPresetRepository(DataStorePresetStore(applicationContext.presetDataStore))
        val backgroundCleanupUseCase =
            BackgroundCleanupUseCase(AndroidActivityManagerWrapper(applicationContext))
        val protectedPackageNames =
            ProtectedPackages.resolveProtectedPackageNames(applicationContext)
        val mainViewModelFactory = MainViewModel.factory(
            appRepository = appRepository,
            ownPackageName = applicationContext.packageName,
            selectionStore = selectionStore,
            presetRepository = presetRepository,
            backgroundCleanupUseCase = backgroundCleanupUseCase,
            protectedPackageNames = protectedPackageNames
        )

        setContent {
            BatteryManagerTheme {
                val mainViewModel: MainViewModel = viewModel(factory = mainViewModelFactory)
                MainScreen(
                    viewModel = mainViewModel,
                    modifier = Modifier.fillMaxSize()
                )
            }
        }
    }
}
