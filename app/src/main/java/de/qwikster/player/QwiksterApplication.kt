package de.qwikster.player

import android.app.Application
import android.content.Context
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.disk.DiskCache
import coil.memory.MemoryCache
import dagger.hilt.android.HiltAndroidApp
import de.qwikster.player.core.CrashReporter
import de.qwikster.player.data.prefs.LanguageStore
import de.qwikster.player.data.prefs.SettingsStore
import de.qwikster.player.data.remote.UserAgents
import de.qwikster.player.di.ApplicationScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Einstiegspunkt der App.
 *
 * Der eigene [ImageLoader] ist auf TV-Hardware zugeschnitten: Senderlogos
 * sind klein, aber es sind sehr viele. Ein großzügiger Festplatten-Cache
 * spart bei jedem Start hunderte HTTP-Anfragen, während der RAM-Cache
 * bewusst klein bleibt (Fire TV Sticks haben oft nur 1 GB).
 */
@HiltAndroidApp
class QwiksterApplication : Application(), ImageLoaderFactory {

    @Inject lateinit var settingsStore: SettingsStore

    // `@field:` ist hier nicht schmückendes Beiwerk: Ohne die ausdrückliche
    // Angabe legte Kotlin die Kennzeichnung auf die *Eigenschaft*, während
    // Dagger sie am *Feld* sucht – und suchte dann nach einem beliebigen
    // CoroutineScope, den es gar nicht gibt.
    @Inject @field:ApplicationScope lateinit var appScope: CoroutineScope

    /**
     * Auch der Anwendungskontext bekommt die gewählte Sprache.
     *
     * ViewModels und Repositories holen ihre Texte über `@ApplicationContext`
     * – ohne diesen Schritt blieben Fortschritts- und Fehlermeldungen in der
     * Gerätesprache stehen, während die restliche Oberfläche längst
     * umgestellt ist.
     */
    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(LanguageStore.wrap(base))
    }

    /**
     * So früh wie möglich: Alles, was danach kommt – Hilt, Room, der
     * Bildlader –, kann bereits abstürzen, und genau diese Abstürze sind die
     * schwer zu findenden.
     */
    override fun onCreate() {
        super.onCreate()
        CrashReporter.install(this)

        // Die gewählte Abspielprogramm-Kennung an einer einzigen Stelle in
        // den Netzwerkteil geben – siehe [UserAgents.preferred]. Das läuft
        // hier und nicht in einem Bildschirm, weil auch der erste Import
        // schon danach fragt, lange bevor die Einstellungen je geöffnet
        // wurden.
        appScope.launch {
            settingsStore.settings
                .map { it.userAgent }
                .distinctUntilChanged()
                .collect { UserAgents.preferred = it }
        }
    }

    override fun newImageLoader(): ImageLoader =
        ImageLoader.Builder(this)
            .memoryCache {
                MemoryCache.Builder(this)
                    .maxSizePercent(0.15)
                    .build()
            }
            .diskCache {
                DiskCache.Builder()
                    .directory(cacheDir.resolve("image_cache"))
                    .maxSizeBytes(96L * 1024 * 1024)
                    .build()
            }
            // Beim Scrollen durch die Senderliste sonst deutlich sichtbares Flackern.
            .crossfade(false)
            .respectCacheHeaders(false)
            .build()
}
