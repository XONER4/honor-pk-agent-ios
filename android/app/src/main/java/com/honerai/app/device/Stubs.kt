package com.honerai.app.device

import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.IBinder

// Заготовки служб из манифеста. Их полная реализация — в модуле устройства
// (фоновая генерация, автообновление). Файл будет заменён.

/** Дописывает ответ, пока приложение свёрнуто (служба переднего плана). */
class GenerationService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        /** Ответ идёт, приложение свёрнуто — держим процесс живым с тихим уведомлением. */
        fun start(context: Context, title: String) {}
        fun stop(context: Context) {}
    }
}

/** Итог установки обновления от PackageInstaller. */
class UpdateInstallReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {}
}

/** Приложение обновилось — показать уведомление и вернуть пользователя в чат. */
class PackageReplacedReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {}
}

/** После перезагрузки телефона — снова запланировать проверку обновлений. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {}
}

/** Периодическая проверка обновлений. */
object UpdateScheduler {
    fun schedule(context: Context, enabled: Boolean) {}
}
