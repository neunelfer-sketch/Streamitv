package de.qwikster.player.data.prefs

import de.qwikster.player.di.ApplicationScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import java.net.Authenticator
import java.net.InetSocketAddress
import java.net.PasswordAuthentication
import java.net.Proxy
import java.net.SocketAddress
import java.net.URI
import java.net.ProxySelector
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Leitet den gesamten Netzverkehr der App über einen SOCKS5-Proxy.
 *
 * Der Zugriff auf die Einstellung erfolgt über ein flüchtiges Feld statt
 * über den Datenspeicher: Ein [ProxySelector] wird von OkHttp mitten im
 * Verbindungsaufbau befragt, also auf einem Netzwerk-Thread und ohne
 * Coroutine. Dort auf DataStore zu warten wäre nicht möglich. Stattdessen
 * schreibt ein Beobachter jede Änderung sofort in das Feld – und weil der
 * Selektor bei *jeder* Verbindung neu gefragt wird, wirkt eine Änderung
 * ohne Neustart der App.
 *
 * Der Umweg über den Selektor ist auch der Grund, warum eine einzige Stelle
 * genügt: Playlist, Programmzeitschrift, Update-Prüfung, Live-Streams **und**
 * Filme teilen sich denselben OkHttp-Client.
 */
@Singleton
class ProxySettings @Inject constructor(
    settingsStore: SettingsStore,
    @ApplicationScope scope: CoroutineScope,
) {

    @Volatile
    private var current: Proxy = Proxy.NO_PROXY

    init {
        settingsStore.settings
            .onEach { settings ->
                current = buildProxy(settings)
                applyCredentials(settings)
            }
            .launchIn(scope)
    }

    /**
     * Wird von OkHttp bei jedem Verbindungsaufbau befragt.
     *
     * Bewusst ein Selektor und keine feste `proxy(...)`-Angabe am Client:
     * Der Client entsteht einmal beim Start der App, die Einstellung kann
     * sich danach jederzeit ändern. Ein fest verdrahteter Proxy ließe sich
     * nur mit einem Neustart wechseln.
     */
    fun selector(): ProxySelector = object : ProxySelector() {
        override fun select(uri: URI?): List<Proxy> = listOf(current)

        // Ist der Proxy nicht erreichbar, wird das als gewöhnlicher
        // Verbindungsfehler gemeldet – die Oberfläche zeigt ihn ohnehin an.
        override fun connectFailed(uri: URI?, address: SocketAddress?, failure: java.io.IOException?) = Unit
    }

    private fun buildProxy(settings: AppSettings): Proxy {
        if (!settings.proxyEnabled || settings.proxyHost.isBlank()) return Proxy.NO_PROXY
        return runCatching {
            // Unaufgelöst: Die Namensauflösung übernimmt der Proxy. Löste
            // das Gerät den Namen selbst auf, verriete es die Zieladresse
            // trotz Proxy an den eigenen DNS-Server – und in Netzen, die
            // Panels über DNS sperren, käme man gar nicht erst durch.
            Proxy(
                Proxy.Type.SOCKS,
                InetSocketAddress.createUnresolved(settings.proxyHost, settings.proxyPort),
            )
        }.getOrDefault(Proxy.NO_PROXY)
    }

    /**
     * Hinterlegt Benutzername und Passwort für SOCKS5.
     *
     * SOCKS-Anmeldung läuft in Java über den globalen [Authenticator] und
     * nicht über OkHttp – dessen `proxyAuthenticator` beantwortet
     * ausschließlich HTTP-Proxy-Anfragen (Statuscode 407), die es bei SOCKS
     * gar nicht gibt.
     */
    private fun applyCredentials(settings: AppSettings) {
        if (!settings.proxyEnabled || settings.proxyUser.isBlank()) {
            runCatching { Authenticator.setDefault(null) }
            return
        }
        runCatching {
            Authenticator.setDefault(
                object : Authenticator() {
                    override fun getPasswordAuthentication(): PasswordAuthentication? {
                        // Nur gegenüber dem eingetragenen Proxy antworten –
                        // ein Server, der unterwegs nach Zugangsdaten fragt,
                        // bekommt sie nicht.
                        if (requestingHost != settings.proxyHost) return null
                        return PasswordAuthentication(
                            settings.proxyUser,
                            settings.proxyPassword.toCharArray(),
                        )
                    }
                },
            )
        }
    }
}
