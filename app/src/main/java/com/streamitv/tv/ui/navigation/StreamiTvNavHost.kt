package com.streamitv.tv.ui.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.streamitv.tv.data.model.Channel
import com.streamitv.tv.data.model.StreamKind
import com.streamitv.tv.ui.guide.GuideScreen
import com.streamitv.tv.ui.home.HomeScreen
import com.streamitv.tv.ui.login.LoginScreen
import com.streamitv.tv.ui.player.PlayerScreen
import com.streamitv.tv.ui.settings.SettingsScreen
import com.streamitv.tv.ui.vod.VodScreen

/** Alle Ziele der App. */
object Routes {
    const val LOGIN = "login"
    const val HOME = "home"
    const val GUIDE = "guide"
    const val PLAYER = "player"
    const val MOVIES = "movies"
    const val SERIES = "series"
    const val SETTINGS = "settings"
}

/**
 * Navigationsgraph.
 *
 * Der aktuell laufende Sender wird **nicht** als Argument durch die Route
 * geschleust, sondern in einem gemeinsamen Zustand gehalten. Grund: das
 * Channel-Objekt enthält URLs mit Zugangsdaten – die haben in einer
 * Navigations-URL (und damit potenziell im Backstack-Log) nichts verloren.
 */
@Composable
fun StreamiTvNavHost(
    hasPlaylist: Boolean,
    onEnterPip: () -> Unit,
    navController: NavHostController = rememberNavController(),
) {
    // Der Sender, den der Player abspielen soll.
    var pendingChannel by remember { mutableStateOf<Channel?>(null) }

    val startDestination = if (hasPlaylist) Routes.HOME else Routes.LOGIN

    // Wird die Playlist gelöscht oder neu eingerichtet, muss der Graph neu
    // starten – sonst bliebe der Nutzer auf einem leeren Hauptbildschirm.
    LaunchedEffect(hasPlaylist) {
        val target = if (hasPlaylist) Routes.HOME else Routes.LOGIN
        if (navController.currentDestination?.route !in listOf(target, Routes.PLAYER)) {
            navController.navigate(target) {
                popUpTo(navController.graph.id) { inclusive = true }
            }
        }
    }

    NavHost(navController = navController, startDestination = startDestination) {

        composable(Routes.LOGIN) {
            LoginScreen(
                onDone = {
                    navController.navigate(Routes.HOME) {
                        popUpTo(Routes.LOGIN) { inclusive = true }
                    }
                },
            )
        }

        composable(Routes.HOME) {
            HomeScreen(
                onOpenPlayer = { channel ->
                    pendingChannel = channel
                    navController.navigate(Routes.PLAYER)
                },
                onOpenGuide = { navController.navigate(Routes.GUIDE) },
                onOpenMovies = { navController.navigate(Routes.MOVIES) },
                onOpenSeries = { navController.navigate(Routes.SERIES) },
                onOpenSettings = { navController.navigate(Routes.SETTINGS) },
            )
        }

        composable(Routes.GUIDE) {
            GuideScreen(
                onPlayChannel = { channel ->
                    pendingChannel = channel
                    navController.navigate(Routes.PLAYER)
                },
                onBack = { navController.popBackStack() },
            )
        }

        composable(Routes.PLAYER) {
            PlayerScreen(
                startChannel = pendingChannel,
                onExit = { navController.popBackStack() },
                onEnterPip = onEnterPip,
            )
        }

        composable(Routes.MOVIES) {
            VodScreen(
                kind = StreamKind.VOD,
                onPlayMovie = { /* Detailansicht folgt – Film startet über den Player */ },
                onOpenSeries = {},
            )
        }

        composable(Routes.SERIES) {
            VodScreen(
                kind = StreamKind.SERIES,
                onPlayMovie = {},
                onOpenSeries = { /* Staffelübersicht folgt */ },
            )
        }

        composable(Routes.SETTINGS) {
            SettingsScreen(
                onBack = { navController.popBackStack() },
                onPlaylistRemoved = {
                    navController.navigate(Routes.LOGIN) {
                        popUpTo(navController.graph.id) { inclusive = true }
                    }
                },
            )
        }
    }
}
