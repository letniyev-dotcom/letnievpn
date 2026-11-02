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
import app.letnievpn.data.Repo
import app.letnievpn.data.Server
import app.letnievpn.data.VpnStatus
import app.letnievpn.data.XrayConfig
import go.Seq
import kotlinx.coroutines.Job
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
        stopCore()
        closeTun()
        Repo.vpn.value = VpnStatus.OFF
        Repo.vpnServer.value = null
        super.onDestroy()
    }

    // ── подключение ────────────────────────────────────────────────

    private fun connect() {
        job?.cancel()
        Repo.vpn.value = VpnStatus.CONNECTING
        job = Repo.scope.launch {
            try {
                val order = candidates()
                if (order.isEmpty()) {
                    shutdown()
                    return@launch
                }
                ensureTun()
                val smart = Repo.smart.value
                // В ручном режиме — только выбранный сервер, в умном — до трёх самых быстрых
                val tries = if (smart) order.take(3) else order.take(1)
                var connected: Server? = null
                for (s in tries) {
                    if (!startCore(s)) continue
                    if (!smart || verify()) {
                        connected = s
                        break
                    }
                    stopCore()
                }
                if (connected == null && smart) {
                    // проверка не прошла нигде — поднимаем самый быстрый без проверки
                    if (startCore(order.first())) connected = order.first()
                }
                if (connected == null) {
                    shutdown()
                    return@launch
                }
                Repo.vpnServer.value = connected.id
                Repo.vpn.value = VpnStatus.ON
                enterForeground(connected.name)
                // фоном обновим пинги, чтобы список был актуальным
                Repo.refreshPings()
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                shutdown()
            }
        }
    }

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

    private fun startCore(server: Server): Boolean = try {
        val proxy = LinkParser.parse(server.link) ?: return false
        val ip = resolve(proxy.host)
        val config = XrayConfig.build(proxy, ip)
        stopCore()
        controller.startLoop(config, tun!!.fd)
        controller.isRunning
    } catch (e: Exception) {
        false
    }

    private fun stopCore() {
        try {
            if (controller.isRunning) controller.stopLoop()
        } catch (e: Exception) {
            // уже остановлено
        }
    }

    /** Проверяем, что через сервер реально ходит трафик. */
    private fun verify(): Boolean = try {
        controller.measureDelay("https://www.gstatic.com/generate_204") >= 0
    } catch (e: Exception) {
        false
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
        tun = b.establish() ?: error("VPN-интерфейс не создан")
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
        job?.cancel()
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
