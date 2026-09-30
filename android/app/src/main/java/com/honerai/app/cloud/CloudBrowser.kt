package com.honerai.app.cloud

import com.honerai.app.core.agent.ActionOutcome
import com.honerai.app.core.agent.ScreenController
import com.honerai.app.core.agent.ScreenNode
import com.honerai.app.core.agent.ScreenSnapshot
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

// Облачный браузер на стороне приложения. Сервер держит сеанс сервиса (Wildberries, Ozon, ВК…),
// обходит анти-бот и хранит вход. Пользователь логинится сам через окно в чате (кадр + тапы/ввод),
// а нейросеть действует в этом же сеансе через [CloudScreenController] — тем же циклом агента.

@Serializable
data class CloudShot(
    val sessionId: String = "",
    val service: String = "",
    val title: String = "",
    val url: String = "",
    val width: Int = 412,
    val height: Int = 915,
    val image: String? = null,
    val blocked: Boolean = false,
    val loggedInHint: Boolean = false,
    val error: String? = null,
)

@Serializable
data class CloudControl(val label: String = "", val x: Int = 0, val y: Int = 0, val tag: String = "")

@Serializable
data class CloudPage(val url: String = "", val text: String = "", val controls: List<CloudControl> = emptyList())

/** Человеческие названия сервисов для подсказок. */
object CloudServices {
    val supported = mapOf(
        "wildberries" to "Wildberries",
        "ozon" to "Ozon",
        "yandex_market" to "Яндекс Маркет",
        "vk" to "ВКонтакте",
        "yandex_mail" to "Яндекс Почта",
        "mts" to "МТС",
    )
    fun label(id: String): String = supported[id] ?: id
    fun isSupported(id: String): Boolean = supported.containsKey(id)
}

/**
 * «Глаза и руки» агента, но вместо экрана телефона — облачный браузер на сервере.
 * Реализует [ScreenController], поэтому подходит существующему [com.honerai.app.core.agent.AgentSession].
 */
class CloudScreenController(
    private val api: CloudApi,
    private val service: String,
    private val sessionId: String,
) : ScreenController {
    @Volatile
    private var controls: List<CloudControl> = emptyList()

    override suspend fun snapshot(): ScreenSnapshot? = withContext(Dispatchers.IO) {
        val page = runCatching { api.cloudRead(sessionId) }.getOrNull() ?: return@withContext null
        controls = page.controls
        val nodes = page.controls.mapIndexed { i, c ->
            ScreenNode(
                index = i, text = c.label, contentDescription = "", className = c.tag,
                clickable = c.tag != "input" && c.tag != "textarea",
                editable = c.tag == "input" || c.tag == "textarea",
                scrollable = false, checkable = false, isPassword = false,
                bounds = "${c.x},${c.y},${c.x},${c.y}",
            )
        }
        ScreenSnapshot(packageName = service, appLabel = CloudServices.label(service), nodes = nodes)
    }

    private suspend fun send(type: String, build: JsonObjectBuilder.() -> Unit = {}): ActionOutcome =
        withContext(Dispatchers.IO) {
            val action = buildJsonObject { put("sessionId", sessionId); put("type", type); build() }
            runCatching { api.cloudInput(action) }
                .fold({ ActionOutcome.Success }, { ActionOutcome.Failed(it.message ?: "ошибка облачного действия") })
        }

    override suspend fun tap(index: Int): ActionOutcome {
        val c = controls.getOrNull(index) ?: return ActionOutcome.NoTarget
        return send("tap") { put("x", c.x); put("y", c.y) }
    }

    override suspend fun tapByText(text: String): ActionOutcome {
        val q = text.trim().lowercase()
        val c = controls.firstOrNull { it.label.lowercase() == q }
            ?: controls.firstOrNull { it.label.lowercase().contains(q) } ?: return ActionOutcome.NoTarget
        return send("tap") { put("x", c.x); put("y", c.y) }
    }

    override suspend fun setText(index: Int, text: String): ActionOutcome {
        val c = controls.getOrNull(index) ?: return ActionOutcome.NoTarget
        // Логины/пароли в облаке вводит сам пользователь через окно входа — агент только обычные поля (поиск и т. п.).
        send("tap") { put("x", c.x); put("y", c.y) }
        return send("type") { put("text", text) }
    }

    override suspend fun scroll(direction: String): ActionOutcome =
        send("scroll") { put("dy", if (direction == "up") -500 else 500) }

    override suspend fun back(): ActionOutcome = send("back")
    override suspend fun home(): ActionOutcome = send("navigate")
    override suspend fun openApp(packageOrName: String): ActionOutcome = ActionOutcome.Success
}

/**
 * Состояние окна облачного браузера для интерфейса входа: текущий кадр + занятость.
 * Все сетевые вызовы — на IO. Пользователь тапает по картинке и печатает — это уходит в сеанс.
 */
class CloudBrowserStore(private val api: CloudApi) {
    private val _frame = MutableStateFlow<CloudShot?>(null)
    val frame: StateFlow<CloudShot?> = _frame.asStateFlow()

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    val sessionId: String? get() = _frame.value?.sessionId?.takeIf { it.isNotEmpty() }
    val service: String? get() = _frame.value?.service?.takeIf { it.isNotEmpty() }

    private suspend fun <T> run(block: () -> T): T? = withContext(Dispatchers.IO) {
        _busy.value = true
        try {
            _error.value = null
            block()
        } catch (e: Throwable) {
            _error.value = e.message ?: "Ошибка облачного браузера"
            null
        } finally {
            _busy.value = false
        }
    }

    suspend fun open(service: String) { run { api.cloudOpen(service) }?.let { _frame.value = it } }

    private suspend fun input(type: String, build: JsonObjectBuilder.() -> Unit = {}) {
        val sid = sessionId ?: return
        run {
            api.cloudInput(buildJsonObject { put("sessionId", sid); put("type", type); build() })
        }?.let { _frame.value = it }
    }

    suspend fun tap(x: Int, y: Int) = input("tap") { put("x", x); put("y", y) }
    suspend fun type(text: String) = input("type") { put("text", text) }
    suspend fun pressEnter() = input("key") { put("key", "Enter") }
    suspend fun scroll(dy: Int) = input("scroll") { put("dy", dy) }
    suspend fun back() = input("back")
    suspend fun refresh() { val sid = sessionId ?: return; run { api.cloudInput(buildJsonObject { put("sessionId", sid); put("type", "wait") }) }?.let { _frame.value = it } }

    suspend fun close() {
        val sid = sessionId ?: return
        run { api.cloudClose(sid) }
        _frame.value = null
    }

    /** Контроллер для передачи сеанса нейросети (после входа). */
    fun controller(): CloudScreenController? {
        val f = _frame.value ?: return null
        if (f.sessionId.isEmpty()) return null
        return CloudScreenController(api, f.service, f.sessionId)
    }
}
