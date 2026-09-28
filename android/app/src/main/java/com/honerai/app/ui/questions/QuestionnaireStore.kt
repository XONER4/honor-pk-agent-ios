package com.honerai.app.ui.questions

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

/**
 * Ответы на карточки вопросов (порт QuestionnaireStore). Живут отдельно от экрана:
 * карточка пересоздаётся при прокрутке, а выбор и таймер должны сохраниться.
 * Ключ — «идентификатор сообщения#номер блока». Хранятся в SharedPreferences (JSON).
 */
object QuestionnaireStore {
    @Serializable
    data class Entry(
        /** null — вопрос ещё впереди, "" — ответа не было (время вышло или пропущен). */
        val answers: List<String?>,
        val current: Int = 0,
        val finished: Boolean = false,
        val sent: Boolean = false,
        /** Когда истекает время текущего вопроса (мс с 1970). */
        val deadline: Long? = null,
        /** Сколько секунд оставалось, когда таймер поставили на паузу. */
        val pausedRemaining: Double? = null,
        val updatedAt: Long = System.currentTimeMillis(),
    )

    private const val PREFS = "honer.questionnaires"
    private const val KEY = "honer.questionnaires.v1"
    private const val KEEP = 300

    private val json = Json { ignoreUnknownKeys = true }
    private val serializer = MapSerializer(String.serializer(), Entry.serializer())
    private val _entries = MutableStateFlow<Map<String, Entry>>(emptyMap())
    val entries: StateFlow<Map<String, Entry>> = _entries.asStateFlow()

    /** Общая область для переходов между вопросами: переход доживает до конца, даже если карточка ушла с экрана. */
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private var prefs: SharedPreferences? = null
    private var saveJob: Job? = null

    /** Загрузка сохранённых ответов (один раз). */
    fun init(context: Context) {
        if (prefs != null) return
        val preferences = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs = preferences
        val saved = preferences.getString(KEY, null) ?: return
        runCatching { json.decodeFromString(serializer, saved) }.getOrNull()?.let { loaded ->
            _entries.value = loaded + _entries.value
        }
    }

    fun entry(key: String, count: Int): Entry {
        val value = _entries.value[key] ?: Entry(answers = List(count) { null })
        return if (value.answers.size < count) value.copy(answers = value.answers + List(count - value.answers.size) { null }) else value
    }

    fun update(key: String, count: Int, change: (Entry) -> Entry) {
        val value = change(entry(key, count)).copy(updatedAt = System.currentTimeMillis())
        _entries.value = _entries.value + (key to value)
        scheduleSave()
    }

    private fun scheduleSave() {
        val preferences = prefs ?: return
        saveJob?.cancel()
        saveJob = scope.launch {
            delay(400)
            var current = _entries.value
            // Храним только свежие карточки: старые ответы уже есть в самом чате.
            if (current.size > KEEP) {
                current = current.entries.sortedByDescending { it.value.updatedAt }.take(KEEP).associate { it.key to it.value }
                _entries.value = current
            }
            val snapshot = current
            withContext(Dispatchers.IO) {
                preferences.edit().putString(KEY, json.encodeToString(serializer, snapshot)).apply()
            }
        }
    }
}
