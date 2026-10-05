package org.example.memosm.api

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.JsonParser
import com.google.gson.TypeAdapter
import com.google.gson.TypeAdapterFactory
import com.google.gson.reflect.TypeToken
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonToken
import com.google.gson.stream.JsonWriter
import kotlin.time.Instant
import org.example.memosm.model.Visibility
import org.example.memosm.model.Memo

/**
 * Shared Gson instance configured with type adapters for custom types
 * used across the app (Retrofit, Room cache, etc.).
 */
object GsonProvider {
    val gson: Gson = GsonBuilder()
        .registerTypeAdapter(Instant::class.java, InstantTypeAdapter())
        .registerTypeAdapter(Visibility::class.java, VisibilityTypeAdapter())
        .registerTypeAdapterFactory(MemoTypeAdapterFactory())
        .create()
}

/** Gson bypasses Kotlin constructor defaults; normalize memo visibility at the JSON boundary. */
private class MemoTypeAdapterFactory : TypeAdapterFactory {
    override fun <T> create(gson: Gson, type: TypeToken<T>): TypeAdapter<T>? {
        if (type.rawType != Memo::class.java) return null
        val delegate = gson.getDelegateAdapter(this, type)
        return object : TypeAdapter<T>() {
            override fun write(out: JsonWriter, value: T?) = delegate.write(out, value)

            override fun read(reader: JsonReader): T? {
                val json = JsonParser.parseReader(reader)
                if (json.isJsonObject) {
                    val memo = json.asJsonObject
                    val visibility = memo.get("visibility")
                    val valid = visibility?.isJsonPrimitive == true && visibility.asJsonPrimitive.isString &&
                        Visibility.entries.any { it.name == visibility.asString }
                    if (!valid) memo.addProperty("visibility", Visibility.PRIVATE.name)
                }
                return delegate.fromJsonTree(json)
            }
        }
    }
}

/**
 * Gson TypeAdapter that serializes/deserializes [kotlin.time.Instant]
 * to/from ISO 8601 strings (e.g. "2026-02-02T21:50:22Z").
 */
class InstantTypeAdapter : TypeAdapter<Instant?>() {
    override fun write(out: JsonWriter, value: Instant?) {
        if (value == null) {
            out.nullValue()
        } else {
            out.value(value.toString())
        }
    }

    override fun read(`in`: JsonReader): Instant? {
        if (`in`.peek() == JsonToken.NULL) {
            `in`.nextNull()
            return null
        }
        val str = `in`.nextString()
        return try {
            Instant.parse(str)
        } catch (_: Exception) {
            null
        }
    }
}


class VisibilityTypeAdapter : TypeAdapter<Visibility?>() {
    override fun write(out: JsonWriter, value: Visibility?) {
        if (value == null) {
            out.nullValue()
        } else {
            out.value(value.name)
        }
    }

    override fun read(`in`: JsonReader): Visibility? {
        if (`in`.peek() == JsonToken.NULL) {
            `in`.nextNull()
            return null
        }
        val str = `in`.nextString()
        if (str.isNullOrBlank()) return null
        return try {
            Visibility.valueOf(str)
        } catch (_: Exception) {
            null
        }
    }
}
