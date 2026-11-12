package com.variableduck.build.habittracker.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.variableduck.build.habittracker.ui.common.BarChartIcon
import com.variableduck.build.habittracker.ui.common.rememberFormatter
import com.variableduck.build.habittracker.ui.habits.HabitsScreen
import com.variableduck.build.habittracker.ui.history.HistoryScreen
import com.variableduck.build.habittracker.ui.importmode.HistoryImportScreen
import com.variableduck.build.habittracker.ui.onboarding.OnboardingScreen
import com.variableduck.build.habittracker.ui.review.CycleReviewScreen
import com.variableduck.build.habittracker.ui.settings.SettingsScreen
import com.variableduck.build.habittracker.ui.stats.StatsScreen
import com.variableduck.build.habittracker.ui.today.TodayScreen
import kotlinx.serialization.Serializable

@Serializable data object TodayRoute

@Serializable data object HistoryRoute

@Serializable data object StatsRoute

@Serializable data object HabitsRoute

@Serializable data object SettingsRoute

@Serializable data object ReviewRoute

@Serializable data object ImportRoute

private data class Tab(val route: Any, val label: String, val icon: ImageVector)

private val tabs = listOf(
    Tab(TodayRoute, "Today", Icons.Filled.CheckCircle),
    Tab(HistoryRoute, "History", Icons.Filled.DateRange),
    Tab(StatsRoute, "Stats", BarChartIcon),
    Tab(HabitsRoute, "Habits", Icons.AutoMirrored.Filled.List),
    Tab(SettingsRoute, "Settings", Icons.Filled.Settings),
)

@Composable
fun AppRoot(viewModel: AppViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    LifecycleResumeEffect(Unit) {
        viewModel.onResume()
        onPauseOrDispose { }
    }
    when {
        state.loading -> Box(
            Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background),
        ) { CircularProgressIndicator(Modifier.padding(48.dp)) }
        !state.onboardingComplete -> OnboardingScreen(onFinished = viewModel::onOnboardingFinished)
        else -> MainScaffold(state, viewModel)
    }
}

@Composable
private fun MainScaffold(state: AppUiState, viewModel: AppViewModel) {
    val navController = rememberNavController()
    val snackbar = remember { SnackbarHostState() }
    val backStack by navController.currentBackStackEntryAsState()
    val destination = backStack?.destination
    var reviewOpened by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(state.pendingReviewCount) {
        if (state.pendingReviewCount > 0 && !reviewOpened) {
            reviewOpened = true
            navController.navigate(ReviewRoute) { launchSingleTop = true }
        }
    }
    LaunchedEffect(state.message) {
        state.message?.let {
            snackbar.showSnackbar(it)
            viewModel.messageShown()
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        bottomBar = {
            NavigationBar {
                tabs.forEach { tab ->
                    val selected = destination?.hierarchy?.any { it.hasRoute(tab.route::class) } == true
                    NavigationBarItem(
                        selected = selected,
                        onClick = {
                            navController.navigate(tab.route) {
                                popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                                launchSingleTop = true
                                restoreState = true
                            }
                        },
                        icon = { Icon(tab.icon, contentDescription = null) },
                        label = { Text(tab.label) },
                    )
                }
            }
        },
    ) { padding ->
        NavHost(
            navController = navController,
            startDestination = TodayRoute,
            modifier = Modifier
                .padding(padding)
                .consumeWindowInsets(padding)
                .imePadding(),
        ) {
            composable<TodayRoute> { TodayScreen(onOpenReview = { navController.navigate(ReviewRoute) }) }
            composable<HistoryRoute> { HistoryScreen() }
            composable<StatsRoute> { StatsScreen() }
            composable<HabitsRoute> { HabitsScreen() }
            composable<SettingsRoute> {
                SettingsScreen(
                    onOpenImport = { navController.navigate(ImportRoute) },
                    onCheckRetention = viewModel::checkRetentionNow,
                    onMessage = viewModel::showMessage,
                )
            }
            composable<ReviewRoute> { CycleReviewScreen(onClose = { navController.popBackStack() }) }
            composable<ImportRoute> { HistoryImportScreen(onClose = { navController.popBackStack() }) }
        }
    }

    state.goalPopup?.let { goal ->
        AlertDialog(
            onDismissRequest = viewModel::dismissGoal,
            title = { Text("Your overall goal") },
            text = { Text(goal, style = MaterialTheme.typography.titleMedium) },
            confirmButton = { TextButton(onClick = viewModel::dismissGoal) { Text("Got it") } },
        )
    }

    state.retentionPrompt?.let { prompt ->
        val formatter = rememberFormatter()
        AlertDialog(
            onDismissRequest = { if (!state.retentionBusy) viewModel.retentionNotNow() },
            title = { Text("Delete old entries?") },
            text = {
                Text(
                    "${prompt.dayCount} days older than ${formatter.date(prompt.cutoff)} can be deleted. Delete?\n\n" +
                        "They will first be saved to an archive_….xlsx file in your backup folder. " +
                        "If that fails, nothing is deleted.",
                )
            },
            confirmButton = {
                TextButton(onClick = viewModel::retentionDelete, enabled = !state.retentionBusy) { Text("Delete") }
            },
            dismissButton = {
                TextButton(onClick = viewModel::retentionNotNow, enabled = !state.retentionBusy) { Text("Not now") }
            },
        )
    }
}
