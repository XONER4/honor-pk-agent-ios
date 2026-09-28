package com.honerai.app.core

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.provider.ContactsContract
import androidx.core.content.ContextCompat
import com.honerai.app.device.DeviceInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.ZonedDateTime
import java.util.Locale
import java.util.TimeZone

/** Сведения об устройстве и месте пользователя для модели. */
object DeviceContext {
    /**
     * Блок системной инструкции. Основной источник — DeviceInfo модуля «Устройство»
     * (модель, город по геопозиции, часовой пояс, язык); запасной — то, что известно без него.
     */
    fun summary(context: Context?): String {
        if (context != null) {
            runCatching { DeviceInfo.summary(context) }.getOrNull()?.takeIf { it.isNotBlank() }?.let { return it }
        }
        return fallback()
    }

    fun fallback(now: ZonedDateTime = ZonedDateTime.now()): String {
        val lines = mutableListOf<String>()
        runCatching { lines.add("Устройство: ${DeviceInfo.modelName}, ${DeviceInfo.osDescription}") }
        val russian = Locale("ru", "RU")
        val locale = Locale.getDefault()
        if (locale.country.isNotEmpty()) lines.add("Регион в настройках телефона: ${locale.getDisplayCountry(russian)}")
        val offset = now.offset.totalSeconds / 3600
        lines.add("Часовой пояс: ${TimeZone.getDefault().id} (UTC${if (offset >= 0) "+" else ""}$offset)")
        if (locale.language.isNotEmpty()) lines.add("Язык системы: ${locale.getDisplayName(russian)}")
        return "\nСведения об устройстве пользователя (используй, когда это помогает ответу — погода, время, местные цены, расписания; не пересказывай без повода):\n" +
            lines.joinToString("\n") { "• $it" }
    }
}

/** Поиск контакта по имени — для инструмента find_contact (только с разрешением READ_CONTACTS). */
object ContactLookup {
    suspend fun execute(context: Context?, call: ToolCallRequest): ToolCallResult {
        val query = ToolArgument.string(call.parsedArguments["name"]).orEmpty().trim()
        if (query.isEmpty()) return ToolCallResult(call.id, call.name, "Не передано имя контакта.")
        val granted = context != null && runCatching {
            ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED
        }.getOrDefault(false)
        if (!granted || context == null) {
            return ToolCallResult(call.id, call.name,
                "Доступ к контактам не разрешён. Предложи пользователю открыть Настройки → Разрешения в приложении и разрешить контакты.")
        }
        val found = withContext(Dispatchers.IO) { runCatching { search(context, query) }.getOrDefault(emptyList()) }
        if (found.isEmpty()) return ToolCallResult(call.id, call.name, "Контакт «$query» не найден.")
        return ToolCallResult(call.id, call.name, "Найденные контакты:\n" + found.joinToString("\n") { "• $it" })
    }

    private fun search(context: Context, query: String): List<String> {
        val resolver = context.contentResolver
        val contacts = mutableListOf<Pair<Long, String>>()
        resolver.query(
            ContactsContract.Contacts.CONTENT_URI,
            arrayOf(ContactsContract.Contacts._ID, ContactsContract.Contacts.DISPLAY_NAME_PRIMARY),
            "${ContactsContract.Contacts.DISPLAY_NAME_PRIMARY} LIKE ?", arrayOf("%$query%"),
            "${ContactsContract.Contacts.DISPLAY_NAME_PRIMARY} ASC",
        )?.use { cursor ->
            while (cursor.moveToNext() && contacts.size < 5) {
                contacts.add(cursor.getLong(0) to (cursor.getString(1) ?: ""))
            }
        }
        return contacts.map { (id, name) ->
            val parts = mutableListOf(name)
            val phones = mutableListOf<String>()
            val emails = mutableListOf<String>()
            var organization = ""
            var birthday = ""
            resolver.query(
                ContactsContract.Data.CONTENT_URI,
                arrayOf(ContactsContract.Data.MIMETYPE, ContactsContract.Data.DATA1, ContactsContract.Data.DATA2),
                "${ContactsContract.Data.CONTACT_ID} = ?", arrayOf(id.toString()), null,
            )?.use { cursor ->
                while (cursor.moveToNext()) {
                    val type = cursor.getString(0) ?: continue
                    val value = cursor.getString(1)?.trim().orEmpty()
                    if (value.isEmpty()) continue
                    when (type) {
                        ContactsContract.CommonDataKinds.Phone.CONTENT_ITEM_TYPE -> if (value !in phones) phones.add(value)
                        ContactsContract.CommonDataKinds.Email.CONTENT_ITEM_TYPE -> if (value !in emails) emails.add(value)
                        ContactsContract.CommonDataKinds.Organization.CONTENT_ITEM_TYPE -> if (organization.isEmpty()) organization = value
                        ContactsContract.CommonDataKinds.Event.CONTENT_ITEM_TYPE -> {
                            val kind = cursor.getInt(2)
                            if (kind == ContactsContract.CommonDataKinds.Event.TYPE_BIRTHDAY) birthday = value
                        }
                    }
                }
            }
            if (organization.isNotEmpty()) parts.add("организация: $organization")
            if (phones.isNotEmpty()) parts.add("телефоны: " + phones.joinToString(", "))
            if (emails.isNotEmpty()) parts.add("почта: " + emails.joinToString(", "))
            if (birthday.isNotEmpty()) parts.add("день рождения: $birthday")
            parts.joinToString("; ")
        }
    }
}
