package com.honerai.app.extras.device

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.ActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.honerai.app.extras.lock.AppLock
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

/** Переключатель «Доступ ИИ к данным и состоянию телефона» (по умолчанию выключен). */
object DeviceAccess {
    private const val PREFS = "honer.extras.device"
    private const val KEY = "aiPhoneAccess"
    private val _enabled = MutableStateFlow(false)
    val enabled: StateFlow<Boolean> = _enabled.asStateFlow()
    @Volatile private var loaded = false

    fun init(context: Context) {
        if (loaded) return
        loaded = true
        _enabled.value = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY, false)
    }

    fun setEnabled(context: Context, value: Boolean) {
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY, value).apply()
        _enabled.value = value
    }
}

/**
 * Мост между инструментами (работают в корутине чата) и окном приложения:
 * системные запросы (запись экрана, разрешения) и запуск других приложений возможны
 * только из видимого Activity — их выполняет [DeviceToolsHostEffect].
 */
object DeviceHost {
    @Volatile var appContext: Context? = null
        private set

    private val _foreground = MutableStateFlow(false)
    /** Окно Honer AI видно пользователю. */
    val foreground: StateFlow<Boolean> = _foreground.asStateFlow()

    internal sealed class Request {
        class Projection(val result: CompletableDeferred<ActivityResult?>) : Request()
        class Permissions(val permissions: Array<String>, val result: CompletableDeferred<Map<String, Boolean>>) : Request()
        class Launch(val intent: Intent, val result: CompletableDeferred<Boolean>) : Request()
        class MoveToBack(val result: CompletableDeferred<Boolean>) : Request()
    }

    internal val requests = Channel<Request>(Channel.UNLIMITED)

    fun init(context: Context) {
        if (appContext == null) appContext = context.applicationContext
        DeviceAccess.init(context)
    }

    internal fun setForeground(value: Boolean) { _foreground.value = value }

    /**
     * Ждём, пока пользователь откроет приложение (ответ мог дописываться в фоне) и снимет блокировку:
     * системные окна не должны появляться поверх экрана блокировки.
     */
    suspend fun awaitForeground(timeoutMs: Long = 60_000): Boolean {
        if (_foreground.value && !AppLock.locked.value) return true
        return withTimeoutOrNull(timeoutMs) { combine(_foreground, AppLock.locked) { visible, locked -> visible && !locked }.first { it } } == true
    }

    private suspend fun <T> send(timeoutMs: Long, make: (CompletableDeferred<T>) -> Request): T? {
        if (!awaitForeground()) return null
        val deferred = CompletableDeferred<T>()
        requests.trySend(make(deferred))
        return try {
            withTimeoutOrNull(timeoutMs) { deferred.await() }
        } finally {
            // Окно не ответило — запрос больше не нужен, хост его пропустит.
            if (!deferred.isCompleted) deferred.cancel()
        }
    }

    /** Системное окно «Начать запись/трансляцию?». null — отказ или приложение не на экране. */
    // Системные окна (а на Android 14 — выбор приложения для показа) могут увести Honer AI в фон:
    // такая «поездка» не должна включать блокировку приложения.
    suspend fun requestProjection(): ActivityResult? =
        send<ActivityResult?>(180_000) { AppLock.allowTrip(5 * 60_000L); Request.Projection(it) }
            ?.takeIf { it.resultCode == Activity.RESULT_OK && it.data != null }

    suspend fun requestPermissions(permissions: Array<String>): Map<String, Boolean> =
        send<Map<String, Boolean>>(120_000) { AppLock.allowTrip(3 * 60_000L); Request.Permissions(permissions, it) }
            ?: permissions.associateWith { false }

    /** Открыть другое приложение (часы, настройки). Возвращение не включает блокировку приложения. */
    suspend fun launch(intent: Intent, tripMillis: Long = 10 * 60_000L): Boolean =
        send<Boolean>(15_000) { AppLock.allowTrip(tripMillis); Request.Launch(intent, it) } == true

    /** Свернуть Honer AI, чтобы пользователь открыл нужный экран. */
    suspend fun moveToBack(): Boolean = send<Boolean>(5_000) { Request.MoveToBack(it) } == true
}

/** Хост в корне интерфейса: регистрирует запросы Activity и выполняет просьбы инструментов по одной. */
@Composable
fun DeviceToolsHostEffect(activity: FragmentActivity) {
    val pendingProjection = remember { arrayOfNulls<CompletableDeferred<ActivityResult?>>(1) }
    val pendingPermissions = remember { arrayOfNulls<CompletableDeferred<Map<String, Boolean>>>(1) }
    val projectionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        pendingProjection[0]?.complete(result)
        pendingProjection[0] = null
    }
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        pendingPermissions[0]?.complete(result)
        pendingPermissions[0] = null
    }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { owner, _ ->
            DeviceHost.setForeground(owner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED))
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            DeviceHost.setForeground(false)
            pendingProjection[0]?.complete(null)
            pendingPermissions[0]?.complete(emptyMap())
        }
    }

    LaunchedEffect(activity) {
        for (request in DeviceHost.requests) {
            when (request) {
                is DeviceHost.Request.Projection -> {
                    if (request.result.isCancelled) continue
                    val manager = activity.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
                    pendingProjection[0] = request.result
                    val launched = runCatching { projectionLauncher.launch(manager.createScreenCaptureIntent()) }.isSuccess
                    if (!launched) request.result.complete(null)
                    runCatching { request.result.await() }
                }
                is DeviceHost.Request.Permissions -> {
                    if (request.result.isCancelled) continue
                    pendingPermissions[0] = request.result
                    val launched = runCatching { permissionLauncher.launch(request.permissions) }.isSuccess
                    if (!launched) request.result.complete(emptyMap())
                    runCatching { request.result.await() }
                }
                is DeviceHost.Request.Launch -> {
                    if (request.result.isCancelled) continue
                    request.result.complete(runCatching { activity.startActivity(request.intent) }.isSuccess)
                }
                is DeviceHost.Request.MoveToBack -> {
                    if (request.result.isCancelled) continue
                    request.result.complete(runCatching { activity.moveTaskToBack(true) }.getOrDefault(false))
                }
            }
        }
    }
}
