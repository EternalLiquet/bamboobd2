package io.github.eternalliquet.bamboobd2.app

import android.os.Bundle
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.material3.MaterialTheme
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.compose.runtime.getValue
import androidx.compose.runtime.DisposableEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.eternalliquet.bamboobd2.app.ui.IdentificationExperimentScreen
import io.github.eternalliquet.bamboobd2.app.ui.BenchScreen
import io.github.eternalliquet.bamboobd2.app.ui.HomeScreen
import io.github.eternalliquet.bamboobd2.app.ui.ScannerSetupScreen
import io.github.eternalliquet.bamboobd2.app.ui.VehicleActions
import io.github.eternalliquet.bamboobd2.core.live.LiveReadState
import io.github.eternalliquet.bamboobd2.core.session.SessionState
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.remember
import io.github.eternalliquet.bamboobd2.app.ui.LiveReadScreen
import io.github.eternalliquet.bamboobd2.app.ui.LiveRecordingScreen
import io.github.eternalliquet.bamboobd2.app.ui.SavedSessionsScreen
import io.github.eternalliquet.bamboobd2.app.ui.SessionDetailScreen
import io.github.eternalliquet.bamboobd2.app.ui.SessionComparisonScreen
import io.github.eternalliquet.bamboobd2.app.ui.SessionScreen

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MaterialTheme {
                val vm: AppViewModel = viewModel()
                val nav = rememberNavController()
                val live by vm.live.collectAsStateWithLifecycle()
                val recordingPending by vm.recordingPending.collectAsStateWithLifecycle()
                val connection by vm.connection.collectAsStateWithLifecycle()
                val session by vm.session.collectAsStateWithLifecycle()
                val recording by vm.recording.collectAsStateWithLifecycle()
                val connectionPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission(), vm::onConnectionPermissionResult)
                DisposableEffect(vm) {
                    val observer = LifecycleEventObserver { _, event ->
                        // ON_START also runs the single startup connection attempt (once per ViewModel).
                        if (event == Lifecycle.Event.ON_START) vm.onAppForegrounded()
                        if (event == Lifecycle.Event.ON_STOP && !isChangingConfigurations) vm.onAppBackgrounded()
                    }
                    lifecycle.addObserver(observer)
                    onDispose { lifecycle.removeObserver(observer) }
                }
                NavHost(navController = nav, startDestination = "home") {
                    composable("home") {
                        // A scan or recording in progress holds the shared connection.
                        val busy = session is SessionState.Running || recordingPending ||
                            (recording !is LiveReadState.Idle && recording !is LiveReadState.Finished)
                        HomeScreen(
                            replays = vm.replays,
                            liveResultPending = live == LiveState.Started || recordingPending,
                            pendingRecording = recordingPending,
                            connection = connection,
                            profiles = remember(connection) { vm.selectableProfiles() },
                            connectionBusy = busy,
                            vehicleActions = VehicleActions(
                                choose = { nav.navigate("setup") },
                                connect = vm::connectVehicle,
                                disconnect = vm::disconnectVehicle,
                                forget = vm::forgetScanner,
                                requestPermission = { vm.bluetoothPermission?.let(connectionPermission::launch) ?: vm.connectVehicle() },
                                identifyAgain = { vm.identifyAgain() },
                                confirmProfile = vm::confirmProfile,
                                openExperiment = { nav.navigate("experiment") },
                            ),
                            // Navigate only when a session actually started.
                            onStart = { scenario -> if (vm.start(scenario)) nav.navigate("session") },
                            onStartReplay = { id -> if (vm.startReplay(id)) nav.navigate("session") },
                            onOpenPendingLive = { if (recordingPending) nav.navigate("recording") else { vm.acknowledgeLiveStarted(); nav.navigate("session") } },
                            onDiscardPendingLive = { if (recordingPending) vm.discardPendingRecording() else vm.discardPendingLive() },
                            onOpenSaved = { nav.navigate("saved") },
                            onOpenBench = { nav.navigate("bench") },
                            onOpenExperiment = { nav.navigate("experiment") },
                            onOpenLive = { nav.navigate("live") },
                        )
                    }
                    composable("setup") {
                        ScannerSetupScreen(vm = vm, onDone = { nav.popBackStack("home", inclusive = false) }, onBack = { nav.popBackStack() })
                    }
                    composable("session") {
                        SessionScreen(
                            vm = vm,
                            onDone = { vm.finishSession(); nav.popBackStack("home", inclusive = false) },
                        )
                    }
                    composable("live") {
                        LiveReadScreen(
                            vm = vm,
                            onStarted = { nav.navigate("session") { popUpTo("home") } },
                            onRecording = { nav.navigate("recording") { popUpTo("home") } },
                            onBack = { nav.popBackStack() },
                        )
                    }
                    composable("recording") {
                        LiveRecordingScreen(vm = vm, onDone = { nav.popBackStack("home", inclusive = false) })
                    }
                    composable("experiment") {
                        IdentificationExperimentScreen(vm = vm, onBack = { nav.popBackStack() })
                    }
                    composable("bench") {
                        BenchScreen(vm = vm, onBack = { nav.popBackStack() })
                    }
                    composable("saved") {
                        SavedSessionsScreen(vm = vm, onOpen = { id -> nav.navigate("saved/$id") },
                            onCompare = { first, second -> nav.navigate("saved/compare/$first/$second") },
                            onBack = { nav.popBackStack() })
                    }
                    composable("saved/compare/{firstId}/{secondId}") { entry ->
                        SessionComparisonScreen(vm = vm, firstId = entry.arguments?.getString("firstId").orEmpty(),
                            secondId = entry.arguments?.getString("secondId").orEmpty(), onBack = { nav.popBackStack() })
                    }
                    composable("saved/{id}") { entry ->
                        SessionDetailScreen(vm = vm, id = entry.arguments?.getString("id").orEmpty(), onBack = { nav.popBackStack() })
                    }
                }
            }
        }
    }
}
