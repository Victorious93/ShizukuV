package ai.victorious.bridge.sdk

import android.os.Bundle

/**
 * Converts between plain maps and Bundles. Only a closed set of value types crosses the boundary;
 * anything else is dropped rather than parceled, so a hostile client cannot smuggle Parcelables
 * (and their custom readers) into the privileged process.
 */
object BundleCodec {
    const val MAX_KEYS = 64
    private const val MAX_STRING = 16 * 1024
    private const val MAX_BYTES = 2 * 1024 * 1024

    fun toMap(b: Bundle?): Map<String, Any?> {
        if (b == null) return emptyMap()
        val out = LinkedHashMap<String, Any?>()
        try {
            for (key in b.keySet().take(MAX_KEYS)) {
                @Suppress("DEPRECATION")
                val v = b.get(key)
                when (v) {
                    is String -> if (v.length <= MAX_STRING) out[key] = v
                    is Long -> out[key] = v
                    is Int -> out[key] = v.toLong()
                    is Boolean -> out[key] = v
                    is ByteArray -> if (v.size <= MAX_BYTES) out[key] = v
                    is Array<*> -> out[key] = v.filterIsInstance<String>().take(MAX_KEYS * 16)
                    else -> Unit
                }
            }
        } catch (_: Throwable) {
            // A malformed Bundle (bad lazy parcel) yields an empty/partial map; the handlers then reject missing args.
        }
        return out
    }

    fun toBundle(m: Map<String, Any?>): Bundle {
        val b = Bundle()
        for ((k, v) in m) {
            when (v) {
                null -> Unit
                is String -> b.putString(k, v)
                is Long -> b.putLong(k, v)
                is Int -> b.putLong(k, v.toLong())
                is Boolean -> b.putBoolean(k, v)
                is ByteArray -> b.putByteArray(k, v)
                is Double, is Float -> b.putString(k, v.toString())
                is List<*> -> b.putStringArray(k, v.map { it.toString() }.toTypedArray())
                else -> b.putString(k, v.toString())
            }
        }
        return b
    }
}
