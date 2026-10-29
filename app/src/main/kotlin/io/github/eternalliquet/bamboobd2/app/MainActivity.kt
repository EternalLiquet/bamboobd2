package io.github.eternalliquet.bamboobd2.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.material3.MaterialTheme
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.eternalliquet.bamboobd2.app.ui.BenchScreen
import io.github.eternalliquet.bamboobd2.app.ui.HomeScreen
import io.github.eternalliquet.bamboobd2.app.ui.LiveReadScreen
import io.github.eternalliquet.bamboobd2.app.ui.SavedSessionsScreen
import io.github.eternalliquet.bamboobd2.app.ui.SessionDetailScreen
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
                NavHost(navController = nav, startDestination = "home") {
                    composable("home") {
                        HomeScreen(
                            replays = vm.replays,
                            liveResultPending = live == LiveState.Started,
                            // Navigate only when a session actually started.
                            onStart = { scenario -> if (vm.start(scenario)) nav.navigate("session") },
                            onStartReplay = { id -> if (vm.startReplay(id)) nav.navigate("session") },
                            onOpenPendingLive = { vm.acknowledgeLiveStarted(); nav.navigate("session") },
                            onDiscardPendingLive = vm::discardPendingLive,
                            onOpenSaved = { nav.navigate("saved") },
                            onOpenBench = { nav.navigate("bench") },
                            onOpenLive = { nav.navigate("live") },
                        )
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
                            onBack = { nav.popBackStack() },
                        )
                    }
                    composable("bench") {
                        BenchScreen(vm = vm, onBack = { nav.popBackStack() })
                    }
                    composable("saved") {
                        SavedSessionsScreen(vm = vm, onOpen = { id -> nav.navigate("saved/$id") }, onBack = { nav.popBackStack() })
                    }
                    composable("saved/{id}") { entry ->
                        SessionDetailScreen(vm = vm, id = entry.arguments?.getString("id").orEmpty(), onBack = { nav.popBackStack() })
                    }
                }
            }
        }
    }
}
