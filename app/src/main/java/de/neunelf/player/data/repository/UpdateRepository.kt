package de.neunelf.player.data.repository

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.util.Log
import androidx.core.content.FileProvider
import dagger.hilt.android.qualifiers.ApplicationContext
import de.neunelf.player.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/** Eine verfügbare neue Ausgabe der App. */
data class UpdateInfo(
    val versionName: String,
    val downloadUrl: String,
    val notes: String?,
)

/** Fortschritt beim Herunterladen einer Aktualisierung. */
sealed interface DownloadProgress {
    data class Running(val percent: Int) : DownloadProgress
    data class Finished(val file: File) : DownloadProgress
    data class Failed(val message: String) : DownloadProgress
}

/**
 * Eingebaute Aktualisierung.
 *
 * Die App wird nicht über einen Store verteilt, sondern als APK aus den
 * Releases eines öffentlichen GitHub-Repositories. Genau dort schaut sie
 * auch nach neuen Ausgaben: `releases/latest` liefert die Versionsnummer
 * und die Adresse der Datei, ganz ohne Anmeldung.
 *
 * Dass eine Aktualisierung sich über die bestehende Installation legen kann,
 * hängt an der Signatur: Alle Builds werden mit demselben (im Repository
 * liegenden) Testschlüssel signiert. Mit wechselnden Schlüsseln würde
 * Android die Installation mit "Signaturen stimmen nicht überein" ablehnen.
 */
@Singleton
class UpdateRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val httpClient: OkHttpClient,
    private val json: Json,
) {

    /** Version dieser Installation, z. B. "1.0.28". */
    val currentVersion: String get() = BuildConfig.VERSION_NAME

    /**
     * Fragt beim Repository nach der neuesten Ausgabe.
     *
     * @return die neue Ausgabe, oder `null` wenn diese Installation bereits
     *         aktuell ist.
     * @throws Exception bei Netzwerk- oder Antwortfehlern – der Aufrufer
     *         entscheidet, ob das sichtbar wird (bei der automatischen
     *         Prüfung im Hintergrund soll es das nicht).
     */
    suspend fun check(): UpdateInfo? = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url("https://api.github.com/repos/${BuildConfig.UPDATE_REPO}/releases/latest")
            .header("Accept", "application/vnd.github+json")
            .build()

        val body = httpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) error("GitHub antwortete mit HTTP ${response.code}")
            response.body?.string().orEmpty()
        }
        if (body.isBlank()) return@withContext null

        // Serializer ausdrücklich benennen statt über die reified-Variante:
        // Die sucht ihn zur Laufzeit, und die R8-Regeln des Release-Builds
        // decken nur `data.remote.**` ab. Ein Aufruf von `serializer()` ist
        // für R8 dagegen eine ganz normale Referenz.
        val release = json.decodeFromString(ReleaseDto.serializer(), body)
        val latestVersion = release.tagName.trimStart('v', 'V').trim()
        if (latestVersion.isBlank()) return@withContext null
        if (compareVersions(latestVersion, currentVersion) <= 0) return@withContext null

        val asset = release.assets.pickApk() ?: return@withContext null
        UpdateInfo(
            versionName = latestVersion,
            downloadUrl = asset.downloadUrl,
            notes = release.body?.takeIf { it.isNotBlank() },
        )
    }

    /**
     * Lädt die APK herunter und meldet den Fortschritt.
     *
     * Die Datei landet im app-eigenen Bereich: Dort wird sie beim
     * Deinstallieren automatisch mit entfernt, und es braucht keine
     * Speicher-Berechtigung.
     */
    fun download(info: UpdateInfo): Flow<DownloadProgress> = flow {
        val target = File(updateDir(), "9elf-Player-${info.versionName}.apk")
        try {
            val request = Request.Builder().url(info.downloadUrl).build()
            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) error("Server antwortete mit HTTP ${response.code}")
                val responseBody = response.body ?: error("Leere Antwort")
                val total = responseBody.contentLength()

                // Erst in eine Nebendatei schreiben und am Ende umbenennen:
                // Bricht der Download ab, bleibt keine halbe APK liegen, die
                // beim nächsten Versuch als fertig gälte.
                val partial = File(target.absolutePath + ".part")
                partial.delete()

                responseBody.byteStream().use { input ->
                    partial.outputStream().use { output ->
                        val buffer = ByteArray(DOWNLOAD_BUFFER)
                        var copied = 0L
                        var lastPercent = -1
                        while (true) {
                            val read = input.read(buffer)
                            if (read < 0) break
                            output.write(buffer, 0, read)
                            copied += read
                            if (total > 0) {
                                val percent = ((copied * 100) / total).toInt()
                                // Nur bei echter Änderung melden – sonst
                                // Tausende Aktualisierungen der Oberfläche.
                                if (percent != lastPercent) {
                                    lastPercent = percent
                                    emit(DownloadProgress.Running(percent))
                                }
                            }
                        }
                    }
                }

                target.delete()
                if (!partial.renameTo(target)) error("Heruntergeladene Datei konnte nicht abgelegt werden")
            }
            emit(DownloadProgress.Finished(target))
        } catch (e: Exception) {
            Log.e(TAG, "Aktualisierung konnte nicht geladen werden", e)
            emit(DownloadProgress.Failed(e.message ?: "Unbekannter Fehler"))
        }
    }.flowOn(Dispatchers.IO)

    /**
     * Übergibt die geladene APK an das System.
     *
     * Ab Android 8 darf eine App das nur, wenn der Nutzer ihr das einmalig
     * erlaubt hat. Fehlt die Erlaubnis, führt diese Methode ihn direkt zur
     * passenden Systemeinstellung, statt kommentarlos nichts zu tun.
     *
     * @return `false`, wenn stattdessen die Einstellung geöffnet wurde.
     */
    fun install(file: File): Boolean {
        if (!canInstallPackages()) {
            openInstallPermissionSettings()
            return false
        }

        val uri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            FileProvider.getUriForFile(context, "${context.packageName}.updates", file)
        } else {
            Uri.fromFile(file)
        }

        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(intent)
        return true
    }

    /** Räumt ältere heruntergeladene Dateien weg. */
    fun cleanUp(keep: File? = null) {
        runCatching {
            updateDir().listFiles()?.forEach { file ->
                if (file != keep) file.delete()
            }
        }
    }

    // -----------------------------------------------------------------------
    // Interna
    // -----------------------------------------------------------------------

    private fun canInstallPackages(): Boolean {
        // Vor Android 8 hing die Erlaubnis an einem globalen Schalter, den
        // Fire-TV-Nutzer für die Downloader-App ohnehin gesetzt haben.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return true
        return context.packageManager.canRequestPackageInstalls()
    }

    private fun openInstallPermissionSettings() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        runCatching {
            context.startActivity(
                Intent(
                    Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    Uri.parse("package:${context.packageName}"),
                ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }
    }

    /**
     * Ablageort der Downloads. Bevorzugt der externe app-eigene Bereich:
     * Auf Geräten vor Android 7 bekommt das Installationsprogramm eine
     * Datei im internen Speicher nicht zu lesen.
     */
    private fun updateDir(): File {
        val base = context.getExternalFilesDir(null) ?: context.filesDir
        return File(base, "updates").apply { mkdirs() }
    }

    /**
     * Wählt die passende Datei aus dem Release.
     *
     * Test- und Release-Build tragen unterschiedliche Anwendungs-IDs und
     * sind damit für Android zwei verschiedene Apps. Ein Testbuild muss
     * also die Debug-Datei ziehen, sonst installiert er die App ein zweites
     * Mal daneben, statt sich selbst zu erneuern.
     */
    private fun List<AssetDto>.pickApk(): AssetDto? {
        val apks = filter { it.name.endsWith(".apk", ignoreCase = true) }
        val debugApks = apks.filter { it.name.contains("-debug", ignoreCase = true) }
        val releaseApks = apks - debugApks.toSet()
        val preferred = if (BuildConfig.DEBUG) debugApks else releaseApks
        // "…-latest.apk" trägt den festen Namen, alle anderen die Version.
        return preferred.firstOrNull { it.name.contains("latest", ignoreCase = true) }
            ?: preferred.firstOrNull()
    }

    @Serializable
    private data class ReleaseDto(
        @SerialName("tag_name") val tagName: String = "",
        val name: String = "",
        val body: String? = null,
        val assets: List<AssetDto> = emptyList(),
    )

    @Serializable
    private data class AssetDto(
        val name: String = "",
        @SerialName("browser_download_url") val downloadUrl: String = "",
    )

    companion object {
        private const val TAG = "UpdateRepository"
        private const val DOWNLOAD_BUFFER = 64 * 1024

        /**
         * Vergleicht zwei Versionsangaben abschnittsweise als Zahlen.
         *
         * Ein reiner Textvergleich wäre falsch: "1.0.9" käme darin nach
         * "1.0.28", die App hielte sich also für aktueller als sie ist und
         * würde die Aktualisierung nie anbieten.
         *
         * @return negativ wenn [a] älter als [b] ist, 0 bei Gleichstand.
         */
        fun compareVersions(a: String, b: String): Int {
            val left = a.split('.').map { it.filter(Char::isDigit).toIntOrNull() ?: 0 }
            val right = b.split('.').map { it.filter(Char::isDigit).toIntOrNull() ?: 0 }
            for (index in 0 until maxOf(left.size, right.size)) {
                val result = (left.getOrElse(index) { 0 }).compareTo(right.getOrElse(index) { 0 })
                if (result != 0) return result
            }
            return 0
        }
    }
}
