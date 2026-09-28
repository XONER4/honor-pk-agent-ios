package com.honerai.app.device

import android.content.Context

// ЗАГОТОВКА (модуль «Настройки»): родительский контроль. Подписи сохраняются.

sealed class GuardVerdict {
    data object Allowed : GuardVerdict()
    data class Blocked(val reason: String) : GuardVerdict()
}

/** Правила и проверки родительского контроля (порт ParentalControl.swift). */
object ParentalControl {
    fun init(context: Context) {}
    val enabled: Boolean get() = false
    val canSearchWeb: Boolean get() = true
    val canOpenLinks: Boolean get() = true
    val canGenerateImages: Boolean get() = true
    val canPlayGames: Boolean get() = true
    val canUseContacts: Boolean get() = true
    val canUseLocation: Boolean get() = true
    val canCloneVoice: Boolean get() = true
    /** Причина блокировки чата сейчас (лимит времени, тихие часы) или null. */
    val blockReason: String? get() = null
    fun systemPromptBlock(): String = ""
    fun check(userText: String): GuardVerdict = GuardVerdict.Allowed
    fun filterOutput(text: String): String = text
    fun isUrlAllowed(url: String): Boolean = true
    fun isGameAllowed(raw: String): Boolean = true
}
