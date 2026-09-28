package com.honerai.app.ui.help

import java.text.Normalizer

// Модель руководства Honer AI (порт HelpCenter.swift). Без зависимостей от Android —
// библиотеку можно проверять обычными модульными тестами.

/** Анимированная демонстрация («мини-видео») внутри статьи или ответа FAQ. */
enum class HelpDemo(
    /** Длина одного цикла анимации в секундах. */
    val duration: Double,
    /** Кадр, который показывается, когда включено «Меньше анимаций». */
    val staticTime: Double,
    private val titleRU: String,
    private val titleEN: String,
) {
    typing(9.0, 6.2, "Живой ответ с рассуждением", "Live answer with reasoning"),
    questionTimer(12.5, 4.2, "Вопрос с таймером на 10 секунд", "A question with a 10-second timer"),
    quiz(10.0, 8.6, "Тест с мгновенной проверкой", "Quiz with instant scoring"),
    dragChat(8.5, 3.6, "Перетаскивание чата в «Закреплено»", "Dragging a chat into Pinned"),
    selectAsk(10.0, 3.6, "Вопрос о выделенном фрагменте", "Ask about a selected fragment"),
    table(10.0, 5.6, "Редактирование таблицы", "Editing a table"),
    webResearch(10.0, 3.4, "Исследование сотен сайтов", "Researching hundreds of sites"),
    photoEditor(10.0, 2.4, "Удаление фона на фото", "Removing a photo background"),
    parental(9.0, 2.5, "Включение родительского контроля", "Turning on parental controls"),
    voice(8.0, 3.6, "Голосовой ввод", "Voice input");

    fun title(english: Boolean): String = if (english) titleEN else titleRU
}

/** Цвет значка (как системные цвета iOS). Превращается в Color на экране. */
enum class HelpTint { blue, brown, cyan, gray, green, indigo, orange, pink, purple, red, teal, yellow }

/** Статья справочника. */
data class HelpArticle(
    val id: String,
    val symbol: String,
    val tint: HelpTint,
    val titleRU: String,
    val titleEN: String,
    val summaryRU: String,
    val summaryEN: String,
    val bodyRU: List<String>,
    val bodyEN: List<String>,
    val stepsRU: List<String> = emptyList(),
    val stepsEN: List<String> = emptyList(),
    val tipsRU: List<String> = emptyList(),
    val tipsEN: List<String> = emptyList(),
    val screenshot: String? = null,
    val demo: HelpDemo? = null,
    val related: List<String> = emptyList(),
) {
    fun title(english: Boolean) = if (english) titleEN else titleRU
    fun summary(english: Boolean) = if (english) summaryEN else summaryRU
    fun paragraphs(english: Boolean) = if (english) bodyEN else bodyRU
    fun steps(english: Boolean) = if (english) stepsEN else stepsRU
    fun tips(english: Boolean) = if (english) tipsEN else tipsRU

    /** Весь текст статьи на обоих языках — для поиска. */
    val searchableText: String
        get() = (listOf(titleRU, titleEN, summaryRU, summaryEN, id) +
            listOf(bodyRU, bodyEN, stepsRU, stepsEN, tipsRU, tipsEN).map { it.joinToString(" ") }).joinToString(" ")
}

/** Раздел справочника. */
data class HelpSection(
    val id: String,
    val symbol: String,
    val tint: HelpTint,
    val titleRU: String,
    val titleEN: String,
    val articles: List<HelpArticle>,
) {
    fun title(english: Boolean) = if (english) titleEN else titleRU
}

/** Вопрос и ответ. */
data class HelpFAQ(
    val questionRU: String,
    val questionEN: String,
    val answerRU: String,
    val answerEN: String,
    val screenshot: String? = null,
    val demo: HelpDemo? = null,
) {
    fun question(english: Boolean) = if (english) questionEN else questionRU
    fun answer(english: Boolean) = if (english) answerEN else answerRU
    val searchableText: String get() = listOf(questionRU, questionEN, answerRU, answerEN).joinToString(" ")
}

/** Вопрос FAQ вместе с его номером в общем списке (для идентификаторов help.faq.<index>). */
data class HelpIndexedFAQ(val index: Int, val item: HelpFAQ)

/** Короткий практический совет. */
data class HelpLifehack(
    val id: String,
    val symbol: String,
    val tint: HelpTint,
    val titleRU: String,
    val titleEN: String,
    val textRU: String,
    val textEN: String,
) {
    fun title(english: Boolean) = if (english) titleEN else titleRU
    fun text(english: Boolean) = if (english) textEN else textRU
    val searchableText: String get() = listOf(titleRU, titleEN, textRU, textEN).joinToString(" ")
}

/** Кнопка-фильтр в горизонтальной ленте категорий. */
data class HelpChip(val id: String, val symbol: String, val titleRU: String, val titleEN: String) {
    fun title(english: Boolean) = if (english) titleEN else titleRU
}

/** Библиотека и поиск. Содержимое строится один раз при первом обращении. */
object HelpLibrary {
    const val allCategory = "all"
    const val lifehacksCategory = "lifehacks"
    const val faqCategory = "faq"

    val sections: List<HelpSection> by lazy {
        listOf(
            startSection, chatSection, questionsSection, filesSection,
            editorSection, webSection, integrationsSection, tablesSection,
            memorySection, chatsSection, voiceSection, gamesSection,
            backgroundSection, privacySection, parentalSection, appearanceSection,
        )
    }

    val faq: List<HelpFAQ> by lazy { faqBasics + faqChats + faqFiles + faqMore }

    val lifehacks: List<HelpLifehack> by lazy { lifehacksPartOne + lifehacksPartTwo }

    val allArticles: List<HelpArticle> by lazy { sections.flatMap { it.articles } }

    private val articlesById: Map<String, HelpArticle> by lazy { allArticles.associateBy { it.id } }

    private val searchIndex: Map<String, String> by lazy {
        allArticles.associate { it.id to normalize(it.searchableText) }
    }

    private val faqIndex: List<String> by lazy { faq.map { normalize(it.searchableText) } }

    fun article(id: String): HelpArticle? = articlesById[id]

    fun section(articleId: String): HelpSection? =
        sections.firstOrNull { section -> section.articles.any { it.id == articleId } }

    val chips: List<HelpChip> by lazy {
        buildList {
            add(HelpChip(allCategory, "square.grid.2x2", "Все", "All"))
            sections.forEach { add(HelpChip(it.id, it.symbol, it.titleRU, it.titleEN)) }
            add(HelpChip(lifehacksCategory, "lightbulb", "Лайфхаки", "Tips & tricks"))
            add(HelpChip(faqCategory, "questionmark.circle", "Вопросы", "FAQ"))
        }
    }

    /** Приводит текст к виду для поиска: без регистра, «ё» = «е», без диакритики. */
    fun normalize(text: String): String {
        val lower = text.lowercase().replace('ё', 'е').replace('й', '\u0000')
        // Диакритику убираем, но «й» сохраняем (иначе NFD превратит её в «и»).
        val stripped = Normalizer.normalize(lower, Normalizer.Form.NFD).replace(DIACRITICS, "")
        return stripped.replace('\u0000', 'й')
    }

    private val DIACRITICS = Regex("\\p{Mn}+")
    private val SEPARATORS = Regex("[\\s\\p{P}]+")

    fun tokens(query: String): List<String> = normalize(query).split(SEPARATORS).filter { it.isNotEmpty() }

    /**
     * Статьи, в которых встречаются все слова запроса (на любом из двух языков).
     * Сначала идут статьи, где слово есть в заголовке.
     */
    fun search(query: String): List<HelpArticle> {
        val words = tokens(query)
        if (words.isEmpty()) return emptyList()
        val titleHits = ArrayList<HelpArticle>()
        val otherHits = ArrayList<HelpArticle>()
        for (article in allArticles) {
            val haystack = searchIndex[article.id] ?: normalize(article.searchableText)
            if (!words.all { haystack.contains(it) }) continue
            val titles = normalize(article.titleRU + " " + article.titleEN)
            if (words.any { titles.contains(it) }) titleHits.add(article) else otherHits.add(article)
        }
        return titleHits + otherHits
    }

    fun searchFAQ(query: String): List<HelpFAQ> = faqEntries(query).map { it.item }

    /** Вопросы FAQ с исходными номерами. Пустой запрос — все вопросы. */
    fun faqEntries(query: String): List<HelpIndexedFAQ> {
        val words = tokens(query)
        return faq.mapIndexedNotNull { index, item ->
            if (words.isEmpty() || words.all { faqIndex[index].contains(it) }) HelpIndexedFAQ(index, item) else null
        }
    }

    fun searchLifehacks(query: String): List<HelpLifehack> {
        val words = tokens(query)
        if (words.isEmpty()) return lifehacks
        return lifehacks.filter { hack ->
            val haystack = normalize(hack.searchableText)
            words.all { haystack.contains(it) }
        }
    }
}
