package com.honerai.app.device.agent

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.honerai.app.core.AppLauncher
import com.honerai.app.core.AppRequest
import com.honerai.app.core.agent.ActionOutcome
import com.honerai.app.core.agent.AgentController
import com.honerai.app.core.agent.RawNode
import com.honerai.app.core.agent.ScreenController
import com.honerai.app.core.agent.ScreenNode
import com.honerai.app.core.agent.ScreenSnapshot
import com.honerai.app.core.agent.SnapshotBuilder
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Служба специальных возможностей — «руки и глаза» агента. Регистрируется в [AgentController] как
 * [ScreenController]: читает содержимое активного окна (снимок), нажимает, вводит текст, прокручивает,
 * жмёт «Назад»/«Домой» и открывает приложения. Пароли не читает открытым текстом и не заполняет.
 */
class DeviceAgentService : AccessibilityService(), ScreenController {

    override fun onServiceConnected() {
        super.onServiceConnected()
        AgentController.register(this)
    }

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        AgentController.unregister(this)
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        AgentController.unregister(this)
        super.onDestroy()
    }

    // Событиями и прерываниями не пользуемся: агент опрашивает экран по требованию.
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}

    // ---- ScreenController ----

    override suspend fun snapshot(): ScreenSnapshot? = withContext(Dispatchers.Main) {
        val root = rootInActiveWindow ?: return@withContext null
        val pkg = root.packageName?.toString().orEmpty()
        val label = appLabel(pkg)
        SnapshotBuilder.build(pkg, label, NodeAdapter(root))
    }

    override suspend fun tap(index: Int): ActionOutcome = onMain {
        val node = nodeAt(index) ?: return@onMain ActionOutcome.NoTarget
        clickNode(node)
    }

    override suspend fun tapByText(text: String): ActionOutcome = onMain {
        val snapshot = SnapshotBuilder.build("", "", rootInActiveWindow?.let { NodeAdapter(it) })
        val node = snapshot.nodeByText(text) ?: return@onMain ActionOutcome.NoTarget
        val info = nodeAt(node.index) ?: return@onMain ActionOutcome.NoTarget
        clickNode(info)
    }

    override suspend fun setText(index: Int, text: String): ActionOutcome = onMain {
        val node = nodeAt(index) ?: return@onMain ActionOutcome.NoTarget
        // Жёсткий предохранитель: поле пароля не заполняем никогда.
        if (node.isPassword) return@onMain ActionOutcome.PasswordRefused
        val target = if (node.isEditable) node else findEditable(node) ?: node
        val args = Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text) }
        val ok = target.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
        if (ok) ActionOutcome.Success else ActionOutcome.Failed("не удалось ввести текст в поле")
    }

    override suspend fun scroll(direction: String): ActionOutcome = onMain {
        val root = rootInActiveWindow ?: return@onMain ActionOutcome.NotConnected
        // Сначала пробуем прокрутить подходящий узел его собственным действием, иначе — жестом по экрану.
        val scrollable = findScrollable(root)
        val forward = direction == "down" || direction == "right"
        if (scrollable != null) {
            val action = if (forward) AccessibilityNodeInfo.ACTION_SCROLL_FORWARD else AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
            if (scrollable.performAction(action)) return@onMain ActionOutcome.Success
        }
        swipe(direction)
    }

    override suspend fun back(): ActionOutcome = onMain {
        if (performGlobalAction(GLOBAL_ACTION_BACK)) ActionOutcome.Success else ActionOutcome.Failed("не удалось нажать «Назад»")
    }

    override suspend fun home(): ActionOutcome = onMain {
        if (performGlobalAction(GLOBAL_ACTION_HOME)) ActionOutcome.Success else ActionOutcome.Failed("не удалось перейти на главный экран")
    }

    override suspend fun openApp(packageOrName: String): ActionOutcome = withContext(Dispatchers.Main) {
        // Переиспользуем существующий лаунчер приложений (каталог + программы главного экрана).
        val request = AppRequest(app = packageOrName)
        val launch = AppLauncher.launchFor(request)
            ?: AppLauncher.findLauncherApp(AppLauncher.launcherApps(this@DeviceAgentService), packageOrName)
                ?.let { com.honerai.app.core.AppCatalog.launcherApp(it.second, it.first) }
            ?: return@withContext ActionOutcome.Failed("приложение «$packageOrName» не найдено")
        if (AppLauncher.open(this@DeviceAgentService, launch)) ActionOutcome.Success else ActionOutcome.Failed("не удалось открыть «$packageOrName»")
    }

    // ---- Вспомогательное ----

    private suspend inline fun onMain(crossinline block: () -> ActionOutcome): ActionOutcome =
        withContext(Dispatchers.Main) { runCatching { block() }.getOrElse { ActionOutcome.Failed(it.message ?: "ошибка действия") } }

    /** Находит узел по индексу так же, как его пронумеровал [SnapshotBuilder]. */
    private fun nodeAt(index: Int): AccessibilityNodeInfo? {
        val root = rootInActiveWindow ?: return null
        val collected = mutableListOf<AccessibilityNodeInfo>()
        collect(root, collected, SnapshotBuilder.MAX_NODES)
        return collected.getOrNull(index)
    }

    /** Тот же отбор значимых узлов, что и в [SnapshotBuilder.build], но с ссылками на реальные узлы. */
    private fun collect(node: AccessibilityNodeInfo, out: MutableList<AccessibilityNodeInfo>, max: Int) {
        if (out.size >= max) return
        val hasText = !node.text.isNullOrBlank() || !node.contentDescription.isNullOrBlank()
        val actionable = node.isClickable || node.isEditable || node.isScrollable || node.isCheckable
        val rect = Rect().also { node.getBoundsInScreen(it) }
        if (node.isVisibleToUser && (actionable || hasText) && rect.width() > 0 && rect.height() > 0) {
            out.add(node)
        }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            if (out.size >= max) return
            collect(child, out, max)
        }
    }

    private fun clickNode(node: AccessibilityNodeInfo): ActionOutcome {
        val clickable = if (node.isClickable) node else findClickable(node)
        if (clickable != null && clickable.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return ActionOutcome.Success
        // Узел не реагирует на ACTION_CLICK — нажимаем жестом по центру его границ.
        val rect = Rect().also { node.getBoundsInScreen(it) }
        return tapAt(rect.exactCenterX(), rect.exactCenterY())
    }

    private fun findClickable(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        var current: AccessibilityNodeInfo? = node.parent
        var depth = 0
        while (current != null && depth < 6) {
            if (current.isClickable) return current
            current = current.parent
            depth++
        }
        return null
    }

    private fun findEditable(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            if (child.isEditable) return child
            findEditable(child)?.let { return it }
        }
        return null
    }

    private fun findScrollable(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        if (node.isScrollable) return node
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            findScrollable(child)?.let { return it }
        }
        return null
    }

    private fun tapAt(x: Float, y: Float): ActionOutcome {
        if (x < 0 || y < 0) return ActionOutcome.Failed("нет координат для нажатия")
        val path = Path().apply { moveTo(x, y) }
        val stroke = GestureDescription.StrokeDescription(path, 0, 60)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()
        return if (dispatchGesture(gesture, null, null)) ActionOutcome.Success else ActionOutcome.Failed("жест нажатия не выполнен")
    }

    private fun swipe(direction: String): ActionOutcome {
        val metrics = resources.displayMetrics
        val w = metrics.widthPixels.toFloat()
        val h = metrics.heightPixels.toFloat()
        val path = Path()
        when (direction) {
            "up" -> { path.moveTo(w / 2, h * 0.35f); path.lineTo(w / 2, h * 0.75f) }
            "left" -> { path.moveTo(w * 0.2f, h / 2); path.lineTo(w * 0.8f, h / 2) }
            "right" -> { path.moveTo(w * 0.8f, h / 2); path.lineTo(w * 0.2f, h / 2) }
            else -> { path.moveTo(w / 2, h * 0.7f); path.lineTo(w / 2, h * 0.3f) } // down
        }
        val gesture = GestureDescription.Builder().addStroke(GestureDescription.StrokeDescription(path, 0, 300)).build()
        return if (dispatchGesture(gesture, null, null)) ActionOutcome.Success else ActionOutcome.Failed("прокрутка не выполнена")
    }

    private fun appLabel(pkg: String): String {
        if (pkg.isEmpty()) return ""
        return runCatching {
            val pm = packageManager
            @Suppress("DEPRECATION")
            val info = pm.getApplicationInfo(pkg, 0)
            pm.getApplicationLabel(info).toString()
        }.getOrDefault(pkg)
    }

    /** Обёртка узла Android под [RawNode] для сборки снимка. */
    private class NodeAdapter(private val node: AccessibilityNodeInfo) : RawNode {
        private val rect = Rect().also { node.getBoundsInScreen(it) }
        override val text: String? get() = node.text?.toString()
        override val contentDescription: String? get() = node.contentDescription?.toString()
        override val className: String? get() = node.className?.toString()
        override val isClickable: Boolean get() = node.isClickable
        override val isEditable: Boolean get() = node.isEditable
        override val isScrollable: Boolean get() = node.isScrollable
        override val isCheckable: Boolean get() = node.isCheckable
        override val isPassword: Boolean get() = node.isPassword
        override val isVisibleToUser: Boolean get() = node.isVisibleToUser
        override val boundsLeft: Int get() = rect.left
        override val boundsTop: Int get() = rect.top
        override val boundsRight: Int get() = rect.right
        override val boundsBottom: Int get() = rect.bottom
        override val children: List<RawNode>
            get() = (0 until node.childCount).mapNotNull { node.getChild(it)?.let { c -> NodeAdapter(c) } }
    }

    companion object {
        // Ждём готовности службы недолго — вызывающий покажет подсказку, если не дождались.
        suspend fun awaitConnected(timeoutMs: Long = 2_000): Boolean {
            if (AgentController.connected.value) return true
            val done = CompletableDeferred<Boolean>()
            return withTimeoutOrNull(timeoutMs) {
                // Простое ожидание флага.
                while (!AgentController.connected.value) kotlinx.coroutines.delay(100)
                done.complete(true); true
            } ?: false
        }

        val supportsGestures: Boolean get() = Build.VERSION.SDK_INT >= 24
    }
}
