package app.letnievpn.data

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket

/** Общее состояние приложения: UI и VpnService живут в одном процессе и читают его отсюда. */
object Repo {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    val subs = MutableStateFlow<List<Sub>>(emptyList())
    val smart = MutableStateFlow(true)
    val selected = MutableStateFlow<String?>(null)

    /** пинг в мс по id сервера; -1 — недоступен */
    val pings = MutableStateFlow<Map<String, Int>>(emptyMap())
    val vpn = MutableStateFlow(VpnStatus.OFF)

    /** сервер, к которому сейчас подключены */
    val vpnServer = MutableStateFlow<String?>(null)

    private var store: Store? = null
    private val pingMutex = Mutex()

    @Synchronized
    fun init(context: Context) {
        if (store != null) return
        val s = Store(context)
        store = s
        subs.value = SubJson.fromJson(s.subsJson)
        smart.value = s.smart
        selected.value = s.selected
    }

    fun xudpKey(): String = store!!.xudpKey

    fun hwid(): String = store?.hwid ?: ""

    /** Причина последней неудачной загрузки подписки (для пустого списка). */
    val lastError = MutableStateFlow("")

    fun allServers(): List<Server> = subs.value.flatMap { it.servers }

    /** Сервер с самым низким пингом из всех подписок. */
    fun best(): Server? {
        val pg = pings.value
        return allServers().filter { (pg[it.id] ?: -1) > 0 }.minByOrNull { pg[it.id]!! }
            ?: allServers().firstOrNull()
    }

    fun current(): Server? {
        if (smart.value) return best()
        val id = selected.value
        return allServers().firstOrNull { it.id == id } ?: best()
    }

    fun setSmart(v: Boolean) {
        smart.value = v
        store?.smart = v
    }

    /** Ручной выбор сервера выключает умное подключение. */
    fun select(id: String) {
        selected.value = id
        store?.selected = id
        setSmart(false)
    }

    fun setDesired(v: Boolean) {
        store?.desired = v
    }

    fun desired(): Boolean = store?.desired ?: false

    fun smartStored(): Boolean = store?.smart ?: true

    private fun persist() {
        store?.subsJson = SubJson.toJson(subs.value)
    }

    // ── пинг ───────────────────────────────────────────────────────

    fun tcpPing(host: String, port: Int, timeoutMs: Int = 2500): Int = try {
        val addr = InetAddress.getByName(host)
        val t = System.nanoTime()
        Socket().use { it.connect(InetSocketAddress(addr, port), timeoutMs) }
        ((System.nanoTime() - t) / 1_000_000).toInt().coerceAtLeast(1)
    } catch (e: Exception) {
        -1
    }

    suspend fun pingAll() {
        pingMutex.withLock {
            val sem = Semaphore(12)
            val list = allServers()
            coroutineScope {
                list.map { s ->
                    async {
                        sem.withPermit {
                            val ms = tcpPing(s.host, s.port)
                            pings.update { it + (s.id to ms) }
                        }
                    }
                }.awaitAll()
            }
        }
    }

    fun refreshPings() {
        scope.launch { pingAll() }
    }

    // ── подписки ───────────────────────────────────────────────────

    /** Вставка из буфера обмена: ссылка на подписку или список ссылок. Ошибки молча игнорируются. */
    suspend fun addFromText(raw: String): Boolean {
        val text = raw.trim()
        if (text.isEmpty()) {
            lastError.value = "буфер обмена пуст"
            return false
        }
        val first = text.lineSequence().first().trim()
        val sub = when {
            first.startsWith("http://", true) || first.startsWith("https://", true) ->
                SubscriptionLoader.fetch(first)
            text.lineSequence().any { LinkParser.isLink(it.trim()) } ->
                SubscriptionLoader.fromLinks(text)
            else -> null
        }
        if (sub == null) {
            lastError.value = SubscriptionLoader.lastError.ifBlank { "не удалось добавить подписку" }
            return false
        }
        lastError.value = ""
        subs.update { list -> listOf(sub) + list.filterNot { it.id == sub.id } }
        persist()
        pingAll()
        return true
    }

    /** Тихо обновляет серверы всех подписок, добавленных по ссылке. */
    fun refreshSubscriptions() {
        scope.launch {
            subs.value.filter { it.url != null }.forEach { old ->
                val fresh = SubscriptionLoader.fetch(old.url!!, old.id)
                if (fresh != null) {
                    subs.update { list -> list.map { if (it.id == old.id) fresh else it } }
                    persist()
                }
            }
            pingAll()
        }
    }
}
