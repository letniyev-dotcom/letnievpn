package app.letnievpn.data

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
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
import java.util.concurrent.ConcurrentHashMap

/** Общее состояние приложения: UI и VpnService живут в одном процессе и читают его отсюда. */
object Repo {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    val subs = MutableStateFlow<List<Sub>>(emptyList())
    val smart = MutableStateFlow(true)
    val selected = MutableStateFlow<String?>(null)

    /** пинг в мс по id сервера; -1 — недоступен */
    val pings = MutableStateFlow<Map<String, Int>>(emptyMap())
    val vpn = MutableStateFlow(VpnStatus.OFF)

    /** реальная задержка через туннель, мс (пока VPN включён и приложение открыто); -1 — нет данных */
    val tunnelDelay = MutableStateFlow(-1)

    /** Экран приложения сейчас на виду: фоновые замеры идут только тогда, чтобы не тратить батарею. */
    @Volatile
    var uiVisible = false
        private set

    private var liveJob: Job? = null
    private val fails = ConcurrentHashMap<String, Int>()

    /** сервер, к которому сейчас подключены */
    val vpnServer = MutableStateFlow<String?>(null)

    private var store: Store? = null
    private val pingMutex = Mutex()

    @Synchronized
    fun init(context: Context) {
        if (store != null) return
        Logger.init(context)
        val s = Store(context)
        store = s
        subs.value = SubJson.fromJson(s.subsJson)
        smart.value = s.smart
        selected.value = s.selected
    }

    fun xudpKey(): String = store!!.xudpKey

    fun hwid(): String = store?.hwid ?: ""

    /** Причина неудачи последней загрузки подписки (внутренняя, на экране не показывается). */
    val lastError = MutableStateFlow("")

    /** Причина, по которой не удалось подключиться; показывается под статусом. */
    val connError = MutableStateFlow("")

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

    suspend fun pingAll(timeoutMs: Int = 2500, quiet: Boolean = false) {
        pingMutex.withLock {
            val sem = Semaphore(12)
            val list = allServers()
            coroutineScope {
                list.map { s ->
                    async {
                        sem.withPermit {
                            val ms = tcpPing(s.host, s.port, timeoutMs)
                            // одиночный сбой не сбрасывает показание: «—» только после двух подряд
                            val value = if (ms < 0) {
                                val n = (fails[s.id] ?: 0) + 1
                                fails[s.id] = n
                                val prev = pings.value[s.id]
                                if (prev != null && prev > 0 && n < 2) prev else -1
                            } else {
                                fails.remove(s.id)
                                ms
                            }
                            pings.update { it + (s.id to value) }
                        }
                    }
                }.awaitAll()
            }
        }
        if (!quiet) Logger.info("ping", "измерено: рабочих ${pings.value.values.count { it > 0 }} из ${allServers().size}")
    }

    fun refreshPings() {
        scope.launch { pingAll() }
    }

    /** Пинг в реальном времени: пока приложение на экране, серверы перемеряются каждые пару секунд. */
    fun startLivePing() {
        uiVisible = true
        if (liveJob?.isActive == true) return
        liveJob = scope.launch {
            while (isActive) {
                pingAll(timeoutMs = 1500, quiet = true)
                delay(1500)
            }
        }
    }

    fun stopLivePing() {
        uiVisible = false
        liveJob?.cancel()
        liveJob = null
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
            Logger.error("sub", "подписка не добавлена: ${lastError.value}")
            return false
        }
        Logger.info("sub", "подписка добавлена: серверов ${sub.servers.size}")
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
