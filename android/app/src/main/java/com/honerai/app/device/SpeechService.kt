package com.honerai.app.device

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

// ЗАГОТОВКА (модуль «Устройство»): голосовой ввод и озвучка. Подписи сохраняются.

class SpeechService(context: Context) {
    val isRecording: StateFlow<Boolean> = MutableStateFlow(false)
    /** Распознанный текст по мере речи. */
    val transcript: StateFlow<String> = MutableStateFlow("")
    val isSpeaking: StateFlow<Boolean> = MutableStateFlow(false)
    val errorMessage: StateFlow<String?> = MutableStateFlow(null)
    /** Начать запись (язык: "ru-RU", "en-US"). Разрешение на микрофон запрашивает экран. */
    fun startRecording(language: String) {}
    /** Закончить запись и вернуть распознанный текст (ждёт финальный результат). */
    suspend fun stopRecording(): String = ""
    fun cancelRecording() {}
    /** Прочитать текст целиком (прерывает текущее чтение). gender: "male"/"female". */
    fun speak(text: String, gender: String, rate: Double, voiceId: String = "") {}
    /** Дочитать следующий кусок, не прерывая звучащий (чтение во время печати ответа). */
    fun append(text: String, gender: String, rate: Double, voiceId: String = "") {}
    fun stopSpeaking() {}
    fun release() {}
}
