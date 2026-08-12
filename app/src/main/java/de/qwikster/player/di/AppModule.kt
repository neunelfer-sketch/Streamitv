package de.qwikster.player.di

import android.content.Context
import androidx.room.Room
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import de.qwikster.player.BuildConfig
import de.qwikster.player.data.local.AppDatabase
import de.qwikster.player.data.local.CategoryDao
import de.qwikster.player.data.local.ChannelDao
import de.qwikster.player.data.local.EpgDao
import de.qwikster.player.data.local.PlaylistDao
import de.qwikster.player.data.local.RecordingDao
import de.qwikster.player.data.local.RecordingEntity
import de.qwikster.player.data.local.UserDataDao
import de.qwikster.player.data.local.VodDao
import de.qwikster.player.data.prefs.ProxySettings
import de.qwikster.player.data.prefs.SettingsStore
import de.qwikster.player.data.repository.PlaylistSyncer
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
 * [de.qwikster.player.data.repository.PlaylistSyncer.syncInBackground]).
 */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class ApplicationScope

/** "Zugriff verweigert" – siehe den User-Agent-Zweitversuch im OkHttp-Client. */
private const val HTTP_FORBIDDEN = 403

/**
 * Legt die Tabelle für Aufnahmen an – im Stand von **Fassung 5**.
 *
 * Der Wortlaut beschreibt das Schema, wie es zu *dieser* Fassung gehörte,
 * und ausdrücklich nicht den heutigen Stand der [RecordingEntity]. Genau
 * das ist hier schon einmal schiefgegangen: Die Spalte `plannedStartAt`
 * wurde nachträglich mit aufgenommen, um die Tabelle "aktuell" zu halten –
 * woraufhin die folgende Migration sie ein zweites Mal hinzufügen wollte
 * und SQLite mit "duplicate column name" abbrach. Die Datenbank ließ sich
 * dann gar nicht mehr öffnen, die App startete nicht mehr.
 *
 * Eine Migration ist ein Schritt in der Geschichte des Schemas. Sie wird
 * nie nachgebessert; alles Neue kommt als weiterer Schritt dahinter.
 *
 * Kotlin-Standardwerte tauchen bewusst **nicht** als `DEFAULT` auf: Sie
 * gelten im Code, nicht in der Datenbank.
 */
private val MIGRATION_4_5 = object : Migration(4, 5) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `recordings` (
                `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                `playlistId` INTEGER NOT NULL,
                `streamId` TEXT NOT NULL,
                `channelName` TEXT NOT NULL,
                `title` TEXT NOT NULL,
                `filePath` TEXT NOT NULL,
                `startedAt` INTEGER NOT NULL,
                `plannedEndAt` INTEGER NOT NULL,
                `endedAt` INTEGER NOT NULL,
                `state` TEXT NOT NULL,
                `sizeBytes` INTEGER NOT NULL,
                `errorMessage` TEXT
            )
            """.trimIndent(),
        )
    }
}

/**
 * Ergänzt den geplanten Beginn für vorgemerkte Aufnahmen.
 *
 * `DEFAULT 0` ist doppelt nötig: SQLite verlangt beim nachträglichen
 * Hinzufügen einer `NOT NULL`-Spalte einen Vorgabewert, und Room vergleicht
 * ihn beim Öffnen mit dem `@ColumnInfo(defaultValue = "0")` der Entity.
 * Stimmen beide nicht überein, bricht Room ab.
 */
private val MIGRATION_5_6 = object : Migration(5, 6) {
    override fun migrate(db: SupportSQLiteDatabase) {
        // Vorher nachsehen, ob es die Spalte schon gibt. Normalerweise
        // gibt es sie nicht – aber eine fehlerhafte Fassung hat sie
        // zeitweise bereits in Schritt 4->5 angelegt, und ein zweites
        // Hinzufügen beendet die App beim Start mit "duplicate column
        // name". Diese Prüfung kostet nichts und macht die Migration
        // gegen jeden Zwischenstand unempfindlich.
        val hasColumn = db.query("PRAGMA table_info(`recordings`)").use { cursor ->
            val nameColumn = cursor.getColumnIndex("name")
            generateSequence { if (cursor.moveToNext()) cursor.getString(nameColumn) else null }
                .any { it == "plannedStartAt" }
        }
        if (!hasColumn) {
            db.execSQL("ALTER TABLE `recordings` ADD COLUMN `plannedStartAt` INTEGER NOT NULL DEFAULT 0")
        }
    }
}

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
    fun provideOkHttpClient(proxySettings: ProxySettings): OkHttpClient = OkHttpClient.Builder()
        // Deckt in einem Zug alles ab, was die App lädt: Playlist,
        // Programmzeitschrift, Update-Prüfung, Live-Streams, Filme und
        // Aufnahmen teilen sich diesen Client (der Player greift über
        // OkHttpDataSource darauf zu, siehe PlayerFactory).
        .proxySelector(proxySettings.selector())
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
        // Der eigene User-Agent, und bei einer Abfuhr ein zweiter Versuch mit
        // einem verbreiteten Player-Namen.
        //
        // Etliche Panels führen eine Positivliste erlaubter User-Agents und
        // antworten allem Unbekannten mit 403 – der Zugang ist dann völlig in
        // Ordnung, nur der Name gefällt nicht. Da der Nutzer davon nichts
        // ahnen kann und am Panel auch nichts ändern kann, versucht die App
        // es in genau diesem Fall einmal selbst erneut. Der zweite Versuch
        // kostet nur dort etwas, wo es ohnehin schon fehlgeschlagen war.
        .addInterceptor { chain ->
            val request = chain.request().newBuilder()
                .header("User-Agent", PlaylistSyncer.USER_AGENT)
                .build()
            val response = chain.proceed(request)
            if (response.code != HTTP_FORBIDDEN) return@addInterceptor response

            // Die erste Antwort muss geschlossen werden, bevor die nächste
            // Anfrage rausgeht – sonst bleibt die Verbindung im Pool hängen.
            response.close()
            chain.proceed(
                request.newBuilder()
                    .header("User-Agent", PlaylistSyncer.FALLBACK_USER_AGENT)
                    .build(),
            )
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
            // Von 4 auf 5 aber ausdrücklich **nicht** verwerfen: Neu ist nur
            // eine leere Tabelle. Ein Verwerfen nähme dem Zuschauer bei einem
            // Update Favoriten und Verlauf weg – Daten, die es nur hier gibt
            // und die kein Neuladen vom Panel wiederbringt.
            .addMigrations(MIGRATION_4_5, MIGRATION_5_6)
            .build()

    @Provides fun providePlaylistDao(db: AppDatabase): PlaylistDao = db.playlistDao()
    @Provides fun provideCategoryDao(db: AppDatabase): CategoryDao = db.categoryDao()
    @Provides fun provideChannelDao(db: AppDatabase): ChannelDao = db.channelDao()
    @Provides fun provideVodDao(db: AppDatabase): VodDao = db.vodDao()
    @Provides fun provideEpgDao(db: AppDatabase): EpgDao = db.epgDao()
    @Provides fun provideUserDataDao(db: AppDatabase): UserDataDao = db.userDataDao()
    @Provides fun provideRecordingDao(db: AppDatabase): RecordingDao = db.recordingDao()

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
