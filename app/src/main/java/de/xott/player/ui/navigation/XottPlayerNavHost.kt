package de.xott.player.ui.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.navigation.NamedNavArgument
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import de.xott.player.data.model.Channel
import de.xott.player.data.model.EpgProgram
import de.xott.player.data.model.StreamKind
import de.xott.player.ui.guide.GuideScreen
import de.xott.player.ui.home.HomeScreen
import de.xott.player.ui.login.LoginScreen
import de.xott.player.ui.player.PlayerScreen
import de.xott.player.ui.player.StartVod
import de.xott.player.ui.settings.ChannelManagerScreen
import de.xott.player.ui.settings.SettingsScreen
import de.xott.player.ui.vod.MovieDetailScreen
import de.xott.player.ui.vod.SeriesDetailScreen
import de.xott.player.ui.vod.VodScreen

/** Alle Ziele der App. */
object Routes {
    const val LOGIN = "login"
    const val HOME = "home"
    const val GUIDE = "guide"
    const val PLAYER = "player"
    const val MOVIES = "movies"
    const val SERIES = "series"
    const val SETTINGS = "settings"
    const val CHANNEL_MANAGER = "channel_manager"
    const val MOVIE_DETAIL = "movie_detail/{playlistId}/{streamId}"
    const val SERIES_DETAIL = "series_detail/{playlistId}/{seriesId}"

    fun movieDetail(playlistId: Long, streamId: String) = "movie_detail/$playlistId/$streamId"
    fun seriesDetail(playlistId: Long, seriesId: String) = "series_detail/$playlistId/$seriesId"

    val movieDetailArgs: List<NamedNavArgument> = listOf(
        navArgument("playlistId") { type = NavType.LongType },
        navArgument("streamId") { type = NavType.StringType },
    )
    val seriesDetailArgs: List<NamedNavArgument> = listOf(
        navArgument("playlistId") { type = NavType.LongType },
        navArgument("seriesId") { type = NavType.StringType },
    )
}

/**
 * Navigationsgraph.
 *
 * Der aktuell laufende Sender/Inhalt wird **nicht** als Argument durch die
 * Route geschleust, sondern in gemeinsamem Zustand gehalten. Grund: Channel-
 * und Wiedergabe-Objekte enthalten URLs mit Zugangsdaten – die haben in einer
 * Navigations-URL (und damit potenziell im Backstack-Log) nichts verloren.
 * Nur reine IDs (Playlist-/Stream-/Serien-ID) laufen als Routenargumente,
 * für Filme/Serien-Details und die Kanalverwaltung.
 */
@Composable
fun XottPlayerNavHost(
    hasPlaylist: Boolean,
    onEnterPip: () -> Unit,
    navController: NavHostController = rememberNavController(),
) {
    // Was der Player als Nächstes abspielen soll.
    var pendingChannel by remember { mutableStateOf<Channel?>(null) }
    var pendingCatchupProgram by remember { mutableStateOf<EpgProgram?>(null) }
    var pendingVod by remember { mutableStateOf<StartVod?>(null) }

    fun openLiveChannel(channel: Channel) {
        pendingChannel = channel
        pendingCatchupProgram = null
        pendingVod = null
        navController.navigate(Routes.PLAYER)
    }

    fun openCatchup(channel: Channel, program: EpgProgram) {
        pendingChannel = channel
        pendingCatchupProgram = program
        pendingVod = null
        navController.navigate(Routes.PLAYER)
    }

    fun openVod(title: String, url: String) {
        pendingChannel = null
        pendingCatchupProgram = null
        pendingVod = StartVod(title, url)
        navController.navigate(Routes.PLAYER)
    }

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
                onOpenPlayer = { channel -> openLiveChannel(channel) },
                onOpenGuide = { navController.navigate(Routes.GUIDE) },
                onOpenMovies = { navController.navigate(Routes.MOVIES) },
                onOpenSeries = { navController.navigate(Routes.SERIES) },
                onOpenSettings = { navController.navigate(Routes.SETTINGS) },
            )
        }

        composable(Routes.GUIDE) {
            GuideScreen(
                onPlayChannel = { channel -> openLiveChannel(channel) },
                onPlayCatchup = { channel, program -> openCatchup(channel, program) },
                onBack = { navController.popBackStack() },
            )
        }

        composable(Routes.PLAYER) {
            PlayerScreen(
                startChannel = pendingChannel,
                startCatchupProgram = pendingCatchupProgram,
                startVod = pendingVod,
                onExit = { navController.popBackStack() },
                onEnterPip = onEnterPip,
            )
        }

        composable(Routes.MOVIES) {
            VodScreen(
                kind = StreamKind.VOD,
                onOpenMovie = { playlistId, streamId ->
                    navController.navigate(Routes.movieDetail(playlistId, streamId))
                },
                onOpenSeries = { _, _ -> },
            )
        }

        composable(Routes.SERIES) {
            VodScreen(
                kind = StreamKind.SERIES,
                onOpenMovie = { _, _ -> },
                onOpenSeries = { playlistId, seriesId ->
                    navController.navigate(Routes.seriesDetail(playlistId, seriesId))
                },
            )
        }

        composable(Routes.MOVIE_DETAIL, arguments = Routes.movieDetailArgs) {
            MovieDetailScreen(
                onPlay = { title, url -> openVod(title, url) },
                onBack = { navController.popBackStack() },
            )
        }

        composable(Routes.SERIES_DETAIL, arguments = Routes.seriesDetailArgs) {
            SeriesDetailScreen(
                onPlayEpisode = { title, url -> openVod(title, url) },
                onBack = { navController.popBackStack() },
            )
        }

        composable(Routes.SETTINGS) {
            SettingsScreen(
                onBack = { navController.popBackStack() },
                onOpenChannelManager = { navController.navigate(Routes.CHANNEL_MANAGER) },
                onPlaylistRemoved = {
                    navController.navigate(Routes.LOGIN) {
                        popUpTo(navController.graph.id) { inclusive = true }
                    }
                },
            )
        }

        composable(Routes.CHANNEL_MANAGER) {
            ChannelManagerScreen()
        }
    }
}
