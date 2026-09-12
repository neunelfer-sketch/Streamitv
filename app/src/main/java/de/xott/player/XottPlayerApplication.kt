package de.xott.player

import android.app.Application
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.disk.DiskCache
import coil.memory.MemoryCache
import dagger.hilt.android.HiltAndroidApp

/**
 * Einstiegspunkt der App.
 *
 * Der eigene [ImageLoader] ist auf TV-Hardware zugeschnitten: Senderlogos
 * sind klein, aber es sind sehr viele. Ein großzügiger Festplatten-Cache
 * spart bei jedem Start hunderte HTTP-Anfragen, während der RAM-Cache
 * bewusst klein bleibt (Fire TV Sticks haben oft nur 1 GB).
 */
@HiltAndroidApp
class XottPlayerApplication : Application(), ImageLoaderFactory {

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
