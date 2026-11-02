package app.letnievpn.vpn

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import app.letnievpn.MainActivity
import app.letnievpn.R
import app.letnievpn.data.LinkParser
import app.letnievpn.data.Logger
import app.letnievpn.data.Repo
import app.letnievpn.data.Server
import app.letnievpn.data.VpnStatus
import app.letnievpn.data.XrayConfig
import go.Seq
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import libv2ray.CoreCallbackHandler
import libv2ray.CoreController
import libv2ray.Libv2ray
import java.net.Inet4Address
import java.net.InetAddress

/**
 * Настоящий VPN: создаёт TUN-интерфейс и отдаёт его дескриптор ядру xray (libv2ray),
 * которое само читает пакеты из туннеля и гонит их через выбранный сервер.
 */
class LetnieVpnService : VpnService() {

    private var tun: ParcelFileDescriptor? = null
    private var job: Job? = null
    /** Следит за трафиком и при сбое переключает сервер (см. startMonitor). */
    private var monitor: Job? = null

    /** Ядро одно, а запускать его могут из разных корутин: все запуски и остановки идут под замком. */
    private val coreLock = Any()

    private val callback = object : CoreCallbackHandler {
        override fun startup(): Long = 0
        override fun shutdown(): Long = 0
        override fun onEmitStatus(code: Long, msg: String?): Long = 0
    }

    private val controller: CoreController by lazy { Libv2ray.newCoreController(callback) }

    override fun onCreate() {
        super.onCreate()
        Repo.init(this)
        Seq.setContext(applicationContext)
        Libv2ray.initCoreEnv(filesDir.absolutePath, Repo.xudpKey())
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        enterForeground(null)
        Logger.info("vpn", "команда: ${intent?.action ?: "перезапуск системой"}, умное=${Repo.smart.value}, серверов=${Repo.allServers().size}")
        if (intent?.action == ACTION_STOP) {
            Repo.setDesired(false)
            shutdown()
            return START_NOT_STICKY
        }
        // ACTION_START, а также перезапуск системой (intent == null) — подключаемся
        Repo.setDesired(true)
        connect()
        return START_STICKY
    }

    override fun onRevoke() {
        // пользователь отозвал VPN в системных настройках
        Repo.setDesired(false)
        shutdown()
    }

    override fun onDestroy() {
        job?.cancel()
        monitor?.cancel()
        stopCore()
        closeTun()
        Repo.tunnelDelay.value = -1
        Repo.vpn.value = VpnStatus.OFF
        Repo.vpnServer.value = null
        super.onDestroy()
    }

    // ── подключение ────────────────────────────────────────────────

    private fun connect() {
        job?.cancel()
        monitor?.cancel()
        Repo.vpn.value = VpnStatus.CONNECTING
        Repo.connError.value = ""
        job = Repo.scope.launch {
            try {
                val order = candidates()
                if (order.isEmpty()) {
                    fail("нет серверов — добавьте подписку")
                    return@launch
                }
                Logger.info("vpn", "кандидаты: " + order.take(4).joinToString { "${it.name} ${Repo.pings.value[it.id]?.takeIf { p -> p > 0 } ?: "—"} мс" })
                ensureTun()
                val smart = Repo.smart.value
                // Ручной режим — только выбранный сервер. Умный — до четырёх самых быстрых,
                // и «Подключено» показываем только когда через сервер реально пошёл трафик.
                val tries = if (smart) order.take(4) else order.take(1)
                var connected: Server? = null
                var firstStarted: Server? = null
                var err = ""
                for (s in tries) {
                    val e = startCore(s)
                    ensureActive()
                    if (e != null) {
                        Logger.info("vpn", "${s.name}: не запустился — $e")
                        err = e
                        continue
                    }
                    if (firstStarted == null) firstStarted = s
                    if (!smart) {
                        connected = s
                        break
                    }
                    delay(400) // даём туннелю подняться
                    val ms = measure()
                    ensureActive()
                    if (ms >= 0) {
                        Logger.info("vpn", "${s.name}: трафик идёт, ${ms} мс")
                        Repo.tunnelDelay.value = ms
                        connected = s
                        break
                    }
                    Logger.info("vpn", "${s.name}: трафик не идёт, пробую следующий")
                    err = "через ${s.name} нет трафика"
                }
                if (connected == null && firstStarted != null) {
                    // Проверочные адреса могли быть просто недоступны: остаёмся на самом быстром,
                    // а монитор продолжит проверять и при необходимости переключит.
                    Logger.info("vpn", "ни один сервер не подтвердил трафик — остаюсь на ${firstStarted.name}")
                    if (startCore(firstStarted) == null) connected = firstStarted
                    ensureActive()
                }
                if (connected == null) {
                    fail(err.ifBlank { "не удалось запустить ни один сервер" })
                    return@launch
                }
                Logger.info("vpn", "подключено: ${connected.name}")
                Repo.vpnServer.value = connected.id
                Repo.vpn.value = VpnStatus.ON
                enterForeground(connected.name)
                Repo.refreshPings()
                startMonitor()
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                fail("${e.javaClass.simpleName}: ${scrub(e.message)}")
            }
        }
    }

    /**
     * Постоянный контроль, пока VPN включён. Раз в несколько секунд через туннель идёт настоящий запрос.
     * В умном режиме два сбоя подряд запускают восстановление (см. recover). В ручном сервер не меняется,
     * задержка только показывается на экране.
     */
    private fun startMonitor() {
        monitor?.cancel()
        monitor = Repo.scope.launch {
            var bad = 0
            delay(2000)
            while (isActive) {
                val smart = Repo.smart.value
                if (smart || Repo.uiVisible) {
                    val ms = measure()
                    if (ms >= 0) {
                        bad = 0
                        Repo.tunnelDelay.value = ms
                    } else {
                        bad++
                        Repo.tunnelDelay.value = -1
                        Logger.info("vpn", "проверка трафика: нет ответа ($bad)")
                        if (smart && bad >= 2) {
                            bad = 0
                            recover()
                        }
                    }
                }
                delay(if (bad > 0) 1500L else if (Repo.uiVisible) 2500L else 15000L)
            }
        }
    }

    /**
     * Трафик пропал. Сначала перезапускаем ядро на том же сервере (после смены Wi-Fi/LTE этого достаточно),
     * если не помогло — перемеряем пинги и по очереди пробуем самые быстрые из остальных.
     */
    private suspend fun recover() {
        val curId = Repo.vpnServer.value
        val cur = Repo.allServers().firstOrNull { it.id == curId }
        if (cur != null) {
            Logger.info("vpn", "трафика нет — перезапускаю ядро на ${cur.name}")
            if (startCore(cur) == null) {
                currentCoroutineContext().ensureActive()
                delay(1500)
                val ms = measure()
                if (ms >= 0) {
                    Logger.info("vpn", "после перезапуска трафик идёт, $ms мс")
                    Repo.tunnelDelay.value = ms
                    return
                }
            }
        }
        withTimeoutOrNull(6000) { Repo.pingAll(1500, quiet = true) }
        val order = ranked().filter { it.id != curId }.take(4)
        Logger.info("vpn", "ищу другой сервер: " + order.joinToString { "${it.name} ${Repo.pings.value[it.id]?.takeIf { p -> p > 0 } ?: "—"} мс" })
        for (s in order) {
            currentCoroutineContext().ensureActive()
            if (startCore(s) != null) continue
            currentCoroutineContext().ensureActive()
            Repo.vpnServer.value = s.id
            enterForeground(s.name)
            delay(600)
            val ms = measure()
            if (ms >= 0) {
                Logger.info("vpn", "переключено на ${s.name}, $ms мс")
                Repo.tunnelDelay.value = ms
                return
            }
            Logger.info("vpn", "${s.name}: трафик не идёт")
        }
        // Ничего не помогло (например, пропала сеть). Пауза, чтобы не дёргать ядро без конца.
        Logger.error("vpn", "ни один сервер не отвечает, повторю через 30 с")
        delay(30000)
    }

    /**
     * Серверы по возрастанию пинга. Если есть хотя бы один отвечающий — недоступные не берём,
     * иначе время уйдёт на заведомо мёртвые.
     */
    private fun ranked(): List<Server> {
        val pg = Repo.pings.value
        val all = Repo.allServers()
        val alive = all.filter { (pg[it.id] ?: -1) > 0 }
        return (alive.ifEmpty { all }).sortedBy { pg[it.id]?.takeIf { p -> p > 0 } ?: Int.MAX_VALUE }
    }

    /** Один настоящий запрос через туннель. Возвращает задержку в мс или -1, если ответа нет. */
    private suspend fun measure(): Int {
        for (url in CHECK_URLS) {
            val test = Repo.scope.async {
                try {
                    if (controller.isRunning) controller.measureDelay(url).toInt() else -1
                } catch (e: Exception) {
                    -1
                }
            }
            val ms = withTimeoutOrNull(5000) { test.await() } ?: -1
            if (ms >= 0) return ms.coerceAtLeast(1)
        }
        return -1
    }

    private fun fail(reason: String) {
        Logger.error("vpn", "не подключилось: $reason")
        Repo.connError.value = reason
        shutdown()
    }

    private fun scrub(m: String?): String =
        (m ?: "").replace(Regex("https?://\\S+"), "<url>").replace('\n', ' ').take(140)

    /** Упорядоченный список серверов: сначала выбранный (или самый быстрый), дальше по пингу. */
    private suspend fun candidates(): List<Server> {
        val all = Repo.allServers()
        if (all.isEmpty()) return emptyList()
        if (Repo.smart.value) {
            // пинги свежее трёх секунд берём как есть, иначе перемеряем
            val age = android.os.SystemClock.elapsedRealtime() - Repo.lastPingAt
            if (age > 3000) withTimeoutOrNull(6000) { Repo.pingAll(1500, quiet = true) }
            return ranked()
        }
        val chosen = Repo.current() ?: return ranked()
        return listOf(chosen) + ranked().filter { it.id != chosen.id }
    }

    /** Запускает ядро с сервером. null — успех, иначе причина неудачи. */
    private fun startCore(server: Server): String? {
        val proxy = LinkParser.parse(server.link) ?: return "ссылка сервера не разобралась"
        val ip = try {
            resolve(proxy.host)
        } catch (e: Exception) {
            return "не удалось определить адрес сервера (${e.javaClass.simpleName})"
        }
        return try {
            val config = XrayConfig.build(proxy, ip, Logger.xrayPath)
            Logger.info("core", "запуск: ${proxy.protocol}, транспорт ${proxy.q["type"] ?: "tcp"}, защита ${proxy.q["security"] ?: "нет"}, порт ${proxy.port}")
            Logger.info("core", "конфиг: ${Logger.redact(config)}")
            synchronized(coreLock) {
                stopCore()
                Repo.tunnelDelay.value = -1
                controller.startLoop(config, tun!!.fd)
                if (controller.isRunning) null else "ядро не запустилось"
            }
        } catch (e: Exception) {
            "ядро: ${scrub(e.message).ifBlank { e.javaClass.simpleName }}"
        }
    }

    private fun stopCore() {
        synchronized(coreLock) {
            try {
                if (controller.isRunning) controller.stopLoop()
            } catch (e: Exception) {
                // уже остановлено
            }
        }
    }

    private fun resolve(host: String): String {
        if (XrayConfig.isIp(host)) return host
        val all = InetAddress.getAllByName(host)
        return (all.firstOrNull { it is Inet4Address } ?: all.first()).hostAddress!!
    }

    private fun ensureTun() {
        if (tun != null) return
        val b = Builder()
            .setSession(getString(R.string.app_name))
            .setMtu(1500)
            .addAddress("10.10.14.1", 30)
            .addRoute("0.0.0.0", 0)
            .addDnsServer("1.1.1.1")
            .addDnsServer("8.8.8.8")
        // Сам клиент ходит к серверу мимо туннеля, иначе соединение с сервером зациклится
        try {
            b.addDisallowedApplication(packageName)
        } catch (e: Exception) {
            // пакет всегда существует
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) b.setMetered(false)
        tun = b.establish() ?: error("не удалось создать VPN-интерфейс")
    }

    private fun closeTun() {
        try {
            tun?.close()
        } catch (e: Exception) {
            // уже закрыт
        }
        tun = null
    }

    private fun shutdown() {
        Logger.info("vpn", "отключение")
        job?.cancel()
        monitor?.cancel()
        Repo.scope.launch {
            stopCore()
            closeTun()
            Repo.tunnelDelay.value = -1
            Repo.vpn.value = VpnStatus.OFF
            Repo.vpnServer.value = null
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    // ── уведомление ────────────────────────────────────────────────

    private fun enterForeground(serverName: String?) {
        val nm = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL, getString(R.string.channel_vpn), NotificationManager.IMPORTANCE_LOW)
            )
        }
        val n = notification(serverName)
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIF_ID, n)
        }
    }

    private fun notification(serverName: String?): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val stop = PendingIntent.getService(
            this, 1, Intent(this, LetnieVpnService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(serverName ?: getString(R.string.status_connecting))
            .setOngoing(true)
            .setShowWhen(false)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(open)
            .addAction(0, getString(R.string.action_stop), stop)
            .build()
    }

    companion object {
        const val ACTION_START = "app.letnievpn.START"
        const val ACTION_STOP = "app.letnievpn.STOP"

        /** Проверка трафика: если не ответил первый адрес, пробуем второй (один из них мог быть недоступен). */
        private val CHECK_URLS = listOf(
            "https://www.gstatic.com/generate_204",
            "https://cp.cloudflare.com/generate_204",
        )
        private const val CHANNEL = "vpn"
        private const val NOTIF_ID = 1

        fun start(context: Context) {
            ContextCompat.startForegroundService(
                context, Intent(context, LetnieVpnService::class.java).setAction(ACTION_START)
            )
        }

        fun stop(context: Context) {
            // доставляем команду уже работающему сервису, новый не поднимаем
            context.startService(Intent(context, LetnieVpnService::class.java).setAction(ACTION_STOP))
        }
    }
}
