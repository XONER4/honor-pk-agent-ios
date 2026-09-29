package com.honerai.app.agent

import com.honerai.app.core.agent.RawNode
import com.honerai.app.core.agent.ScreenSerialization
import com.honerai.app.core.agent.SnapshotBuilder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Поддельный узел дерева для проверки сборки снимка без Android. */
class FakeNode(
    override val text: String? = null,
    override val contentDescription: String? = null,
    override val className: String? = "android.widget.View",
    override val isClickable: Boolean = false,
    override val isEditable: Boolean = false,
    override val isScrollable: Boolean = false,
    override val isCheckable: Boolean = false,
    override val isPassword: Boolean = false,
    override val isVisibleToUser: Boolean = true,
    override val boundsLeft: Int = 0,
    override val boundsTop: Int = 0,
    override val boundsRight: Int = 100,
    override val boundsBottom: Int = 40,
    override val children: List<RawNode> = emptyList(),
) : RawNode

class ScreenSnapshotTest {
    private fun tree(): FakeNode = FakeNode(
        className = "FrameLayout",
        children = listOf(
            FakeNode(text = "Поиск", isClickable = true, boundsLeft = 0, boundsTop = 0, boundsRight = 300, boundsBottom = 80),
            FakeNode(text = "наушники", isEditable = true, boundsLeft = 0, boundsTop = 90, boundsRight = 300, boundsBottom = 150),
            FakeNode(text = "секрет123", isEditable = true, isPassword = true, boundsLeft = 0, boundsTop = 160, boundsRight = 300, boundsBottom = 220),
            // Невидимый узел — в снимок не попадает.
            FakeNode(text = "спрятано", isVisibleToUser = false),
            // Пустой невзаимодействуемый узел без текста — пропускается.
            FakeNode(className = "View"),
        ),
    )

    @Test fun buildsSnapshotWithStableIndicesAndFlags() {
        val snapshot = SnapshotBuilder.build("com.wildberries.ru", "Wildberries", tree())
        assertEquals("com.wildberries.ru", snapshot.packageName)
        assertEquals("Wildberries", snapshot.appLabel)
        // Три значимых узла: кнопка поиска, поле ввода, поле пароля.
        assertEquals(3, snapshot.nodes.size)
        assertEquals(0, snapshot.nodes[0].index)
        assertEquals(1, snapshot.nodes[1].index)
        assertEquals(2, snapshot.nodes[2].index)
        assertTrue(snapshot.nodes[0].clickable)
        assertTrue(snapshot.nodes[1].editable)
    }

    @Test fun passwordNodeIsFlaggedAndTextRedacted() {
        val snapshot = SnapshotBuilder.build("app", "App", tree())
        val password = snapshot.nodes.first { it.isPassword }
        assertTrue(password.isPassword)
        assertEquals(SnapshotBuilder.REDACTED, password.text)
        assertFalse(password.text.contains("секрет"))
        // Обычные узлы текст сохраняют.
        assertEquals("наушники", snapshot.nodes[1].text)
    }

    @Test fun compactJsonContainsRedactedPasswordFlagAndNoSecret() {
        val snapshot = SnapshotBuilder.build("com.wildberries.ru", "Wildberries", tree())
        val json = ScreenSerialization.toCompactString(snapshot)
        assertTrue(json.contains("\"password\":true"))
        assertTrue(json.contains(SnapshotBuilder.REDACTED))
        assertFalse(json.contains("секрет123"))
        assertTrue(json.contains("\"editable\":true"))
        assertTrue(json.contains("Wildberries"))
    }

    @Test fun truncatesToMaxNodes() {
        val many = (0 until 200).map { FakeNode(text = "item$it", isClickable = true, boundsTop = it * 10, boundsBottom = it * 10 + 8) }
        val root = FakeNode(className = "List", children = many)
        val snapshot = SnapshotBuilder.build("app", "App", root)
        assertEquals(SnapshotBuilder.MAX_NODES, snapshot.nodes.size)
        assertEquals(0, snapshot.nodes.first().index)
        assertEquals(SnapshotBuilder.MAX_NODES - 1, snapshot.nodes.last().index)
    }

    @Test fun nodeLookupByText() {
        val snapshot = SnapshotBuilder.build("app", "App", tree())
        assertEquals(0, snapshot.nodeByText("Поиск")?.index)
        assertEquals(0, snapshot.nodeByText("поис")?.index)
    }
}
