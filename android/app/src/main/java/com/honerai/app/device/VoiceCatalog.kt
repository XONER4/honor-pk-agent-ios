package com.honerai.app.device

// Пол и качество голосов синтеза речи (порт VoiceCatalog.swift). На Android у голоса нет
// поля «пол»: берём признаки движка (features), имена голосов и известные коды голосов Google/Samsung.
// Чистый Kotlin — проверяется JVM-тестами.

/** Голос для экрана настроек: id — имя голоса движка (Voice.getName). */
data class VoiceOption(
    val id: String,
    val name: String,
    val language: String,
    /** "male", "female" или "unknown". */
    val gender: String,
    /** 100…500 (TextToSpeech.Voice.QUALITY_*). */
    val quality: Int,
    val needsNetwork: Boolean,
)

object VoiceCatalog {
    enum class Gender(val raw: String) { FEMALE("female"), MALE("male"), UNKNOWN("unknown") }

    fun genderFromRaw(raw: String): Gender = if (raw == "female") Gender.FEMALE else Gender.MALE

    private val femaleNames = setOf(
        "milena", "katya", "alyona", "alena", "elena", "irina", "marina", "tatyana", "tania",
        "oksana", "vera", "yulia", "julia", "svetlana", "anna", "daria", "dariya", "ksenia",
        "lyudmila", "nadezhda", "olga", "polina", "sofia", "valentina", "yana", "alice", "alisa",
        "samantha", "ava", "allison", "susan", "victoria", "karen", "moira", "tessa", "fiona", "kate", "serena",
        "nicky", "zoe", "joelle", "noelle", "martha", "catherine", "natasha", "natalia", "arina", "maria",
    )
    private val maleNames = setOf(
        "aaron", "arthur", "evan", "nathan", "tom", "oliver", "gordon", "lee", "rishi", "fred", "alex", "reed",
        "yuri", "yuriy", "dmitri", "dmitry", "alexander", "aleksandr", "maxim", "maksim",
        "ivan", "sergey", "sergei", "nikolay", "pavel", "andrey", "andrei", "artem", "boris",
        "victor", "viktor", "george", "georgiy", "kostya", "konstantin", "mikhail", "oleg",
        "roman", "stepan", "timur", "vladimir", "anton", "daniel", "denis", "egor", "fedor",
        "gleb", "igor", "kirill", "leonid", "nikita", "petr", "ruslan", "vadim", "yaroslav", "vsevolod", "mikhail",
    )

    /**
     * Голоса Google TTS называются кодами («ru-ru-x-rud-local», «en-us-x-iom-network»).
     * Пол известных кодов (по списку голосов Google); неизвестные коды — «unknown».
     */
    private val googleCodes: Map<String, Gender> = mapOf(
        // Английский (США)
        "iob" to Gender.FEMALE, "iog" to Gender.FEMALE, "iol" to Gender.MALE, "iom" to Gender.MALE,
        "tpc" to Gender.FEMALE, "tpd" to Gender.MALE, "tpf" to Gender.FEMALE, "sfg" to Gender.FEMALE,
        // Английский (Великобритания)
        "gba" to Gender.FEMALE, "gbb" to Gender.MALE, "gbc" to Gender.FEMALE, "gbd" to Gender.MALE,
        "gbg" to Gender.FEMALE, "rjs" to Gender.MALE, "fis" to Gender.FEMALE,
        // Русский
        "dfc" to Gender.FEMALE, "ruc" to Gender.FEMALE, "rue" to Gender.FEMALE,
        "rud" to Gender.MALE, "ruf" to Gender.MALE,
    )

    private val samsungPattern = Regex("smt([fm])\\d", RegexOption.IGNORE_CASE)
    private val googlePattern = Regex("^[a-z]{2,3}-[a-z]{2,3}-x-([a-z]{3})(?:-|$)", RegexOption.IGNORE_CASE)

    /** Пол голоса: признаки движка → код Samsung/Google → имя. */
    fun gender(name: String, features: Set<String> = emptySet()): Gender {
        val lowerFeatures = features.map { it.lowercase() }
        if (lowerFeatures.any { it == "female" || it.endsWith("gender=female") || it.endsWith("_female") }) return Gender.FEMALE
        if (lowerFeatures.any { it == "male" || it.endsWith("gender=male") || it.endsWith("_male") }) return Gender.MALE
        val lowered = name.lowercase()
        samsungPattern.find(lowered)?.let { return if (it.groupValues[1] == "f") Gender.FEMALE else Gender.MALE }
        googlePattern.find(lowered)?.let { match -> googleCodes[match.groupValues[1]]?.let { return it } }
        if (Regex("(^|[^a-z])female([^a-z]|$)").containsMatchIn(lowered)) return Gender.FEMALE
        if (Regex("(^|[^a-z])male([^a-z]|$)").containsMatchIn(lowered)) return Gender.MALE
        return genderOfName(lowered)
    }

    /** По имени: «Milena (Enhanced)» → женский. */
    fun genderOfName(name: String): Gender {
        val lowered = name.lowercase()
        val words = lowered.split(Regex("[^\\p{L}]+")).filter { it.isNotEmpty() }
        if (words.any { it in femaleNames }) return Gender.FEMALE
        if (words.any { it in maleNames }) return Gender.MALE
        return Gender.UNKNOWN
    }

    /** Кандидат для выбора голоса (без зависимости от android.speech.tts.Voice — удобно для тестов). */
    data class Candidate(
        val id: String,
        val language: String,
        val country: String,
        val quality: Int,
        val needsNetwork: Boolean,
        val gender: Gender,
    )

    /** Результат выбора: голос и поправка высоты тона, если нужного пола нет. */
    data class Choice(val id: String, val pitch: Float)

    /**
     * Лучший голос: сначала нужный пол, затем без интернета (если такой есть), затем качество,
     * затем основной вариант языка (ru-RU, en-US). Если пол голосов неизвестен — разные голоса
     * для «мужского» и «женского», а при единственном голосе — лёгкая поправка тона.
     */
    fun choose(candidates: List<Candidate>, language: String, gender: Gender, online: Boolean): Choice? {
        val code = language.take(2).lowercase()
        val preferredCountry = if (code == "en") "US" else "RU"
        var pool = candidates.filter { it.language.lowercase().startsWith(code) }
        if (!online) pool = pool.filter { !it.needsNetwork }
        if (pool.isEmpty()) return null
        val hasOffline = pool.any { !it.needsNetwork }
        fun score(voice: Candidate): Int {
            var value = voice.quality / 10
            if (hasOffline && !voice.needsNetwork) value += 40
            if (voice.country.equals(preferredCountry, ignoreCase = true)) value += 3
            return value
        }
        val ordered = pool.sortedWith(compareByDescending<Candidate> { score(it) }.thenBy { it.id })
        ordered.firstOrNull { it.gender == gender }?.let { return Choice(it.id, 1f) }
        val unknown = ordered.filter { it.gender == Gender.UNKNOWN }
        if (unknown.size >= 2) {
            // Пол неизвестен, но голосов несколько: «женский» — первый, «мужской» — второй.
            return Choice(if (gender == Gender.MALE) unknown[1].id else unknown[0].id, 1f)
        }
        val best = ordered.first()
        val pitch = when {
            best.gender == gender -> 1f
            gender == Gender.MALE -> 0.88f
            gender == Gender.FEMALE -> 1.1f
            else -> 1f
        }
        return Choice(best.id, pitch)
    }
}
