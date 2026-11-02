package app.letnievpn.data

import android.content.Context
import java.util.UUID

/** Простое хранилище настроек и подписок (приватные SharedPreferences приложения). */
class Store(context: Context) {
    private val sp = context.applicationContext.getSharedPreferences("letnie", Context.MODE_PRIVATE)

    var subsJson: String
        get() = sp.getString("subs", "[]") ?: "[]"
        set(v) = sp.edit().putString("subs", v).apply()

    /** Умное подключение: само выбирает самый быстрый сервер и поднимается после перезагрузки. */
    var smart: Boolean
        get() = sp.getBoolean("smart", true)
        set(v) = sp.edit().putBoolean("smart", v).apply()

    var selected: String?
        get() = sp.getString("selected", null)
        set(v) = sp.edit().putString("selected", v).apply()

    /** Пользователь включил VPN и не выключал вручную. Нужно для автозапуска после перезагрузки. */
    var desired: Boolean
        get() = sp.getBoolean("desired", false)
        set(v) = sp.edit().putBoolean("desired", v).apply()

    /** Постоянный идентификатор устройства (HWID): панели подписок используют его для лимита устройств. */
    val hwid: String
        get() {
            val k = sp.getString("hwid", null)
            if (k != null) return k
            val n = UUID.randomUUID().toString().replace("-", "").take(16)
            sp.edit().putString("hwid", n).apply()
            return n
        }

    val xudpKey: String
        get() {
            val k = sp.getString("xudp", null)
            if (k != null) return k
            val n = UUID.randomUUID().toString()
            sp.edit().putString("xudp", n).apply()
            return n
        }
}
