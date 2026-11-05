package org.example.memosm.serialization

import com.google.gson.JsonParser
import com.google.gson.TypeAdapter
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonWriter
import kotlin.time.Instant

/** Gson adapter for [Instant] that accepts current strings and legacy Gson object snapshots. */
class InstantTypeAdapter : TypeAdapter<Instant?>() {
    override fun write(out: JsonWriter, value: Instant?) {
        if (value == null) {
            out.nullValue()
        } else {
            out.value(value.toString())
        }
    }

    override fun read(`in`: JsonReader): Instant? {
        val json = JsonParser.parseReader(`in`)
        if (json.isJsonNull) return null
        return when {
            json.isJsonPrimitive && json.asJsonPrimitive.isString ->
                runCatching { Instant.parse(json.asString) }.getOrNull()

            // Older snapshots used Gson's reflective Instant representation.
            json.isJsonObject -> {
                val value = json.asJsonObject
                val seconds = value.get("epochSeconds")
                if (seconds?.isJsonPrimitive != true) {
                    null
                } else {
                    val nanos = value.get("nanosecondsOfSecond")
                    runCatching {
                        Instant.fromEpochSeconds(seconds.asLong, nanos?.asLong ?: 0L)
                    }.getOrNull()
                }
            }

            else -> null
        }
    }
}
