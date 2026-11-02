package app.letnievpn.data

import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

/** Загружает подписку по ссылке (список ссылок vless/vmess/trojan/ss, обычно в base64). */
object SubscriptionLoader {

    suspend fun fetch(url: String, keepId: String? = null): Sub? = withContext(Dispatchers.IO) {
        try {
            val c = URL(url).openConnection() as HttpURLConnection
            c.connectTimeout = 15000
            c.readTimeout = 20000
            c.instanceFollowRedirects = true
            c.setRequestProperty("User-Agent", "v2rayNG/1.10.0 letnievpn")
            c.setRequestProperty("Accept", "*/*")
            val body = c.inputStream.bufferedReader().use { it.readText() }
            val title = c.getHeaderField("profile-title")
            val userInfo = c.getHeaderField("subscription-userinfo")
            c.disconnect()

            val links = extractLinks(body)
            if (links.isEmpty()) return@withContext null
            val servers = links.mapNotNull { LinkParser.toServer(it) }.distinctBy { it.id }
            if (servers.isEmpty()) return@withContext null

            val name = decodeTitle(title)
                ?: runCatching { URL(url).host.removePrefix("www.") }.getOrNull()
                ?: "Подписка"
            Sub(
                id = keepId ?: UUID.nameUUIDFromBytes(url.toByteArray()).toString().take(8),
                name = name,
                url = url,
                info = formatInfo(userInfo),
                servers = servers,
            )
        } catch (e: Exception) {
            null
        }
    }

    /** Ссылки, вставленные из буфера прямо списком (без адреса подписки). */
    fun fromLinks(text: String): Sub? {
        val servers = extractLinks(text).mapNotNull { LinkParser.toServer(it) }.distinctBy { it.id }
        if (servers.isEmpty()) return null
        return Sub(
            id = "clip-" + UUID.nameUUIDFromBytes(text.trim().toByteArray()).toString().take(8),
            name = "Из буфера",
            url = null,
            info = "",
            servers = servers,
        )
    }

    private fun extractLinks(body: String): List<String> {
        fun lines(t: String) = t.lineSequence().map { it.trim() }.filter { LinkParser.isLink(it) }.toList()
        val direct = lines(body)
        if (direct.isNotEmpty()) return direct
        return try {
            val clean = body.trim().replace("\\s".toRegex(), "").replace('+', '-').replace('/', '_').trimEnd('=')
            lines(String(Base64.decode(clean, Base64.URL_SAFE or Base64.NO_WRAP), Charsets.UTF_8))
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun decodeTitle(h: String?): String? {
        if (h.isNullOrBlank()) return null
        if (h.startsWith("base64:")) {
            return try {
                String(Base64.decode(h.removePrefix("base64:"), Base64.DEFAULT), Charsets.UTF_8).ifBlank { null }
            } catch (e: Exception) {
                null
            }
        }
        return h
    }

    /** «upload=..; download=..; total=..; expire=..» → «12 ГБ из 50 ГБ · до 14 дек.» */
    private fun formatInfo(h: String?): String {
        if (h.isNullOrBlank()) return ""
        val m = h.split(';').mapNotNull {
            val kv = it.trim().split('=')
            if (kv.size == 2) kv[0].trim() to (kv[1].trim().toLongOrNull() ?: 0L) else null
        }.toMap()
        val parts = ArrayList<String>()
        val total = m["total"] ?: 0L
        if (total > 0) {
            val used = (m["upload"] ?: 0L) + (m["download"] ?: 0L)
            parts.add("${gb(used)} из ${gb(total)}")
        }
        val expire = m["expire"] ?: 0L
        if (expire > 0) {
            val f = SimpleDateFormat("d MMM", Locale("ru"))
            parts.add("до " + f.format(Date(expire * 1000)))
        }
        return parts.joinToString(" · ")
    }

    private fun gb(bytes: Long): String {
        val g = bytes / 1073741824.0
        return (if (g >= 10) String.format(Locale.ROOT, "%.0f", g) else String.format(Locale.ROOT, "%.1f", g)) + " ГБ"
    }
}
