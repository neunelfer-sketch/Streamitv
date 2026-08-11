package de.qwikster.player.ui.common

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Unterhalb dieser Breite gilt ein Bildschirm als "schmal".
 *
 * Mehrspaltige Bildschirme (Hauptbildschirm, Guide, Filme/Serien) sind mit
 * festen dp-Breiten für TV-Auflösungen entworfen (i. d. R. ≥ 960 dp
 * sichtbare Breite). Ein Handy im Querformat liegt meist zwischen 600 und
 * 900 dp – die festen Breiten der ersten Spalten würden dort zusammen mit
 * der letzten (gewichteten) Spalte kaum noch Raum lassen. Bildschirme
 * benutzen diese Schwelle, um unterhalb davon schmalere, aber weiterhin
 * feste Spaltenbreiten zu wählen, statt echter proportionaler Skalierung –
 * das bleibt vorhersagbar und ändert am eigentlichen TV-Layout nichts.
 */
val COMPACT_WIDTH_BREAKPOINT: Dp = 900.dp
