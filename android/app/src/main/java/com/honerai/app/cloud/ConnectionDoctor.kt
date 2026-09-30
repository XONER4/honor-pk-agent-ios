package com.honerai.app.cloud

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities

// «Врач связи»: когда сервер недоступен, определяет ПОЧЕМУ (нет интернета / оператор блокирует / сервер лёг)
// и что делать. Приложение показывает это пользователю, а нейросеть получает ту же причину в контексте,
// чтобы объяснить и помочь, если её спросят (когда связь есть хотя бы по запасному адресу).

enum class ConnReason { OK, NO_INTERNET, LIKELY_BLOCKED, RELAY_LIMIT, SERVER_DOWN }

data class ConnDiagnostic(
    val reason: ConnReason,
    val title: String,
    val detail: String,
    val fixes: List<String>,
) {
    /** Короткая строка для системного контекста нейросети. */
    fun aiNote(): String = when (reason) {
        ConnReason.OK -> ""
        ConnReason.NO_INTERNET -> "Связь: у пользователя нет интернета (сеть не пропускает данные)."
        ConnReason.LIKELY_BLOCKED -> "Связь: интернет есть, но сервер недоступен по мобильной сети — вероятно, оператор (МТС) блокирует прямой доступ. Решение: включить VPN или Wi-Fi; приложение уже пробует запасные адреса."
        ConnReason.RELAY_LIMIT -> "Связь: исчерпан суточный лимит бесплатного релея (адрес, которым приложение обходит блокировку оператора). Поэтому пользователи без VPN сейчас не могут подключиться. Причина — дневной лимит бесплатного релея; он сбрасывается в начале суток (UTC). Решения: включить VPN (тогда работает прямой адрес), подождать сброса лимита, или администратору — увеличить лимит/подключить платный план релея."
        ConnReason.SERVER_DOWN -> "Связь: интернет есть, но сервер временно не отвечает."
    }
}

object ConnectionDoctor {
    data class Net(val validated: Boolean, val cellular: Boolean, val wifi: Boolean, val vpn: Boolean)

    fun net(context: Context): Net {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        val caps = cm?.activeNetwork?.let { cm.getNetworkCapabilities(it) }
        return Net(
            validated = caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true,
            cellular = caps?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) == true,
            wifi = caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true,
            vpn = caps?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true,
        )
    }

    /**
     * Диагноз, когда ни один адрес сервера не ответил.
     * [relayLimited] — при проверке заметили, что у релея исчерпан суточный лимит (код 429/маркеры):
     * это ТОЧНАЯ причина, поэтому она важнее общего «похоже, заблокировано».
     */
    fun diagnose(context: Context, relayLimited: Boolean = false): ConnDiagnostic {
        val nw = net(context)
        if (!nw.validated) return ConnDiagnostic(
            ConnReason.NO_INTERNET, "Нет интернета",
            "Телефон не в сети или сеть не пропускает данные.",
            listOf("Проверьте Wi-Fi или мобильный интернет", "Выключите режим полёта"),
        )
        // Точно знаем причину: у бесплатного релея кончился дневной лимит запросов (как раньше у Deno).
        if (relayLimited) return ConnDiagnostic(
            ConnReason.RELAY_LIMIT, "Дневной лимит связи исчерпан",
            "Бесплатный запасной адрес (релей), которым приложение обходит блокировку оператора, исчерпал суточный лимит запросов. Без VPN подключиться сейчас не получится.",
            listOf("Включите VPN — тогда сработает прямой адрес сервера", "Или подождите: лимит сбрасывается в начале суток", "Мы уже работаем над увеличением лимита"),
        )
        // Интернет есть, а сервер недоступен. Без VPN — почти всегда это блокировка провайдера/оператора
        // (проверено: у оператора и даже на части домашнего интернета прямой доступ к серверу закрыт).
        if (!nw.vpn) return ConnDiagnostic(
            ConnReason.LIKELY_BLOCKED, "Похоже, доступ к серверу заблокирован",
            "Интернет работает, но сервер Honer AI недоступен. Скорее всего, ваш оператор или провайдер (например, МТС) блокирует прямой доступ к серверу без VPN.",
            listOf("Включите VPN — с ним всё заработает", "Приложение уже пробует запасные адреса", "Если есть другой Wi-Fi — попробуйте его"),
        )
        // VPN включён, но сервер всё равно не отвечает — вероятно, обслуживание/сбой сервера.
        return ConnDiagnostic(
            ConnReason.SERVER_DOWN, "Сервер временно недоступен",
            "Интернет и VPN есть, но сервер не отвечает — возможно, идёт обслуживание.",
            listOf("Повторите через минуту", "Приложение переключится на рабочий адрес автоматически"),
        )
    }
}
