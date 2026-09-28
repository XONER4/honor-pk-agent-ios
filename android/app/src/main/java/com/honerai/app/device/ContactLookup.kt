package com.honerai.app.device

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.provider.ContactsContract
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Поиск контакта по имени — бэкенд инструмента find_contact (порт ContactLookup из iOS).
 * Возвращает текст для нейросети: до 5 контактов с телефонами, почтой, организацией и днём рождения.
 */
object ContactLookup {
    suspend fun find(context: Context, name: String): String = withContext(Dispatchers.IO) {
        val query = name.trim()
        if (query.isEmpty()) return@withContext "Не передано имя контакта."
        if (!ParentalControl.canUseContacts) return@withContext "Доступ к контактам отключён родительским контролем."
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) {
            return@withContext "Доступ к контактам не разрешён. Предложи пользователю открыть Настройки → Разрешения в приложении и разрешить контакты."
        }
        val found = try {
            search(context, query)
        } catch (_: Exception) {
            emptyList()
        }
        if (found.isEmpty()) "Контакт «$query» не найден."
        else "Найденные контакты:\n" + found.joinToString("\n") { "• $it" }
    }

    private fun search(context: Context, query: String): List<String> {
        val resolver = context.contentResolver
        val ids = LinkedHashMap<Long, String>()
        resolver.query(
            ContactsContract.Contacts.CONTENT_URI,
            arrayOf(ContactsContract.Contacts._ID, ContactsContract.Contacts.DISPLAY_NAME_PRIMARY),
            "${ContactsContract.Contacts.DISPLAY_NAME_PRIMARY} LIKE ?",
            arrayOf("%$query%"),
            "${ContactsContract.Contacts.DISPLAY_NAME_PRIMARY} ASC",
        )?.use { cursor ->
            while (cursor.moveToNext() && ids.size < 5) {
                ids[cursor.getLong(0)] = cursor.getString(1).orEmpty()
            }
        }
        return ids.map { (id, displayName) ->
            val parts = arrayListOf(displayName)
            val phones = ArrayList<String>()
            val emails = ArrayList<String>()
            var organization = ""
            var birthday = ""
            resolver.query(
                ContactsContract.Data.CONTENT_URI,
                arrayOf(ContactsContract.Data.MIMETYPE, ContactsContract.Data.DATA1, ContactsContract.Data.DATA2),
                "${ContactsContract.Data.CONTACT_ID} = ?",
                arrayOf(id.toString()),
                null,
            )?.use { cursor ->
                while (cursor.moveToNext()) {
                    val mime = cursor.getString(0)
                    val value = cursor.getString(1)?.trim().orEmpty()
                    if (value.isEmpty()) continue
                    when (mime) {
                        ContactsContract.CommonDataKinds.Phone.CONTENT_ITEM_TYPE -> if (value !in phones) phones.add(value)
                        ContactsContract.CommonDataKinds.Email.CONTENT_ITEM_TYPE -> if (value !in emails) emails.add(value)
                        ContactsContract.CommonDataKinds.Organization.CONTENT_ITEM_TYPE -> if (organization.isEmpty()) organization = value
                        ContactsContract.CommonDataKinds.Event.CONTENT_ITEM_TYPE -> {
                            if (cursor.getInt(2) == ContactsContract.CommonDataKinds.Event.TYPE_BIRTHDAY) birthday = formatBirthday(value)
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

    /** «1990-05-17» → «17.5.1990», «--05-17» → «17.5» (как на iPhone). */
    internal fun formatBirthday(raw: String): String {
        val match = Regex("^(\\d{4}|-)?-?(\\d{1,2})-(\\d{1,2})").find(raw.trim()) ?: return raw
        val year = match.groupValues[1].takeIf { it.length == 4 }
        val month = match.groupValues[2].toInt()
        val day = match.groupValues[3].toInt()
        return "$day.$month" + (year?.let { ".$it" } ?: "")
    }
}
