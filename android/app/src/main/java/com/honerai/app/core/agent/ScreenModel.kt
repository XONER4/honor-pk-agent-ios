package com.honerai.app.core.agent

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Модель экрана для агента. Всё — чистый Kotlin, без Android: служба доступности приводит
 * дерево [android.view.accessibility.AccessibilityNodeInfo] к [RawNode], а тесты подставляют свои узлы.
 */

/** Один узел на экране в том виде, в каком его читает служба доступности. */
interface RawNode {
    val text: String?
    val contentDescription: String?
    val className: String?
    val isClickable: Boolean
    val isEditable: Boolean
    val isScrollable: Boolean
    val isCheckable: Boolean
    val isPassword: Boolean
    val isVisibleToUser: Boolean
    /** Границы на экране в пикселях: слева, сверху, справа, снизу. */
    val boundsLeft: Int
    val boundsTop: Int
    val boundsRight: Int
    val boundsBottom: Int
    val children: List<RawNode>
}

/** Видимый узел с устойчивым индексом — то, что агент видит и на что может нажать. */
data class ScreenNode(
    val index: Int,
    val text: String,
    val contentDescription: String,
    val className: String,
    val clickable: Boolean,
    val editable: Boolean,
    val scrollable: Boolean,
    val checkable: Boolean,
    /** Поле пароля: агент никогда не вводит сюда текст, содержимое скрыто. */
    val isPassword: Boolean,
    val bounds: String,
) {
    /** Текст, по которому агент опознаёт кнопку (для нажатия по тексту и проверки на «важное» действие). */
    val label: String get() = text.ifBlank { contentDescription }
}

/** Снимок экрана: приложение впереди и видимые узлы с индексами. */
data class ScreenSnapshot(
    val packageName: String,
    val appLabel: String,
    val nodes: List<ScreenNode>,
) {
    fun node(index: Int): ScreenNode? = nodes.firstOrNull { it.index == index }

    /** Первый узел, чей текст/описание содержит [query] (без регистра). */
    fun nodeByText(query: String): ScreenNode? {
        val q = query.trim().lowercase()
        if (q.isEmpty()) return null
        return nodes.firstOrNull { it.label.lowercase() == q }
            ?: nodes.firstOrNull { it.label.lowercase().contains(q) }
    }
}

/** Сборка снимка из дерева узлов: обход в глубину, отбор значимых узлов, скрытие паролей. */
object SnapshotBuilder {
    const val MAX_NODES = 60
    const val REDACTED = "•••"
    private const val MAX_TEXT = 120

    fun build(packageName: String, appLabel: String, root: RawNode?, maxNodes: Int = MAX_NODES): ScreenSnapshot {
        val collected = mutableListOf<ScreenNode>()
        if (root != null) walk(root, collected, maxNodes)
        return ScreenSnapshot(packageName, appLabel, collected)
    }

    private fun walk(node: RawNode, out: MutableList<ScreenNode>, maxNodes: Int) {
        if (out.size >= maxNodes) return
        // Невидимое и пустое место не показываем: узел значим, если с ним можно взаимодействовать
        // либо он несёт текст (заголовок, цена, статус).
        val hasText = !node.text.isNullOrBlank() || !node.contentDescription.isNullOrBlank()
        val actionable = node.isClickable || node.isEditable || node.isScrollable || node.isCheckable
        if (node.isVisibleToUser && (actionable || hasText) && node.width > 0 && node.height > 0) {
            out.add(
                ScreenNode(
                    index = out.size,
                    text = clip(node.text, node.isPassword),
                    contentDescription = clip(node.contentDescription, node.isPassword),
                    className = shortClass(node.className),
                    clickable = node.isClickable,
                    editable = node.isEditable,
                    scrollable = node.isScrollable,
                    checkable = node.isCheckable,
                    isPassword = node.isPassword,
                    bounds = "${node.boundsLeft},${node.boundsTop},${node.boundsRight},${node.boundsBottom}",
                ),
            )
        }
        for (child in node.children) {
            if (out.size >= maxNodes) return
            walk(child, out, maxNodes)
        }
    }

    private val RawNode.width: Int get() = boundsRight - boundsLeft
    private val RawNode.height: Int get() = boundsBottom - boundsTop

    /** Пароль скрываем полностью; остальное подрезаем и чистим переносы. */
    private fun clip(value: String?, password: Boolean): String {
        val text = value?.replace(Regex("\\s+"), " ")?.trim().orEmpty()
        if (text.isEmpty()) return ""
        if (password) return REDACTED
        return if (text.length > MAX_TEXT) text.take(MAX_TEXT) + "…" else text
    }

    private fun shortClass(name: String?): String = name?.substringAfterLast('.').orEmpty()
}

/** Компактный JSON снимка для нейросети. */
object ScreenSerialization {
    fun toJson(snapshot: ScreenSnapshot): JsonObject = buildJsonObject {
        put("app", snapshot.appLabel)
        put("package", snapshot.packageName)
        put("nodes", nodesJson(snapshot.nodes))
    }

    private fun nodesJson(nodes: List<ScreenNode>): JsonArray = buildJsonArray {
        for (node in nodes) add(buildJsonObject {
            put("i", node.index)
            if (node.text.isNotEmpty()) put("text", node.text)
            if (node.contentDescription.isNotEmpty() && node.contentDescription != node.text) put("desc", node.contentDescription)
            if (node.className.isNotEmpty()) put("cls", node.className)
            // Флаги ставим только когда true — так JSON короче.
            if (node.clickable) put("clickable", true)
            if (node.editable) put("editable", true)
            if (node.scrollable) put("scrollable", true)
            if (node.checkable) put("checkable", true)
            if (node.isPassword) put("password", true)
            put("bounds", node.bounds)
        })
    }

    fun toCompactString(snapshot: ScreenSnapshot): String = toJson(snapshot).toString()
}
