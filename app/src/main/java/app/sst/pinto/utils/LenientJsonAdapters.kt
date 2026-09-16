package app.sst.pinto.utils

import com.squareup.moshi.JsonAdapter
import com.squareup.moshi.JsonReader
import com.squareup.moshi.JsonWriter
import com.squareup.moshi.Types

/**
 * Accept Int from JSON int, float (e.g. 0.0), or numeric string — as used by
 * some portal payloads for redeem amounts.
 */
object LenientIntAdapter {
    val FACTORY = JsonAdapter.Factory { type, annotations, _ ->
        if (annotations.isNotEmpty()) return@Factory null
        val raw = Types.getRawType(type)
        if (raw != Integer::class.java && raw != Int::class.javaPrimitiveType) {
            return@Factory null
        }
        object : JsonAdapter<Int>() {
            override fun fromJson(reader: JsonReader): Int? {
                return when (reader.peek()) {
                    JsonReader.Token.NULL -> {
                        reader.nextNull<Unit>()
                        null
                    }
                    JsonReader.Token.NUMBER -> reader.nextDouble().toInt()
                    JsonReader.Token.STRING -> reader.nextString().toDoubleOrNull()?.toInt()
                    else -> {
                        reader.skipValue()
                        null
                    }
                }
            }

            override fun toJson(writer: JsonWriter, value: Int?) {
                if (value == null) writer.nullValue() else writer.value(value)
            }
        }
    }
}

/**
 * Accept Long from JSON number or numeric string (portal often sends timestamp as string).
 */
object LenientLongAdapter {
    val FACTORY = JsonAdapter.Factory { type, annotations, _ ->
        if (annotations.isNotEmpty()) return@Factory null
        val raw = Types.getRawType(type)
        if (raw != java.lang.Long::class.java && raw != Long::class.javaPrimitiveType) {
            return@Factory null
        }
        object : JsonAdapter<Long>() {
            override fun fromJson(reader: JsonReader): Long? {
                return when (reader.peek()) {
                    JsonReader.Token.NULL -> {
                        reader.nextNull<Unit>()
                        null
                    }
                    JsonReader.Token.NUMBER -> reader.nextDouble().toLong()
                    JsonReader.Token.STRING -> {
                        val s = reader.nextString()
                        s.toLongOrNull() ?: s.toDoubleOrNull()?.toLong()
                    }
                    else -> {
                        reader.skipValue()
                        null
                    }
                }
            }

            override fun toJson(writer: JsonWriter, value: Long?) {
                if (value == null) writer.nullValue() else writer.value(value)
            }
        }
    }
}
