package com.batterymanager.app.ui.screens.main

import android.content.ActivityNotFoundException
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.batterymanager.app.R
import com.batterymanager.app.data.presets.Preset
import com.batterymanager.app.domain.guided.GuidedOutcome
import com.batterymanager.app.domain.guided.GuidedResult
import com.batterymanager.app.domain.guided.GuidedSettingsState
import com.batterymanager.app.domain.processing.CleanupOutcome
import com.batterymanager.app.domain.processing.CleanupResult
import com.batterymanager.app.platform.apps.LaunchableAppInfo
import com.batterymanager.app.platform.intents.AndroidAppSettingsIntentFactory
import com.batterymanager.app.platform.intents.AppSettingsIntentFactory
import com.batterymanager.app.ui.components.AppRow
import com.batterymanager.app.ui.theme.BatteryManagerTheme

/**
 * Reads discovered apps, selection state, saved presets, and
 * background-cleanup state from [MainViewModel] and renders a list with a
 * checkbox per eligible app, a selected count, Select All / Deselect All
 * controls, a presets row (save the current selection as a named preset;
 * load or delete a saved one), a "Clean Selected" action that attempts
 * Phase A background-process cleanup for the current selection, and an
 * "Open Selected" action ([openSelected]) that (re-)launches each
 * currently selected app via its existing [LaunchableAppInfo.launchIntent].
 *
 * "Clean Selected" is a request/attempt, never a guaranteed or permanent
 * stop - see [CleanupOutcome]'s doc comments and the summary dialog below
 * for how that's kept honest in the UI copy. No Force Stop, no guided
 * Settings automation, no Usage Access - those are explicitly out of
 * scope for this session (see PROJECT_STATE.md).
 */
@Composable
fun MainScreen(
    viewModel: MainViewModel,
    modifier: Modifier = Modifier
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val presets by viewModel.presets.collectAsStateWithLifecycle()
    val cleanupState by viewModel.cleanupState.collectAsStateWithLifecycle()
    val guidedSettingsState by viewModel.guidedSettingsState.collectAsStateWithLifecycle()
    MainScreenContent(
        uiState = uiState,
        presets = presets,
        cleanupState = cleanupState,
        guidedSettingsState = guidedSettingsState,
        onRetry = viewModel::retry,
        onToggleSelection = viewModel::toggleSelection,
        onSelectAll = viewModel::selectAll,
        onDeselectAll = viewModel::deselectAll,
        onCreatePreset = viewModel::createPreset,
        onLoadPreset = viewModel::loadPreset,
        onDeletePreset = viewModel::deletePreset,
        onCleanSelected = viewModel::cleanSelected,
        onDismissCleanupSummary = viewModel::dismissCleanupSummary,
        onManageSelected = viewModel::startGuidedSettings,
        onGuidedSettingsReturned = viewModel::confirmGuidedSettingsReturn,
        onGuidedSettingsLaunchFailed = viewModel::markCurrentGuidedAppUnavailable,
        onSkipGuidedApp = viewModel::skipCurrentGuidedApp,
        onSkipAllRemainingGuided = viewModel::skipAllRemainingGuided,
        onCancelGuidedSettings = viewModel::cancelGuidedSettings,
        onDismissGuidedSettingsSummary = viewModel::dismissGuidedSettingsSummary,
        modifier = modifier
    )
}

/**
 * Stateless content, separated from [MainScreen] so it can be exercised in
 * `@Preview`s without needing a real [MainViewModel]/`Context`.
 */
@Composable
private fun MainScreenContent(
    uiState: MainUiState,
    presets: List<Preset>,
    cleanupState: CleanupUiState,
    guidedSettingsState: GuidedSettingsState,
    onRetry: () -> Unit,
    onToggleSelection: (String) -> Unit,
    onSelectAll: () -> Unit,
    onDeselectAll: () -> Unit,
    onCreatePreset: (String) -> Unit,
    onLoadPreset: (String) -> Unit,
    onDeletePreset: (String) -> Unit,
    onCleanSelected: () -> Unit,
    onDismissCleanupSummary: () -> Unit,
    onManageSelected: () -> Unit,
    onGuidedSettingsReturned: () -> Unit,
    onGuidedSettingsLaunchFailed: () -> Unit,
    onSkipGuidedApp: () -> Unit,
    onSkipAllRemainingGuided: () -> Unit,
    onCancelGuidedSettings: () -> Unit,
    onDismissGuidedSettingsSummary: () -> Unit,
    modifier: Modifier = Modifier
) {
    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TopAppBar(title = { Text(stringResource(id = R.string.app_name)) })
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            Text(
                text = stringResource(id = R.string.applications_title),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(16.dp)
            )
            when (uiState) {
                MainUiState.Loading ->
                    CenteredMessage(stringResource(id = R.string.loading_applications))

                is MainUiState.Success ->
                    AppListContent(
                        apps = uiState.apps,
                        selectableApps = uiState.selectableApps,
                        selectedPackageNames = uiState.selectedPackageNames,
                        selectedCount = uiState.selectedCount,
                        presets = presets,
                        cleanupInProgress = cleanupState is CleanupUiState.InProgress,
                        guidedSettingsInProgress = guidedSettingsState is GuidedSettingsState.Running,
                        onToggleSelection = onToggleSelection,
                        onSelectAll = onSelectAll,
                        onDeselectAll = onDeselectAll,
                        onCreatePreset = onCreatePreset,
                        onLoadPreset = onLoadPreset,
                        onDeletePreset = onDeletePreset,
                        onCleanSelected = onCleanSelected,
                        onManageSelected = onManageSelected
                    )

                MainUiState.Empty ->
                    CenteredMessage(stringResource(id = R.string.no_eligible_applications))

                is MainUiState.Error ->
                    ErrorContent(onRetry = onRetry)
            }
        }
    }

    when (cleanupState) {
        is CleanupUiState.InProgress ->
            CleanupProgressDialog(total = cleanupState.total)

        is CleanupUiState.Complete ->
            CleanupSummaryDialog(results = cleanupState.results, onDismiss = onDismissCleanupSummary)

        CleanupUiState.Idle -> Unit
    }

    when (guidedSettingsState) {
        is GuidedSettingsState.Running -> {
            val apps = (uiState as? MainUiState.Success)?.apps.orEmpty()
            GuidedSettingsOverlay(
                state = guidedSettingsState,
                apps = apps,
                onSettingsLaunchFailed = onGuidedSettingsLaunchFailed,
                onReturnedFromSettings = onGuidedSettingsReturned,
                onSkip = onSkipGuidedApp,
                onSkipAllRemaining = onSkipAllRemainingGuided,
                onCancel = onCancelGuidedSettings
            )
        }

        is GuidedSettingsState.Complete ->
            GuidedSettingsSummaryDialog(
                results = guidedSettingsState.results,
                onDismiss = onDismissGuidedSettingsSummary
            )

        GuidedSettingsState.Idle -> Unit
    }
}

@Composable
private fun AppListContent(
    apps: List<LaunchableAppInfo>,
    selectableApps: Set<String>,
    selectedPackageNames: Set<String>,
    selectedCount: Int,
    presets: List<Preset>,
    cleanupInProgress: Boolean,
    guidedSettingsInProgress: Boolean,
    onToggleSelection: (String) -> Unit,
    onSelectAll: () -> Unit,
    onDeselectAll: () -> Unit,
    onCreatePreset: (String) -> Unit,
    onLoadPreset: (String) -> Unit,
    onDeletePreset: (String) -> Unit,
    onCleanSelected: () -> Unit,
    onManageSelected: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current

    Column(modifier = modifier.fillMaxSize()) {
        Text(
            text = stringResource(id = R.string.selected_count, selectedCount),
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
        )
        Row(
            modifier = Modifier.padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Button(onClick = onSelectAll) {
                Text(text = stringResource(id = R.string.select_all))
            }
            OutlinedButton(onClick = onDeselectAll) {
                Text(text = stringResource(id = R.string.deselect_all))
            }
        }
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Button(
                onClick = onCleanSelected,
                enabled = selectedCount > 0 && !cleanupInProgress && !guidedSettingsInProgress
            ) {
                Text(text = stringResource(id = R.string.clean_selected))
            }
            OutlinedButton(
                onClick = onManageSelected,
                enabled = selectedCount > 0 && !cleanupInProgress && !guidedSettingsInProgress
            ) {
                Text(text = stringResource(id = R.string.manage_selected))
            }
        }
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            OutlinedButton(
                onClick = {
                    openSelected(
                        context = context,
                        apps = apps,
                        selectedPackageNames = selectedPackageNames
                    )
                },
                enabled = selectedCount > 0 && !cleanupInProgress && !guidedSettingsInProgress
            ) {
                Text(text = stringResource(id = R.string.open_selected))
            }
        }
        PresetsSection(
            presets = presets,
            onLoadPreset = onLoadPreset,
            onDeletePreset = onDeletePreset,
            onCreatePreset = onCreatePreset
        )
        LazyColumn(modifier = Modifier.weight(1f)) {
            items(items = apps, key = { it.packageName }) { app ->
                AppRow(
                    app = app,
                    selectable = app.packageName in selectableApps,
                    selected = app.packageName in selectedPackageNames,
                    onToggleSelected = onToggleSelection
                )
            }
        }
    }
}

/**
 * "Open Selected" (session task): attempts to (re-)launch every currently
 * selected app, using the existing [LaunchableAppInfo.launchIntent] each
 * app was already discovered with - no new launcher architecture, no new
 * permissions, no background service.
 *
 * This does not reverse Force Stop or undo background cleanup; it only
 * asks Android to bring each selected app's own launcher activity to the
 * foreground, the same as tapping its icon would.
 *
 * Per app: if [LaunchableAppInfo.launchIntent] is null, that app is
 * skipped (`continue`) without attempting to launch it. If starting the
 * intent throws for any reason - [ActivityNotFoundException] (e.g. the
 * app was uninstalled since discovery), [SecurityException] (a
 * restricted/disabled activity the platform declines to start), or any
 * other unexpected [RuntimeException] the platform could raise for a
 * single package - that app is skipped and the rest of the selection is
 * still attempted. One bad app never stops the others and never crashes
 * the screen; nothing here is allowed to propagate out of this loop.
 */
private fun openSelected(
    context: android.content.Context,
    apps: List<LaunchableAppInfo>,
    selectedPackageNames: Set<String>
) {
    val targets = apps.filter { it.packageName in selectedPackageNames }
    for (app in targets) {
        val intent = app.launchIntent ?: continue
        try {
            context.startActivity(intent)
        } catch (e: RuntimeException) {
            // Covers ActivityNotFoundException (uninstalled/unresolvable
            // since discovery), SecurityException (a restricted/disabled
            // activity), and any other RuntimeException startActivity
            // could raise for one package - skip safely rather than let
            // one bad app crash the screen or abort the rest of the
            // selection.
        }
    }
}

/**
 * The presets row: a "Save as Preset" button (opens [CreatePresetDialog]),
 * followed by a chip per saved preset. Tapping a chip loads it
 * ([onLoadPreset]); tapping its "Delete" label deletes it ([onDeletePreset]) -
 * see [PresetChip].
 */
@Composable
private fun PresetsSection(
    presets: List<Preset>,
    onLoadPreset: (String) -> Unit,
    onDeletePreset: (String) -> Unit,
    onCreatePreset: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    var showCreateDialog by remember { mutableStateOf(false) }

    Column(modifier = modifier.fillMaxWidth()) {
        Text(
            text = stringResource(id = R.string.presets_title),
            style = MaterialTheme.typography.titleSmall,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
        )
        LazyRow(
            contentPadding = PaddingValues(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            item {
                OutlinedButton(onClick = { showCreateDialog = true }) {
                    Text(text = stringResource(id = R.string.save_as_preset))
                }
            }
            if (presets.isEmpty()) {
                item {
                    Text(
                        text = stringResource(id = R.string.no_saved_presets),
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(start = 8.dp)
                    )
                }
            }
            items(items = presets, key = { it.id }) { preset ->
                PresetChip(
                    preset = preset,
                    onLoad = { onLoadPreset(preset.id) },
                    onDelete = { onDeletePreset(preset.id) }
                )
            }
        }
    }

    if (showCreateDialog) {
        CreatePresetDialog(
            onDismiss = { showCreateDialog = false },
            onConfirm = { name ->
                onCreatePreset(name)
                showCreateDialog = false
            }
        )
    }
}

/**
 * One saved preset: tapping the name loads it, tapping "Delete" removes
 * it. A simple text label rather than an icon button - this project has
 * no icon-set dependency yet, so a text affordance avoids adding one for
 * a single button.
 */
@Composable
private fun PresetChip(
    preset: Preset,
    onLoad: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        shape = MaterialTheme.shapes.small,
        tonalElevation = 1.dp,
        modifier = modifier
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .clickable(onClick = onLoad)
                .padding(horizontal = 12.dp, vertical = 6.dp)
        ) {
            Text(text = preset.name, style = MaterialTheme.typography.bodyMedium)
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = stringResource(id = R.string.delete_preset),
                style = MaterialTheme.typography.labelSmall,
                modifier = Modifier.clickable(onClick = onDelete)
            )
        }
    }
}

/**
 * Name-entry dialog for [PresetsSection]'s "Save as Preset" action. The
 * suggestion buttons (Battery Saver / Sleep / Work / Custom, per the
 * task's suggested default preset names) only pre-fill the text field -
 * nothing is saved, and no application is added, until the user taps
 * Save. This is a deliberate choice: default presets are never
 * auto-created or auto-populated with apps: the user always controls both
 * the name and the contents.
 */
@Composable
private fun CreatePresetDialog(
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit
) {
    var name by remember { mutableStateOf("") }
    val suggestions = listOf(
        stringResource(id = R.string.preset_suggestion_battery_saver),
        stringResource(id = R.string.preset_suggestion_sleep),
        stringResource(id = R.string.preset_suggestion_work),
        stringResource(id = R.string.preset_suggestion_custom)
    )

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(text = stringResource(id = R.string.save_as_preset)) },
        text = {
            Column {
                Text(
                    text = stringResource(id = R.string.preset_dialog_description),
                    style = MaterialTheme.typography.bodySmall
                )
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(text = stringResource(id = R.string.preset_name_label)) },
                    singleLine = true
                )
                Spacer(modifier = Modifier.height(8.dp))
                Row {
                    suggestions.forEach { suggestion ->
                        TextButton(onClick = { name = suggestion }) {
                            Text(text = suggestion, style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { onConfirm(name) },
                enabled = name.isNotBlank()
            ) {
                Text(text = stringResource(id = R.string.save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(text = stringResource(id = R.string.cancel))
            }
        }
    )
}

/**
 * Shown while [MainViewModel.cleanSelected] is running. There is no
 * documented, reliable per-package progress callback for
 * `killBackgroundProcesses`, so this is a single indeterminate spinner for
 * the whole batch (see [CleanupUiState.InProgress]'s doc comment) - not a
 * per-app step counter. Not dismissible - the run completes into
 * [CleanupUiState.Complete] on its own; there is nothing meaningful to
 * cancel mid-batch since each per-package call is effectively instant.
 */
@Composable
private fun CleanupProgressDialog(total: Int, modifier: Modifier = Modifier) {
    Dialog(onDismissRequest = {}) {
        Surface(shape = MaterialTheme.shapes.medium, modifier = modifier) {
            Column(
                modifier = Modifier.padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                CircularProgressIndicator()
                Text(
                    text = stringResource(id = R.string.cleanup_in_progress, total),
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        }
    }
}

/**
 * Shown once a cleanup run finishes ([CleanupUiState.Complete]). Groups
 * [results] by [CleanupOutcome] with per-package detail, plus a fixed
 * disclaimer that this was a request/attempt, never a guaranteed or
 * permanent stop - see [CleanupOutcome]'s doc comments for why that
 * language matters. The whole dialog is scrollable-by-list (a
 * [LazyColumn]) since the number of results equals the number of apps
 * selected, which is not bounded.
 */
@Composable
private fun CleanupSummaryDialog(
    results: List<CleanupResult>,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(text = stringResource(id = R.string.cleanup_summary_title)) },
        text = {
            Column {
                Text(
                    text = stringResource(id = R.string.cleanup_summary_disclaimer),
                    style = MaterialTheme.typography.bodySmall
                )
                Spacer(modifier = Modifier.height(8.dp))
                LazyColumn(modifier = Modifier.height(240.dp)) {
                    items(items = results, key = { it.packageName }) { result ->
                        Row(modifier = Modifier.padding(vertical = 4.dp)) {
                            Text(
                                text = result.packageName,
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.weight(1f)
                            )
                            Text(
                                text = stringResource(id = result.outcome.labelRes()),
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(onClick = onDismiss) {
                Text(text = stringResource(id = R.string.done))
            }
        }
    )
}

/** The fixed, honest label shown for each [CleanupOutcome] - see its own doc comments. */
private fun CleanupOutcome.labelRes(): Int = when (this) {
    CleanupOutcome.PROCESSED -> R.string.cleanup_outcome_processed
    CleanupOutcome.SKIPPED -> R.string.cleanup_outcome_skipped
    CleanupOutcome.PROTECTED -> R.string.cleanup_outcome_protected
    CleanupOutcome.UNAVAILABLE -> R.string.cleanup_outcome_unavailable
    CleanupOutcome.ERROR -> R.string.cleanup_outcome_error
}

/**
 * The full-screen overlay shown while a guided Settings run
 * ([GuidedSettingsState.Running]) is in progress.
 *
 * On entering a new [GuidedSettingsState.Running.index], this launches
 * [state.current]'s `ACTION_APPLICATION_DETAILS_SETTINGS` intent via
 * [AppSettingsIntentFactory] and `context.startActivity` - the only
 * Android-framework interaction in this composable beyond ordinary
 * lifecycle observation. It then watches this screen's own
 * `Activity.onResume` (via [LocalLifecycleOwner]) to notice the user came
 * back, and calls [onReturnedFromSettings]. Nothing here reads the
 * Settings screen's contents, taps anything on it, or waits for a
 * particular result from it - see [AppSettingsIntentFactory]'s doc
 * comment.
 *
 * Known limitation, inherent to `onResume`-based detection (not fixable
 * without exactly the automation this app is required not to use): if the
 * user leaves Battery Manager some other way after the intent launches
 * (e.g. presses Home, then reopens Battery Manager themselves rather than
 * returning from the Settings screen), that also looks like "returned
 * from Settings" and advances the queue. This is the same detection
 * Architecture doc Component 9 itself specifies (`Activity.onResume`) and
 * is a known, documented trade-off, not an oversight.
 *
 * Handles the user pressing Back (treated the same as Cancel - see
 * [onCancel]) and an app having been uninstalled - if the intent doesn't
 * resolve or launching it throws [ActivityNotFoundException], this calls
 * [onSettingsLaunchFailed] instead of crashing or getting stuck.
 */
@Composable
private fun GuidedSettingsOverlay(
    state: GuidedSettingsState.Running,
    apps: List<LaunchableAppInfo>,
    onSettingsLaunchFailed: () -> Unit,
    onReturnedFromSettings: () -> Unit,
    onSkip: () -> Unit,
    onSkipAllRemaining: () -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
    intentFactory: AppSettingsIntentFactory = remember { AndroidAppSettingsIntentFactory() }
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var awaitingReturn by remember(state.index) { mutableStateOf(false) }
    val currentOnSettingsLaunchFailed by rememberUpdatedState(onSettingsLaunchFailed)
    val currentOnReturnedFromSettings by rememberUpdatedState(onReturnedFromSettings)

    LaunchedEffect(state.index) {
        awaitingReturn = false
        val intent = intentFactory.buildAppDetailsIntent(state.current)
        val resolves = intent.resolveActivity(context.packageManager) != null
        if (!resolves) {
            currentOnSettingsLaunchFailed()
        } else {
            try {
                awaitingReturn = true
                context.startActivity(intent)
            } catch (e: ActivityNotFoundException) {
                awaitingReturn = false
                currentOnSettingsLaunchFailed()
            }
        }
    }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME && awaitingReturn) {
                awaitingReturn = false
                currentOnReturnedFromSettings()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // Back is treated the same as Cancel - there is no meaningful "back to
    // the previous app" within a guided run.
    BackHandler(onBack = onCancel)

    Dialog(
        onDismissRequest = onCancel,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(modifier = modifier.fillMaxSize()) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(24.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = stringResource(id = R.string.guided_settings_progress, state.position, state.total),
                    style = MaterialTheme.typography.titleMedium
                )
                Spacer(modifier = Modifier.height(16.dp))
                val appName = apps.find { it.packageName == state.current }?.appName ?: state.current
                Text(text = appName, style = MaterialTheme.typography.headlineSmall)
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = stringResource(id = R.string.guided_settings_instruction),
                    style = MaterialTheme.typography.bodyMedium
                )
                Spacer(modifier = Modifier.height(32.dp))
                CircularProgressIndicator()
                Spacer(modifier = Modifier.height(32.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = onSkip) {
                        Text(text = stringResource(id = R.string.skip))
                    }
                    OutlinedButton(onClick = onSkipAllRemaining) {
                        Text(text = stringResource(id = R.string.skip_all_remaining))
                    }
                }
                Spacer(modifier = Modifier.height(8.dp))
                TextButton(onClick = onCancel) {
                    Text(text = stringResource(id = R.string.cancel))
                }
            }
        }
    }
}

/**
 * Shown once a guided Settings run finishes ([GuidedSettingsState.Complete]).
 * Lists every [results] entry with its honest, hedged outcome label - see
 * [GuidedOutcome]'s doc comments and PRD §11.1 for exactly why "You
 * confirmed via Settings" (not "App was force-stopped") is the strongest
 * claim made here.
 */
@Composable
private fun GuidedSettingsSummaryDialog(
    results: List<GuidedResult>,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(text = stringResource(id = R.string.guided_settings_summary_title)) },
        text = {
            Column {
                Text(
                    text = stringResource(id = R.string.guided_settings_summary_disclaimer),
                    style = MaterialTheme.typography.bodySmall
                )
                Spacer(modifier = Modifier.height(8.dp))
                LazyColumn(modifier = Modifier.height(240.dp)) {
                    items(items = results, key = { it.packageName }) { result ->
                        Row(modifier = Modifier.padding(vertical = 4.dp)) {
                            Text(
                                text = result.packageName,
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.weight(1f)
                            )
                            Text(
                                text = stringResource(id = result.outcome.labelRes()),
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(onClick = onDismiss) {
                Text(text = stringResource(id = R.string.done))
            }
        }
    )
}

/** The fixed, honest label shown for each [GuidedOutcome] - see its own doc comments. */
private fun GuidedOutcome.labelRes(): Int = when (this) {
    GuidedOutcome.CONFIRMED_VIA_SETTINGS -> R.string.guided_outcome_confirmed
    GuidedOutcome.SKIPPED -> R.string.guided_outcome_skipped
    GuidedOutcome.UNAVAILABLE -> R.string.guided_outcome_unavailable
    GuidedOutcome.CANCELLED -> R.string.guided_outcome_cancelled
}

@Composable
private fun CenteredMessage(message: String, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Text(text = message, style = MaterialTheme.typography.bodyLarge)
    }
}

@Composable
private fun ErrorContent(onRetry: () -> Unit, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = stringResource(id = R.string.unable_to_load_applications),
            style = MaterialTheme.typography.bodyLarge
        )
        Spacer(modifier = Modifier.height(16.dp))
        Button(onClick = onRetry) {
            Text(text = stringResource(id = R.string.retry))
        }
    }
}

@Preview(showBackground = true, name = "Loading")
@Composable
private fun MainScreenLoadingPreview() {
    BatteryManagerTheme {
        MainScreenContent(
            uiState = MainUiState.Loading,
            presets = emptyList(),
            cleanupState = CleanupUiState.Idle,
            guidedSettingsState = GuidedSettingsState.Idle,
            onRetry = {},
            onToggleSelection = {},
            onSelectAll = {},
            onDeselectAll = {},
            onCreatePreset = {},
            onLoadPreset = {},
            onDeletePreset = {},
            onCleanSelected = {},
            onDismissCleanupSummary = {},
            onManageSelected = {},
            onGuidedSettingsReturned = {},
            onGuidedSettingsLaunchFailed = {},
            onSkipGuidedApp = {},
            onSkipAllRemainingGuided = {},
            onCancelGuidedSettings = {},
            onDismissGuidedSettingsSummary = {}
        )
    }
}

@Preview(showBackground = true, name = "Empty")
@Composable
private fun MainScreenEmptyPreview() {
    BatteryManagerTheme {
        MainScreenContent(
            uiState = MainUiState.Empty,
            presets = emptyList(),
            cleanupState = CleanupUiState.Idle,
            guidedSettingsState = GuidedSettingsState.Idle,
            onRetry = {},
            onToggleSelection = {},
            onSelectAll = {},
            onDeselectAll = {},
            onCreatePreset = {},
            onLoadPreset = {},
            onDeletePreset = {},
            onCleanSelected = {},
            onDismissCleanupSummary = {},
            onManageSelected = {},
            onGuidedSettingsReturned = {},
            onGuidedSettingsLaunchFailed = {},
            onSkipGuidedApp = {},
            onSkipAllRemainingGuided = {},
            onCancelGuidedSettings = {},
            onDismissGuidedSettingsSummary = {}
        )
    }
}

@Preview(showBackground = true, name = "Error")
@Composable
private fun MainScreenErrorPreview() {
    BatteryManagerTheme {
        MainScreenContent(
            uiState = MainUiState.Error(),
            presets = emptyList(),
            cleanupState = CleanupUiState.Idle,
            guidedSettingsState = GuidedSettingsState.Idle,
            onRetry = {},
            onToggleSelection = {},
            onSelectAll = {},
            onDeselectAll = {},
            onCreatePreset = {},
            onLoadPreset = {},
            onDeletePreset = {},
            onCleanSelected = {},
            onDismissCleanupSummary = {},
            onManageSelected = {},
            onGuidedSettingsReturned = {},
            onGuidedSettingsLaunchFailed = {},
            onSkipGuidedApp = {},
            onSkipAllRemainingGuided = {},
            onCancelGuidedSettings = {},
            onDismissGuidedSettingsSummary = {}
        )
    }
}
