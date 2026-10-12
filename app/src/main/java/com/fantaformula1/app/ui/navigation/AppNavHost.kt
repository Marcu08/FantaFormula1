package com.fantaformula1.app.ui.navigation

import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.fantaformula1.app.core.AppContainer
import com.fantaformula1.app.ui.home.HomeScreen

/**
 * Grafo di navigazione dell'intera app.
 * Le schermate delle singole feature vengono registrate nelle rispettive fasi.
 */
@Composable
fun AppNavHost(
    container: AppContainer,
    modifier: Modifier = Modifier,
    navController: NavHostController = rememberNavController(),
    startDestination: String = Routes.HOME,
) {
    NavHost(
        navController = navController,
        startDestination = startDestination,
        modifier = modifier,
        enterTransition = { slideInHorizontally(tween(240)) { it / 4 } + fadeIn(tween(240)) },
        exitTransition = { fadeOut(tween(180)) },
        popEnterTransition = { fadeIn(tween(180)) },
        popExitTransition = { slideOutHorizontally(tween(240)) { it / 4 } + fadeOut(tween(240)) },
    ) {
        composable(Routes.HOME) {
            HomeScreen()
        }
    }
}
