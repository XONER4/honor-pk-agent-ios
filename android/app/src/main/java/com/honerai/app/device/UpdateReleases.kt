package com.honerai.app.device

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull

// Разбор выпусков GitHub для автообновления. Чистый Kotlin — проверяется JVM-тестами.
// Android-выпуски помечаются тегом «android-v10.44.0» (или «android-v10.44.0-1044»),
// а номер сборки пишется в описании строкой «versionCode: 1044».

object UpdateReleases {
    const val TAG_PREFIX = "android-v"

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private val versionCodeLine = Regex("(?im)^\\s*[*_`]*\\s*versionCode\\s*[*_`]*\\s*[:=]\\s*[*_`]*\\s*(\\d+)")
    private val tagCode = Regex("^android-v[0-9][0-9.]*[-+](\\d+)$", RegexOption.IGNORE_CASE)

    /** Номер сборки из описания («versionCode: 1044») или из тега («android-v10.44.0-1044»); null — не указан. */
    fun parseVersionCode(body: String, tag: String): Int? {
        versionCodeLine.find(body)?.groupValues?.get(1)?.toIntOrNull()?.let { return it }
        return tagCode.find(tag.trim())?.groupValues?.get(1)?.toIntOrNull()
    }

    /** «android-v10.44.0-1044» → «10.44.0». */
    fun versionName(tag: String): String {
        val rest = tag.trim().removePrefix(TAG_PREFIX).removePrefix("V")
        return rest.split('-', '+').first()
    }

    /** Сравнение версий по числам: «10.44.1» > «10.44.0», «10.5» < «10.44»; суффиксы (-debug) игнорируются. */
    fun compareVersionNames(left: String, right: String): Int {
        fun parts(value: String): List<Int> = value.trim().removePrefix("v").removePrefix("V")
            .split('-', '+', ' ').first()
            .split('.')
            .map { part -> part.takeWhile { it.isDigit() }.toIntOrNull() ?: 0 }
        val a = parts(left)
        val b = parts(right)
        for (index in 0 until maxOf(a.size, b.size)) {
            val x = a.getOrElse(index) { 0 }
            val y = b.getOrElse(index) { 0 }
            if (x != y) return x.compareTo(y)
        }
        return 0
    }

    /** Новее ли выпуск установленной сборки: по versionCode, а если его нет — по имени версии. */
    fun isNewer(info: UpdateInfo, currentCode: Int, currentName: String): Boolean =
        if (info.versionCode > 0) info.versionCode > currentCode else compareVersionNames(info.versionName, currentName) > 0

    /** Описание выпуска без служебной строки versionCode. */
    fun cleanNotes(body: String): String =
        body.lines().filterNot { versionCodeLine.containsMatchIn(it) }.joinToString("\n").trim().take(4_000)

    /**
     * Самый новый подходящий выпуск из ответа /repos/{repo}/releases: не черновик, тег «android-v…»,
     * есть .apk. Если versionCode не указан, [UpdateInfo.versionCode] = 0.
     */
    fun pickLatest(releasesJson: String): UpdateInfo? {
        val root = runCatching { json.parseToJsonElement(releasesJson) }.getOrNull() as? JsonArray ?: return null
        val candidates = ArrayList<UpdateInfo>()
        for (element in root) {
            val release = element as? JsonObject ?: continue
            if (release.bool("draft") == true) continue
            val tag = release.string("tag_name") ?: continue
            if (!tag.startsWith(TAG_PREFIX, ignoreCase = true)) continue
            val assets = release["assets"] as? JsonArray ?: continue
            val apks = assets.mapNotNull { it as? JsonObject }.filter { (it.string("name") ?: "").lowercase().endsWith(".apk") }
            // Если APK несколько — берём релизную сборку, а не отладочную.
            val apk = apks.firstOrNull { !(it.string("name") ?: "").lowercase().contains("debug") } ?: apks.firstOrNull() ?: continue
            val url = apk.string("browser_download_url") ?: continue
            val body = release.string("body") ?: ""
            candidates.add(UpdateInfo(
                versionName = versionName(tag),
                versionCode = parseVersionCode(body, tag) ?: 0,
                notes = cleanNotes(body),
                apkUrl = url,
                sizeBytes = apk.long("size") ?: 0L,
            ))
        }
        if (candidates.isEmpty()) return null
        // GitHub отдаёт выпуски от новых к старым, но надёжнее сравнить номера.
        return candidates.reduce { best, next ->
            val newer = when {
                next.versionCode > 0 && best.versionCode > 0 -> next.versionCode > best.versionCode
                else -> compareVersionNames(next.versionName, best.versionName) > 0
            }
            if (newer) next else best
        }
    }

    private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
    private fun JsonObject.bool(key: String): Boolean? = (this[key] as? JsonPrimitive)?.booleanOrNull
    private fun JsonObject.long(key: String): Long? = (this[key] as? JsonPrimitive)?.longOrNull
}
