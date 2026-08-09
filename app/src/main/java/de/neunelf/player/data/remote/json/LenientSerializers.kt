package de.neunelf.player.data.remote.json

import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Tolerante Serializer für die Xtream-Codes-API.
 *
 * Hintergrund: Xtream-Panels (und die zahllosen Forks davon) sind bei den
 * Datentypen extrem inkonsistent. Ein und dasselbe Feld kommt je nach Panel
 * und Endpunkt als Zahl, als String, als `null` oder als leerer String an:
 *
 * ```
 * "stream_id": 12345        vs.  "stream_id": "12345"
 * "category_id": null       vs.  "category_id": "7"
 * "tv_archive": 1           vs.  "tv_archive": "1"     vs. true
 * "rating": 8.4             vs.  "rating": "8.4"       vs. ""
 * ```
 *
 * Mit den Standard-Serializern würde jede dieser Varianten die komplette
 * Antwort zum Absturz bringen. Die Serializer hier lesen deshalb das rohe
 * [kotlinx.serialization.json.JsonElement] und konvertieren defensiv –
 * im Zweifel auf einen neutralen Standardwert statt auf eine Exception.
 */

/** Liest jeden Primitiven als String; `null`/Objekte/Arrays werden zu `""`. */
object FlexibleStringSerializer : KSerializer<String> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("FlexibleString", PrimitiveKind.STRING)

    override fun deserialize(decoder: Decoder): String {
        val input = decoder as? JsonDecoder ?: return decoder.decodeString()
        return when (val element = input.decodeJsonElement()) {
            is JsonNull -> ""
            is JsonPrimitive -> element.content
            // Manche Panels liefern z. B. "backdrop_path" mal als Array, mal als String.
            is JsonArray -> element.firstOrNull()?.let { (it as? JsonPrimitive)?.content } ?: ""
            is JsonObject -> ""
        }
    }

    override fun serialize(encoder: Encoder, value: String) = encoder.encodeString(value)
}

/** Wie [FlexibleStringSerializer], liefert aber `null` statt Leerstring. */
object FlexibleNullableStringSerializer : KSerializer<String?> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("FlexibleNullableString", PrimitiveKind.STRING)

    override fun deserialize(decoder: Decoder): String? =
        FlexibleStringSerializer.deserialize(decoder).takeIf { it.isNotBlank() }

    override fun serialize(encoder: Encoder, value: String?) {
        if (value == null) encoder.encodeString("") else encoder.encodeString(value)
    }
}

/** Liest Zahl-oder-String als [Int]; nicht parsebare Werte werden zu `0`. */
object FlexibleIntSerializer : KSerializer<Int> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("FlexibleInt", PrimitiveKind.INT)

    override fun deserialize(decoder: Decoder): Int {
        val raw = FlexibleStringSerializer.deserialize(decoder)
        // "8.0" -> 8 : manche Panels liefern Ganzzahlen als Fließkomma.
        return raw.toIntOrNull() ?: raw.toDoubleOrNull()?.toInt() ?: 0
    }

    override fun serialize(encoder: Encoder, value: Int) = encoder.encodeInt(value)
}

/** Liest Zahl-oder-String als [Long]; nicht parsebare Werte werden zu `0`. */
object FlexibleLongSerializer : KSerializer<Long> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("FlexibleLong", PrimitiveKind.LONG)

    override fun deserialize(decoder: Decoder): Long {
        val raw = FlexibleStringSerializer.deserialize(decoder)
        return raw.toLongOrNull() ?: raw.toDoubleOrNull()?.toLong() ?: 0L
    }

    override fun serialize(encoder: Encoder, value: Long) = encoder.encodeLong(value)
}

/** Liest Zahl-oder-String als [Double]; nicht parsebare Werte werden zu `0.0`. */
object FlexibleDoubleSerializer : KSerializer<Double> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("FlexibleDouble", PrimitiveKind.DOUBLE)

    override fun deserialize(decoder: Decoder): Double {
        val raw = FlexibleStringSerializer.deserialize(decoder)
        // Einige Panels benutzen Komma als Dezimaltrenner ("7,8").
        return raw.toDoubleOrNull() ?: raw.replace(',', '.').toDoubleOrNull() ?: 0.0
    }

    override fun serialize(encoder: Encoder, value: Double) = encoder.encodeDouble(value)
}

/** Akzeptiert `true/false`, `1/0` und `"1"/"0"/"true"/"yes"`. */
object FlexibleBooleanSerializer : KSerializer<Boolean> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("FlexibleBoolean", PrimitiveKind.BOOLEAN)

    override fun deserialize(decoder: Decoder): Boolean =
        when (FlexibleStringSerializer.deserialize(decoder).lowercase().trim()) {
            "1", "true", "yes", "on" -> true
            else -> false
        }

    override fun serialize(encoder: Encoder, value: Boolean) = encoder.encodeBoolean(value)
}

/**
 * Xtream liefert bei `get_series_info` die Episoden als Map
 * `"1": [ ... ], "2": [ ... ]` – bei manchen Panels aber als Array von Arrays.
 * Dieser Helfer normalisiert beides auf eine Liste von (Staffel, Elemente).
 */
fun normalizeSeasonMap(element: kotlinx.serialization.json.JsonElement?): List<Pair<Int, JsonArray>> =
    when (element) {
        null, is JsonNull -> emptyList()
        is JsonObject -> element.entries.mapNotNull { (key, value) ->
            val season = key.toIntOrNull() ?: return@mapNotNull null
            val array = value as? JsonArray ?: return@mapNotNull null
            season to array
        }.sortedBy { it.first }

        is JsonArray -> element.mapIndexedNotNull { index, value ->
            val array = value as? JsonArray ?: return@mapIndexedNotNull null
            index to array
        }

        else -> throw SerializationException("Unerwartetes Format für Staffel-Map: $element")
    }
