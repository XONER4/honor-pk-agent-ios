package com.honerai.app.settings

import com.honerai.app.ui.help.HelpDemo
import com.honerai.app.ui.help.HelpLibrary
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Целостность руководства: идентификаторы, ссылки, оба языка, поиск. */
class HelpLibraryTest {
    @Test
    fun sectionsAndArticlesHaveUniqueIds() {
        assertEquals(16, HelpLibrary.sections.size)
        assertEquals(HelpLibrary.sections.size, HelpLibrary.sections.map { it.id }.toSet().size)
        val ids = HelpLibrary.allArticles.map { it.id }
        assertEquals(57, ids.size) // media: +статья «Поисковики, приложения и загрузки»
        assertEquals(ids.size, ids.toSet().size)
        assertEquals(HelpLibrary.lifehacks.size, HelpLibrary.lifehacks.map { it.id }.toSet().size)
        assertEquals(HelpLibrary.chips.size, HelpLibrary.chips.map { it.id }.toSet().size)
    }

    @Test
    fun relatedArticlesExist() {
        for (article in HelpLibrary.allArticles) {
            for (id in article.related) assertNotNull("${article.id} → $id", HelpLibrary.article(id))
            assertFalse(article.id, article.related.contains(article.id))
        }
    }

    @Test
    fun bothLanguagesAreFilled() {
        for (section in HelpLibrary.sections) {
            assertTrue(section.titleRU.isNotBlank() && section.titleEN.isNotBlank())
            assertTrue(section.articles.isNotEmpty())
        }
        for (a in HelpLibrary.allArticles) {
            assertTrue(a.id, a.titleRU.isNotBlank() && a.titleEN.isNotBlank())
            assertTrue(a.id, a.summaryRU.isNotBlank() && a.summaryEN.isNotBlank())
            assertTrue(a.id, a.bodyRU.isNotEmpty() && a.bodyRU.size == a.bodyEN.size)
            assertTrue(a.id, (a.bodyRU + a.bodyEN + a.stepsRU + a.stepsEN + a.tipsRU + a.tipsEN).all { it.isNotBlank() })
            assertEquals(a.id, a.stepsRU.size, a.stepsEN.size)
            assertEquals(a.id, a.tipsRU.size, a.tipsEN.size)
        }
        for (f in HelpLibrary.faq) {
            assertTrue(f.questionRU.isNotBlank() && f.questionEN.isNotBlank() && f.answerRU.isNotBlank() && f.answerEN.isNotBlank())
        }
        for (h in HelpLibrary.lifehacks) {
            assertTrue(h.id, h.titleRU.isNotBlank() && h.titleEN.isNotBlank() && h.textRU.isNotBlank() && h.textEN.isNotBlank())
        }
        for (d in HelpDemo.entries) assertTrue(d.title(false).isNotBlank() && d.title(true).isNotBlank())
    }

    @Test
    fun counts() {
        assertTrue(HelpLibrary.faq.size >= 25)
        assertEquals(32, HelpLibrary.faq.size)
        assertEquals(15, HelpLibrary.lifehacks.size)
        // Все 10 мини-роликов встречаются в статьях или ответах.
        val used = (HelpLibrary.allArticles.mapNotNull { it.demo } + HelpLibrary.faq.mapNotNull { it.demo }).toSet()
        assertEquals(HelpDemo.entries.toSet(), used)
    }

    @Test
    fun screenshotsExistInAssets() {
        val names = (HelpLibrary.allArticles.mapNotNull { it.screenshot } + HelpLibrary.faq.mapNotNull { it.screenshot }).toSet()
        assertTrue(names.isNotEmpty())
        val dir = listOf(File("src/main/assets/guide"), File("app/src/main/assets/guide")).firstOrNull { it.isDirectory }
        assertNotNull("assets/guide not found", dir)
        for (name in names) assertTrue(name, File(dir, "$name.jpg").isFile)
    }

    @Test
    fun searchFindsTablesAndParental() {
        val tables = HelpLibrary.search("таблиц")
        assertTrue(tables.isNotEmpty())
        assertTrue(tables.any { it.id.contains("table") })
        val parental = HelpLibrary.search("родител")
        assertTrue(parental.isNotEmpty())
        assertTrue(parental.any { HelpLibrary.section(it.id)?.id == "parental" })
        // Заголовочные совпадения идут первыми.
        assertTrue(HelpLibrary.normalize(tables.first().titleRU + tables.first().titleEN).contains("таблиц"))
        assertTrue(HelpLibrary.search("Родительский КОНТРОЛЬ").isNotEmpty())
        assertTrue(HelpLibrary.search("ёжиков-зябликов xyzzy").isEmpty())
        assertTrue(HelpLibrary.search("   ").isEmpty())
        assertEquals(HelpLibrary.faq.size, HelpLibrary.faqEntries("").size)
        assertTrue(HelpLibrary.searchFAQ("архив").isNotEmpty())
        assertEquals(HelpLibrary.lifehacks, HelpLibrary.searchLifehacks(""))
    }

    @Test
    fun noIphoneLeftoversInRussianText() {
        val text = (HelpLibrary.allArticles.map { it.searchableText } + HelpLibrary.faq.map { it.searchableText }).joinToString(" ")
        assertFalse(text.contains("iPhone"))
        assertFalse(text.contains("Face ID"))
    }
}
