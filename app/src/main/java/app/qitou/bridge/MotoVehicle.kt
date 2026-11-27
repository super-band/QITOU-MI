package app.qitou.bridge

import android.content.Context
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import org.json.JSONObject

data class MotoVehicle(
    val ssid: String,
    val password: String,
    val mac: String?,
    val modelId: String?,
    val name: String? = null,
    val action: Int = 1,
    val sn: String? = null,
) {
    val displayName: String get() = name?.takeIf { it.isNotBlank() } ?: ssid
    val isOpenNetwork: Boolean get() = password.isBlank()

    companion object {
        fun parse(raw: String): MotoVehicle? {
            val text = raw.trim()
            if (text.startsWith("{")) {
                parseJson(text)?.let { return it }
            }
            val query = queryMap(text)
            run {
                val ssid = query["ssid"].orEmpty().trim().ifEmpty { query["wifi"].orEmpty().trim() }
                val password = query["pwd"].orEmpty()
                    .ifEmpty { query["password"].orEmpty() }
                    .ifEmpty { query["passphrase"].orEmpty() }
                val auth = query["auth"].orEmpty()
                val explicitlyPasswordless = query.containsKey("pwd") ||
                    query.containsKey("password") ||
                    auth.contains("open", true) ||
                    auth.equals("none", true)
                if (ssid.isNotEmpty() && (password.isNotEmpty() || explicitlyPasswordless)) {
                    return MotoVehicle(
                        ssid = ssid,
                        password = password,
                        mac = formatMac(query["mac"] ?: query["bm"]),
                        modelId = query["modelid"] ?: query["productid"],
                        name = query["name"],
                        action = query["action"]?.toIntOrNull() ?: 1,
                        sn = query["sn"],
                    )
                }
            }
            val wifi = Regex("""(?i)(?:^|[?&])Wifi=([^&#\s]+)""").find(text) ?: return null
            val ssid = wifi.groupValues[1]
            val after = text.substring(wifi.range.last + 1)
            if (!after.startsWith('#')) return null
            val parts = after.removePrefix("#").split('#', limit = 2)
            val password = parts.getOrNull(0)?.substringBefore('&').orEmpty()
            if (ssid.isBlank()) return null
            val mac = parts.getOrNull(1)?.substringBefore('&')
            val machineId = Regex("""(?i)MachineID=([^&#\s]+)""").find(text)
                ?.groupValues?.get(1)
            val productId = Regex("""(?i)ProductID=([^&#\s]+)""").find(text)
                ?.groupValues?.get(1)
            return MotoVehicle(
                ssid = ssid,
                password = password,
                mac = formatMac(mac) ?: formatMac(machineId),
                modelId = productId,
                name = ssid,
                sn = machineId,
            )
        }

        fun manual(ssid: String, password: String): MotoVehicle? {
            val cleanSsid = ssid.trim()
            if (cleanSsid.isBlank()) return null
            return MotoVehicle(cleanSsid, password, null, null, cleanSsid)
        }

        private fun queryMap(raw: String): Map<String, String> {
            val query = raw.substringAfter('?', "").substringBefore('#')
            if (query.isBlank()) return emptyMap()
            return query.split('&').mapNotNull {
                val at = it.indexOf('=')
                if (at <= 0) null else {
                    val value = decode(it.substring(at + 1))
                    it.substring(0, at).lowercase() to value
                }
            }.toMap()
        }

        private fun parseJson(raw: String): MotoVehicle? = runCatching {
            val json = JSONObject(raw)
            val ssid = json.optString("ssid")
                .ifBlank { json.optString("wifi") }
                .trim()
            if (ssid.isBlank()) return@runCatching null
            MotoVehicle(
                ssid = ssid,
                password = json.optString("pwd")
                    .ifBlank { json.optString("password") },
                mac = formatMac(json.optString("mac").ifBlank { json.optString("bm") }),
                modelId = json.optString("modelid").ifBlank { json.optString("productId") },
                name = json.optString("name").ifBlank { ssid },
                action = json.optInt("action", 1),
                sn = json.optString("sn"),
            )
        }.getOrNull()

        private fun decode(value: String): String =
            runCatching { URLDecoder.decode(value, StandardCharsets.UTF_8.name()) }
                .getOrDefault(value)

        private fun formatMac(raw: String?): String? {
            if (raw.isNullOrBlank()) return null
            val hex = raw.filter { it.isDigit() || it.lowercaseChar() in 'a'..'f' }
            if (hex.length != 12) return raw.takeIf { it.contains(':') }
            return hex.chunked(2).joinToString(":") { it.lowercase() }
        }
    }
}

object MotoVehiclePrefs {
    private const val PREFS = "qitou_vehicle"
    private val lock = Any()
    @Volatile private var cacheLoaded = false
    @Volatile private var cachedVehicle: MotoVehicle? = null

    fun save(context: Context, vehicle: MotoVehicle): Boolean =
        runCatching {
            val encryptedPassword = VehicleSecretStore.encrypt(context, vehicle.password)
            val editor = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putString("ssid", vehicle.ssid)
                .putString("password_cipher", encryptedPassword)
                .putString("mac", vehicle.mac)
                .putString("model", vehicle.modelId)
                .putString("name", vehicle.name)
                .putInt("action", vehicle.action)
                .putString("sn", vehicle.sn)
                .remove("password")
                .remove("raw")
            editor.apply()
            cachedVehicle = vehicle
            cacheLoaded = true
            true
        }.getOrDefault(false)

    fun load(context: Context): MotoVehicle? {
        if (cacheLoaded) return cachedVehicle
        synchronized(lock) {
            if (cacheLoaded) return cachedVehicle
            return loadUncached(context).also {
                cachedVehicle = it
                cacheLoaded = true
            }
        }
    }

    private fun loadUncached(context: Context): MotoVehicle? {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val ssid = p.getString("ssid", null) ?: return null
        val encrypted = p.getString("password_cipher", null)
        @Suppress("DEPRECATION")
        val legacyPassword = p.getString("password", null)
        val password = when {
            encrypted != null -> VehicleSecretStore.decrypt(context, encrypted) ?: return null
            legacyPassword != null -> legacyPassword
            else -> ""
        }
        val vehicle = MotoVehicle(
            ssid = ssid,
            password = password,
            mac = p.getString("mac", null),
            modelId = p.getString("model", null),
            name = p.getString("name", null),
            action = p.getInt("action", 1),
            sn = p.getString("sn", null),
        )
        if (encrypted == null && legacyPassword != null) {
            val appContext = context.applicationContext
            Thread {
                save(appContext, vehicle)
            }.apply {
                name = "vehicle-prefs-migration"
                isDaemon = true
                start()
            }
        }
        return vehicle
    }

    fun clear(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear().apply()
        synchronized(lock) {
            cachedVehicle = null
            cacheLoaded = true
        }
    }
}
