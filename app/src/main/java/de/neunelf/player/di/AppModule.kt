package de.neunelf.player.di

import android.content.Context
import androidx.room.Room
import de.neunelf.player.BuildConfig
import de.neunelf.player.data.local.AppDatabase
import de.neunelf.player.data.local.CategoryDao
import de.neunelf.player.data.local.ChannelDao
import de.neunelf.player.data.local.EpgDao
import de.neunelf.player.data.local.PlaylistDao
import de.neunelf.player.data.local.UserDataDao
import de.neunelf.player.data.local.VodDao
import de.neunelf.player.data.prefs.SettingsStore
import de.neunelf.player.data.repository.PlaylistSyncer
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import java.util.concurrent.TimeUnit
import javax.inject.Qualifier
import javax.inject.Singleton

/**
 * Ein Gültigkeitsbereich, der die App überlebt statt an einen Bildschirm
 * gebunden zu sein – für Arbeit, die eine Navigation übersteht (siehe
 * [de.neunelf.player.data.repository.PlaylistSyncer.syncInBackground]).
 */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class ApplicationScope

/**
 * Zentrale Objektgraph-Konfiguration.
 *
 * Alles hier ist `@Singleton`: OkHttp-Verbindungspool, Room-Datenbank und
 * der Json-Parser sind teuer zu erzeugen und sollen App-weit geteilt werden.
 */
@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides
    @Singleton
    fun provideJson(): Json = Json {
        // Panels erweitern ihre Antworten ständig um neue Felder.
        ignoreUnknownKeys = true
        // Erlaubt unquotierte Keys und einfache Anführungszeichen – kommt
        // bei selbstgebauten Panels tatsächlich vor.
        isLenient = true
        // `null` für ein Feld mit Default -> Default statt Absturz.
        coerceInputValues = true
        explicitNulls = false
    }

    @Provides
    @Singleton
    fun provideOkHttpClient(): OkHttpClient = OkHttpClient.Builder()
        // IPTV-Panels antworten unter Last gerne langsam; großzügige Timeouts
        // sind hier weniger schlimm als ein abgebrochener Import.
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .callTimeout(0, TimeUnit.MILLISECONDS) // Große EPG-Downloads nicht abwürgen.
        .retryOnConnectionFailure(true)
        // Viele Panels leiten von HTTP auf HTTPS (oder umgekehrt) um.
        .followRedirects(true)
        .followSslRedirects(true)
        .addInterceptor { chain ->
            val request = chain.request().newBuilder()
                .header("User-Agent", PlaylistSyncer.USER_AGENT)
                .build()
            chain.proceed(request)
        }
        .apply {
            if (BuildConfig.DEBUG) {
                addInterceptor(
                    HttpLoggingInterceptor().apply {
                        // BASIC statt BODY: eine Senderliste im Log wäre
                        // mehrere Megabyte groß.
                        level = HttpLoggingInterceptor.Level.BASIC
                        // Zugangsdaten nicht ins Logcat schreiben.
                        redactHeader("Authorization")
                    },
                )
            }
        }
        .build()

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): AppDatabase =
        Room.databaseBuilder(context, AppDatabase::class.java, AppDatabase.NAME)
            // Der Cache ist jederzeit neu aufbaubar – bei einem Schema-Sprung
            // ist Neuladen billiger als eine Migration.
            .fallbackToDestructiveMigration()
            .build()

    @Provides fun providePlaylistDao(db: AppDatabase): PlaylistDao = db.playlistDao()
    @Provides fun provideCategoryDao(db: AppDatabase): CategoryDao = db.categoryDao()
    @Provides fun provideChannelDao(db: AppDatabase): ChannelDao = db.channelDao()
    @Provides fun provideVodDao(db: AppDatabase): VodDao = db.vodDao()
    @Provides fun provideEpgDao(db: AppDatabase): EpgDao = db.epgDao()
    @Provides fun provideUserDataDao(db: AppDatabase): UserDataDao = db.userDataDao()

    @Provides
    @Singleton
    fun provideSettingsStore(@ApplicationContext context: Context): SettingsStore =
        SettingsStore(context)

    @Provides
    @Singleton
    @ApplicationScope
    fun provideApplicationScope(): CoroutineScope =
        // SupervisorJob: ein fehlgeschlagener Hintergrund-Import darf nicht
        // den gemeinsamen Gültigkeitsbereich für alle anderen mit reißen.
        CoroutineScope(SupervisorJob() + Dispatchers.Default)
}
