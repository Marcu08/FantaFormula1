package com.fantaformula1.app.ui.navigation

import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.fantaformula1.app.core.AppContainer
import com.fantaformula1.app.data.repository.AuthState
import com.fantaformula1.app.data.sync.F1SyncScheduler
import com.fantaformula1.app.data.sync.SyncResult
import com.fantaformula1.app.ui.auth.AuthScreen
import com.fantaformula1.app.ui.calendar.CalendarScreen
import com.fantaformula1.app.ui.config.ConfigScreen
import com.fantaformula1.app.ui.leagues.LeagueMembersScreen
import com.fantaformula1.app.ui.leagues.LeagueScreen
import com.fantaformula1.app.ui.leagues.LeaguesScreen
import com.fantaformula1.app.ui.predictions.PredictionScreen
import com.fantaformula1.app.ui.results.RaceResultScreen
import com.fantaformula1.app.ui.rules.RulesScreen
import com.fantaformula1.app.ui.standings.StandingsScreen
import com.fantaformula1.app.ui.components.LoadingState

/**
 * Grafo di navigazione dell'intera app.
 *
 * La schermata di partenza viene decisa dopo il caricamento della configurazione:
 * senza Supabase si va su Configurazione, senza sessione su Accesso, altrimenti
 * direttamente sulle leghe.
 */
@Composable
fun AppNavHost(
    container: AppContainer,
    modifier: Modifier = Modifier,
    navController: NavHostController = rememberNavController(),
) {
    var startDestination by remember { mutableStateOf<String?>(null) }
    val authState by container.authRepository.state.collectAsStateWithLifecycle(
        initialValue = AuthState.Checking,
    )
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    // U6: i messaggi di sync usano lo Snackbar del grafo, non i Toast.
    val snackbarState = remember { SnackbarHostState() }

    LaunchedEffect(Unit) {
        runCatching { container.initialize() }
        val configured = container.configStore.current().isConfigured
        val signedIn = container.authRepository.currentUserId() != null
        startDestination = when {
            !configured -> Routes.CONFIG
            signedIn -> Routes.HOME
            else -> Routes.AUTH
        }
    }

    // Uscita dall'account: torna alla schermata di accesso e ferma ogni
    // sincronizzazione F1 avviata da questo dispositivo (N5).
    LaunchedEffect(authState) {
        if (authState is AuthState.SignedOut && startDestination != null) {
            F1SyncScheduler.cancelAll(context)
            val onAuth = navController.currentDestination?.route == Routes.AUTH
            if (!onAuth) {
                navController.navigate(Routes.AUTH) {
                    popUpTo(0) { inclusive = true }
                }
            }
        }
    }

    val destination = startDestination
    if (destination == null) {
        LoadingState(modifier)
        return
    }

    Box(modifier = modifier.fillMaxSize()) {
        NavHost(
            navController = navController,
            startDestination = destination,
            enterTransition = { slideInHorizontally(tween(240)) { it / 4 } + fadeIn(tween(240)) },
            exitTransition = { fadeOut(tween(180)) },
            popEnterTransition = { fadeIn(tween(180)) },
            popExitTransition = { slideOutHorizontally(tween(240)) { it / 4 } + fadeOut(tween(240)) },
        ) {
        composable(Routes.CONFIG) {
            ConfigScreen(
                container = container,
                onBack = {
                    if (navController.previousBackStackEntry != null) {
                        navController.popBackStack()
                    }
                },
                onConfigured = {
                    navController.navigate(Routes.AUTH) {
                        popUpTo(0) { inclusive = true }
                    }
                },
            )
        }

        composable(Routes.AUTH) {
            AuthScreen(
                container = container,
                onBack = {
                    if (navController.previousBackStackEntry != null) {
                        navController.popBackStack()
                    }
                },
                onAuthenticated = {
                    navController.navigate(Routes.HOME) {
                        popUpTo(0) { inclusive = true }
                    }
                },
            )
        }

        composable(Routes.HOME) {
            LeaguesScreen(
                container = container,
                onOpenLeague = { leagueId -> navController.navigate(Routes.league(leagueId)) },
                onOpenMembers = { leagueId ->
                    navController.navigate(Routes.leagueMembers(leagueId))
                },
                onOpenConfig = { navController.navigate(Routes.CONFIG) },
            )
        }

        composable(Routes.LEAGUE) { entry ->
            val leagueId = entry.arguments?.getString("leagueId").orEmpty()
            // La sincronizzazione periodica e' riservata agli admin (N5): i
            // non-admin riceverebbero errori RLS dal worker.
            LaunchedEffect(leagueId) {
                val isAdmin = runCatching { container.leagueRepository.isAdmin(leagueId) }
                    .getOrDefault(false)
                if (isAdmin) {
                    F1SyncScheduler.schedule(context, leagueId)
                } else {
                    F1SyncScheduler.cancel(context, leagueId)
                }
            }
            LeagueScreen(
                container = container,
                leagueId = leagueId,
                onBack = { navController.popBackStack() },
                onOpenMembers = { id -> navController.navigate(Routes.leagueMembers(id)) },
                onOpenCalendar = { id -> navController.navigate(Routes.calendar(id)) },
                onOpenStandings = { id -> navController.navigate(Routes.leagueStandings(id)) },
                onOpenRules = { id -> navController.navigate(Routes.leagueRules(id)) },
                onOpenPrediction = { raceId -> navController.navigate(Routes.racePrediction(raceId)) },
                onSyncCalendar = {
                    scope.launch {
                        // Null = sincronizzazione gia' in corso: nessun messaggio.
                        val result = container.f1SyncStateHolder.syncCalendar(leagueId)
                        if (result != null) {
                            val message = when (result) {
                                is SyncResult.Success -> buildString {
                                    append("Calendario aggiornato (${result.created} gare)")
                                    if (result.pastSkipped > 0) {
                                        append(" · ${result.pastSkipped} gare passate saltate")
                                    }
                                }
                                is SyncResult.Failure -> result.message
                            }
                            snackbarState.showSnackbar(message)
                        }
                    }
                },
            )
        }

        composable(Routes.LEAGUE_STANDINGS) { entry ->
            val leagueId = entry.arguments?.getString("leagueId").orEmpty()
            StandingsScreen(
                container = container,
                leagueId = leagueId,
                onBack = { navController.popBackStack() },
            )
        }

        composable(Routes.LEAGUE_RULES) { entry ->
            val leagueId = entry.arguments?.getString("leagueId").orEmpty()
            RulesScreen(
                container = container,
                leagueId = leagueId,
                onBack = { navController.popBackStack() },
            )
        }

        composable(Routes.CALENDAR) { entry ->
            val leagueId = entry.arguments?.getString("leagueId").orEmpty()
            CalendarScreen(
                container = container,
                leagueId = leagueId,
                onBack = { navController.popBackStack() },
                onOpenResult = { raceId, _ ->
                    navController.navigate(Routes.raceResults(raceId))
                },
                onOpenPrediction = { raceId ->
                    navController.navigate(Routes.racePrediction(raceId))
                },
                onSyncResults = {
                    scope.launch {
                        // Null = sincronizzazione gia' in corso: nessun messaggio.
                        val result = container.f1SyncStateHolder.syncResults(leagueId)
                        if (result != null) {
                            val message = when (result) {
                                is SyncResult.Success -> "Risultati aggiornati (${result.completed} gare)"
                                is SyncResult.Failure -> result.message
                            }
                            snackbarState.showSnackbar(message)
                        }
                    }
                },
            )
        }

        composable(Routes.RACE_PREDICTION) { entry ->
            val raceId = entry.arguments?.getString("raceId").orEmpty()
            PredictionScreen(
                container = container,
                raceId = raceId,
                onBack = { navController.popBackStack() },
            )
        }

        composable(Routes.RACE_RESULTS) { entry ->
            val raceId = entry.arguments?.getString("raceId").orEmpty()
            RaceResultScreen(
                container = container,
                raceId = raceId,
                onBack = { navController.popBackStack() },
            )
        }

        composable(Routes.LEAGUE_MEMBERS) { entry ->
            val leagueId = entry.arguments?.getString("leagueId").orEmpty()
            LeagueMembersScreen(
                container = container,
                leagueId = leagueId,
                onBack = { navController.popBackStack() },
                onLeftLeague = {
                    // Abbandono della lega: ferma anche la sync periodica (N5).
                    F1SyncScheduler.cancel(context, leagueId)
                    navController.navigate(Routes.HOME) {
                        popUpTo(0) { inclusive = true }
                    }
                },
            )
        }
        }

        SnackbarHost(
            hostState = snackbarState,
            modifier = Modifier.align(Alignment.BottomCenter),
        )
    }
}
