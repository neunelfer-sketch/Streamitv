package de.qwikster.player.ui.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import de.qwikster.player.data.model.Channel
import de.qwikster.player.data.model.StreamKind
import de.qwikster.player.ui.contact.ContactScreen
import de.qwikster.player.ui.guide.GuideScreen
import de.qwikster.player.ui.home.HomeScreen
import de.qwikster.player.ui.login.LoginScreen
import de.qwikster.player.ui.player.PlayerScreen
import de.qwikster.player.ui.search.SearchScreen
import de.qwikster.player.ui.settings.EpgSourceScreen
import de.qwikster.player.ui.settings.SettingsScreen
import de.qwikster.player.ui.recordings.RecordingsScreen
import de.qwikster.player.ui.recordings.ScheduleScreen
import de.qwikster.player.ui.vod.MovieDetailScreen
import de.qwikster.player.ui.vod.SeriesDetailScreen
import de.qwikster.player.ui.vod.VodScreen
import de.qwikster.player.ui.vodplayer.VodPlayerScreen

/** Alle Ziele der App. */
object Routes {
    const val LOGIN = "login"
    const val HOME = "home"
    const val GUIDE = "guide"
    const val PLAYER = "player"
    const val MOVIES = "movies"
    const val SERIES = "series"
    const val SETTINGS = "settings"
    const val CONTACT = "contact"
    const val SEARCH = "search"
    const val EPG_SOURCE = "epg_source"
    const val RECORDINGS = "recordings"
    const val RECORDING_SCHEDULE = "recording_schedule"

    /** Filme, Serien und Episoden über ihre ID – die IDs enthalten keine Zugangsdaten. */
    const val MOVIE_DETAIL = "movie_detail/{streamId}"
    const val MOVIE_PLAYER = "movie_player/{streamId}"
    const val SERIES_DETAIL = "series_detail/{seriesId}"
    const val EPISODE_PLAYER = "episode_player/{episodeId}"
    const val RECORDING_PLAYER = "recording_player/{recordingId}"

    fun movieDetail(streamId: String) = "movie_detail/$streamId"
    fun moviePlayer(streamId: String) = "movie_player/$streamId"
    fun seriesDetail(seriesId: String) = "series_detail/$seriesId"
    fun episodePlayer(episodeId: String) = "episode_player/$episodeId"
    fun recordingPlayer(recordingId: Long) = "recording_player/$recordingId"
}

/**
 * Navigationsgraph.
 *
 * Der aktuell laufende Sender wird **nicht** als Argument durch die Route
 * geschleust, sondern in einem gemeinsamen Zustand gehalten. Grund: das
 * Channel-Objekt enthält URLs mit Zugangsdaten – die haben in einer
 * Navigations-URL (und damit potenziell im Backstack-Log) nichts verloren.
 *
 * `hasPlaylist` bestimmt hier **nur** das Startziel der allerersten
 * Komposition. Es gibt bewusst keine reaktive `LaunchedEffect`, die bei
 * jeder Änderung automatisch navigiert: Beim Speichern einer neuen Playlist
 * wird sie sofort aktiv (siehe `PlaylistDao.insertAndActivate`), lange bevor
 * der Erstimport fertig ist. Eine reaktive Navigation würde in genau diesem
 * Moment von der Einrichtung wegspringen, deren Rückstapel-Eintrag entfernen
 * und damit `LoginViewModel` samt der noch laufenden Synchronisierung
 * abbrechen. Beide echten Übergänge werden deshalb explizit an ihrer
 * Quelle ausgelöst: [de.qwikster.player.ui.login.LoginScreen] navigiert erst,
 * wenn der Import wirklich abgeschlossen ist, und
 * [de.qwikster.player.ui.settings.SettingsScreen] navigiert beim Löschen der
 * Playlist sofort selbst. Das Aufsplitten des Kaltstart-Sonderfalls
 * (Datenbankwert noch nicht geladen) übernimmt bereits `MainActivity`, indem
 * sie diesen Graphen erst komponiert, sobald der echte Wert vorliegt.
 */
@Composable
fun QwiksterNavHost(
    hasPlaylist: Boolean,
    onEnterPip: () -> Unit,
    navController: NavHostController = rememberNavController(),
) {
    // Der Sender, den der Player abspielen soll.
    var pendingChannel by remember { mutableStateOf<Channel?>(null) }

    val startDestination = if (hasPlaylist) Routes.HOME else Routes.LOGIN

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
                onOpenSearch = { navController.navigate(Routes.SEARCH) },
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
                onPlayMovie = { streamId -> navController.navigate(Routes.movieDetail(streamId)) },
                onOpenSeries = {},
            )
        }

        composable(Routes.SERIES) {
            VodScreen(
                kind = StreamKind.SERIES,
                onPlayMovie = {},
                onOpenSeries = { seriesId -> navController.navigate(Routes.seriesDetail(seriesId)) },
                onPlayEpisode = { episodeId -> navController.navigate(Routes.episodePlayer(episodeId)) },
            )
        }

        composable(
            route = Routes.MOVIE_DETAIL,
            arguments = listOf(navArgument("streamId") { type = NavType.StringType }),
        ) { entry ->
            val streamId = entry.arguments?.getString("streamId").orEmpty()
            MovieDetailScreen(
                onPlay = { navController.navigate(Routes.moviePlayer(streamId)) },
                onBack = { navController.popBackStack() },
            )
        }

        composable(
            route = Routes.MOVIE_PLAYER,
            arguments = listOf(navArgument("streamId") { type = NavType.StringType }),
        ) {
            VodPlayerScreen(onExit = { navController.popBackStack() })
        }

        composable(
            route = Routes.SERIES_DETAIL,
            arguments = listOf(navArgument("seriesId") { type = NavType.StringType }),
        ) {
            SeriesDetailScreen(
                onPlayEpisode = { episodeId -> navController.navigate(Routes.episodePlayer(episodeId)) },
                onBack = { navController.popBackStack() },
            )
        }

        composable(
            route = Routes.EPISODE_PLAYER,
            arguments = listOf(navArgument("episodeId") { type = NavType.StringType }),
        ) {
            VodPlayerScreen(onExit = { navController.popBackStack() })
        }

        composable(Routes.SETTINGS) {
            SettingsScreen(
                onBack = { navController.popBackStack() },
                onOpenContact = { navController.navigate(Routes.CONTACT) },
                onOpenEpgSource = { navController.navigate(Routes.EPG_SOURCE) },
                onOpenRecordings = { navController.navigate(Routes.RECORDINGS) },
                onPlaylistRemoved = {
                    navController.navigate(Routes.LOGIN) {
                        popUpTo(navController.graph.id) { inclusive = true }
                    }
                },
            )
        }

        composable(Routes.RECORDINGS) {
            RecordingsScreen(
                onPlay = { id -> navController.navigate(Routes.recordingPlayer(id)) },
                onSchedule = { navController.navigate(Routes.RECORDING_SCHEDULE) },
                onBack = { navController.popBackStack() },
            )
        }

        composable(Routes.RECORDING_SCHEDULE) {
            ScheduleScreen(onBack = { navController.popBackStack() })
        }

        composable(
            route = Routes.RECORDING_PLAYER,
            arguments = listOf(navArgument("recordingId") { type = NavType.StringType }),
        ) {
            VodPlayerScreen(onExit = { navController.popBackStack() })
        }

        composable(Routes.EPG_SOURCE) {
            EpgSourceScreen(onBack = { navController.popBackStack() })
        }

        composable(Routes.SEARCH) {
            SearchScreen(
                onPlayChannel = { channel ->
                    pendingChannel = channel
                    navController.navigate(Routes.PLAYER)
                },
                onPlayMovie = { streamId -> navController.navigate(Routes.movieDetail(streamId)) },
                onOpenSeries = { seriesId -> navController.navigate(Routes.seriesDetail(seriesId)) },
            )
        }

        composable(Routes.CONTACT) {
            ContactScreen(onBack = { navController.popBackStack() })
        }
    }
}
