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
    private var watch: Job? = null

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
        watch?.cancel()
        stopCore()
        closeTun()
        Repo.vpn.value = VpnStatus.OFF
        Repo.vpnServer.value = null
        super.onDestroy()
    }

    // ── подключение ────────────────────────────────────────────────

    private fun connect() {
        job?.cancel()
        watch?.cancel()
        Repo.vpn.value = VpnStatus.CONNECTING
        Repo.connError.value = ""
        job = Repo.scope.launch {
            try {
                val order = candidates()
                if (order.isEmpty()) {
                    fail("нет серверов — добавьте подписку")
                    return@launch
                }
                Logger.info("vpn", "кандидаты: " + order.take(3).joinToString { "${it.name} ${Repo.pings.value[it.id] ?: "?"} мс" })
                ensureTun()
                // В ручном режиме — только выбранный сервер, в умном — до трёх самых быстрых
                val tries = if (Repo.smart.value) order.take(3) else order.take(1)
                var connected: Server? = null
                var err = ""
                for (s in tries) {
                    val e = startCore(s)
                    if (e == null) {
                        connected = s
                        break
                    }
                    err = e
                }
                if (connected == null) {
                    fail(err)
                    return@launch
                }
                // Сразу показываем «Подключено»; работоспособность проверяем фоном (см. watchdog)
                Logger.info("vpn", "подключено: ${connected.name}")
                Repo.vpnServer.value = connected.id
                Repo.vpn.value = VpnStatus.ON
                enterForeground(connected.name)
                Repo.refreshPings()
                if (Repo.smart.value) watchdog(order, connected)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                fail("${e.javaClass.simpleName}: ${scrub(e.message)}")
            }
        }
    }

    /**
     * Умное подключение: если через выбранный сервер трафик не идёт, переключаемся
     * на следующий по пингу. Проверка идёт фоном, чтобы не задерживать подключение.
     */
    private fun watchdog(order: List<Server>, first: Server) {
        watch?.cancel()
        watch = Repo.scope.launch {
            val others = order.filter { it.id != first.id }.take(2)
            kotlinx.coroutines.delay(2500)
            if (verify()) return@launch
            for (next in others) {
                if (startCore(next) != null) continue
                Repo.vpnServer.value = next.id
                enterForeground(next.name)
                kotlinx.coroutines.delay(2500)
                if (verify()) return@launch
            }
            // ни один не ответил на проверку — остаёмся на последнем: проверочный адрес мог быть просто недоступен
        }
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
            // перед выбором быстрейшего свежо измеряем пинги
            withTimeoutOrNull(7000) { Repo.pingAll() }
        }
        val pg = Repo.pings.value
        val sorted = all.sortedBy { s -> pg[s.id]?.takeIf { it > 0 } ?: Int.MAX_VALUE }
        if (Repo.smart.value) return sorted
        val chosen = Repo.current() ?: return sorted
        return listOf(chosen) + sorted.filter { it.id != chosen.id }
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
            stopCore()
            controller.startLoop(config, tun!!.fd)
            if (controller.isRunning) null else "ядро не запустилось"
        } catch (e: Exception) {
            "ядро: ${scrub(e.message).ifBlank { e.javaClass.simpleName }}"
        }
    }

    private fun stopCore() {
        try {
            if (controller.isRunning) controller.stopLoop()
        } catch (e: Exception) {
            // уже остановлено
        }
    }

    /** Проверяем, что через сервер реально ходит трафик (не дольше 8 секунд). */
    private suspend fun verify(): Boolean {
        val test = Repo.scope.async {
            try {
                controller.measureDelay("https://www.gstatic.com/generate_204") >= 0
            } catch (e: Exception) {
                false
            }
        }
        val ok = withTimeoutOrNull(8000) { test.await() } ?: false
        Logger.info("core", "проверка трафика: ${if (ok) "успешно" else "нет ответа"}")
        return ok
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
        watch?.cancel()
        Repo.scope.launch {
            stopCore()
            closeTun()
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
