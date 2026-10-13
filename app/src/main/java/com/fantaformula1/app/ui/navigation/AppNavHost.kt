package com.fantaformula1.app.ui.navigation

import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import android.widget.Toast
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
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

    // Uscita dall'account: torna alla schermata di accesso.
    LaunchedEffect(authState) {
        if (authState is AuthState.SignedOut && startDestination != null) {
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

    NavHost(
        navController = navController,
        startDestination = destination,
        modifier = modifier,
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
            // Avvia la sincronizzazione periodica quando l'utente entra in una lega.
            LaunchedEffect(leagueId) {
                F1SyncScheduler.schedule(context, leagueId)
            }
            LeagueScreen(
                container = container,
                leagueId = leagueId,
                onBack = { navController.popBackStack() },
                onOpenMembers = { id -> navController.navigate(Routes.leagueMembers(id)) },
                onOpenCalendar = { id -> navController.navigate(Routes.calendar(id)) },
                onOpenStandings = { id -> navController.navigate(Routes.leagueStandings(id)) },
                onOpenRules = { id -> navController.navigate(Routes.leagueRules(id)) },
                onSyncCalendar = {
                    scope.launch {
                        // Null = sincronizzazione gia' in corso: nessun Toast.
                        val result = container.f1SyncStateHolder.syncCalendar(leagueId)
                        if (result != null) {
                            val message = when (result) {
                                is SyncResult.Success -> "Calendario aggiornato (${result.created} gare)"
                                is SyncResult.Failure -> result.message
                            }
                            Toast.makeText(context, message, Toast.LENGTH_LONG).show()
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
                        // Null = sincronizzazione gia' in corso: nessun Toast.
                        val result = container.f1SyncStateHolder.syncResults(leagueId)
                        if (result != null) {
                            val message = when (result) {
                                is SyncResult.Success -> "Risultati aggiornati (${result.completed} gare)"
                                is SyncResult.Failure -> result.message
                            }
                            Toast.makeText(context, message, Toast.LENGTH_LONG).show()
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
                    navController.navigate(Routes.HOME) {
                        popUpTo(0) { inclusive = true }
                    }
                },
            )
        }
    }
}
