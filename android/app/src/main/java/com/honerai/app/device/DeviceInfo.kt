package com.honerai.app.device

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Geocoder
import android.location.Location
import android.location.LocationManager
import android.os.Build
import androidx.core.content.ContextCompat
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.Executors

/**
 * Сведения об устройстве для нейросети (порт DeviceModel и DeviceContext из iOS):
 * модель телефона, версия Android, город (если разрешена геопозиция), регион, часовой пояс, язык.
 */
object DeviceInfo {
    /** «Samsung Galaxy S24 Ultra», «Xiaomi 14». */
    val modelName: String get() = DeviceModels.marketingName(Build.MANUFACTURER.orEmpty(), Build.MODEL.orEmpty(), systemDeviceName)

    /** Имя устройства из настроек системы (у новых Xiaomi Build.MODEL — код вида «23127PN0CG»). */
    @Volatile private var systemDeviceName: String? = null

    /** Запомнить имя устройства из настроек системы; вызывается из [summary], можно и при запуске. */
    fun init(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N_MR1) {
            systemDeviceName = runCatching {
                android.provider.Settings.Global.getString(context.contentResolver, android.provider.Settings.Global.DEVICE_NAME)
            }.getOrNull()?.trim()?.takeIf { it.isNotEmpty() }
        }
    }
    val osDescription: String get() = "Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})"
    val appVersion: String get() = com.honerai.app.BuildConfig.VERSION_NAME

    private const val PREFS = "honer.device"
    const val CITY_KEY = "honor.deviceCity"
    const val CITY_UPDATED_KEY = "honor.deviceCityUpdated"
    private const val REFRESH_INTERVAL_MS = 60L * 60 * 1000
    private val worker = Executors.newSingleThreadExecutor { runnable -> Thread(runnable, "honer-geocoder").apply { isDaemon = true } }

    /** Блок для системной инструкции: устройство, город (если разрешён), часовой пояс, язык. */
    fun summary(context: Context): String {
        val app = context.applicationContext
        if (systemDeviceName == null) init(app)
        val lines = ArrayList<String>()
        lines.add("Устройство: $modelName, $osDescription")
        val city = currentCity(app)
        if (!city.isNullOrBlank()) lines.add("Местоположение по геопозиции: $city")
        val russian = Locale("ru", "RU")
        val locale = Locale.getDefault()
        if (locale.country.isNotEmpty()) {
            val name = locale.getDisplayCountry(russian).ifEmpty { locale.country }
            lines.add("Регион в настройках телефона: $name")
        }
        val zone = TimeZone.getDefault()
        lines.add("Часовой пояс: ${zone.id} (${utcOffset(zone.getOffset(System.currentTimeMillis()))})")
        val language = locale.getDisplayName(russian).ifEmpty { locale.toLanguageTag() }
        lines.add("Язык системы: $language")
        return "\nСведения об устройстве пользователя (используй, когда это помогает ответу — погода, время, местные цены, расписания; не пересказывай без повода):\n" +
            lines.joinToString("\n") { "• $it" }
    }

    /** "UTC+3", "UTC-5", "UTC+5:30". */
    internal fun utcOffset(offsetMillis: Int): String {
        val totalMinutes = offsetMillis / 60_000
        val sign = if (totalMinutes >= 0) "+" else "-"
        val hours = kotlin.math.abs(totalMinutes) / 60
        val minutes = kotlin.math.abs(totalMinutes) % 60
        return "UTC$sign$hours" + if (minutes > 0) ":" + minutes.toString().padStart(2, '0') else ""
    }

    /** Город из кэша; раз в час обновляется в фоне по последней известной (приблизительной) геопозиции. */
    fun currentCity(context: Context): String? {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (!ParentalControl.canUseLocation || !hasLocationPermission(context)) {
            if (prefs.contains(CITY_KEY)) prefs.edit().remove(CITY_KEY).remove(CITY_UPDATED_KEY).apply()
            return null
        }
        val updated = prefs.getLong(CITY_UPDATED_KEY, 0L)
        if (System.currentTimeMillis() - updated > REFRESH_INTERVAL_MS) refreshCity(context)
        return prefs.getString(CITY_KEY, null)
    }

    /** Обновить город сейчас (например, сразу после выдачи разрешения на геопозицию). */
    fun refreshCity(context: Context, force: Boolean = false) {
        val app = context.applicationContext
        if (!ParentalControl.canUseLocation || !hasLocationPermission(app) || !Geocoder.isPresent()) return
        val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (!force && System.currentTimeMillis() - prefs.getLong(CITY_UPDATED_KEY, 0L) < REFRESH_INTERVAL_MS) return
        // Отмечаем попытку сразу — чтобы не запускать геокодер на каждый запрос.
        prefs.edit().putLong(CITY_UPDATED_KEY, System.currentTimeMillis()).apply()
        worker.execute {
            val location = lastKnownLocation(app) ?: return@execute
            val city = geocode(app, location) ?: return@execute
            prefs.edit().putString(CITY_KEY, city).putLong(CITY_UPDATED_KEY, System.currentTimeMillis()).apply()
        }
    }

    private fun hasLocationPermission(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

    @SuppressLint("MissingPermission")
    private fun lastKnownLocation(context: Context): Location? {
        val manager = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return null
        val providers = runCatching { manager.getProviders(true) }.getOrDefault(emptyList())
        return providers.mapNotNull { provider -> runCatching { manager.getLastKnownLocation(provider) }.getOrNull() }
            .maxByOrNull { it.time }
    }

    @Suppress("DEPRECATION")
    private fun geocode(context: Context, location: Location): String? {
        val geocoder = Geocoder(context, Locale("ru", "RU"))
        val address = try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                val latch = java.util.concurrent.CountDownLatch(1)
                var result: android.location.Address? = null
                geocoder.getFromLocation(location.latitude, location.longitude, 1, object : Geocoder.GeocodeListener {
                    override fun onGeocode(addresses: MutableList<android.location.Address>) {
                        result = addresses.firstOrNull(); latch.countDown()
                    }
                    override fun onError(errorMessage: String?) { latch.countDown() }
                })
                latch.await(10, java.util.concurrent.TimeUnit.SECONDS)
                result
            } else {
                geocoder.getFromLocation(location.latitude, location.longitude, 1)?.firstOrNull()
            }
        } catch (_: Exception) {
            null
        } ?: return null
        val parts = listOfNotNull(address.locality ?: address.subAdminArea, address.adminArea, address.countryName)
            .filter { it.isNotBlank() }
            .distinct()
        return parts.takeIf { it.isNotEmpty() }?.joinToString(", ")
    }
}

/** Маркетинговые названия моделей: «SM-S928B» → «Galaxy S24 Ultra». Чистый Kotlin — проверяется тестами. */
object DeviceModels {
    /** Коды Samsung без региональной буквы в конце (SM-S928B, SM-S928U → SM-S928). */
    private val samsung: Map<String, String> = mapOf(
        // Galaxy S
        "SM-G960" to "Galaxy S9", "SM-G965" to "Galaxy S9+",
        "SM-G970" to "Galaxy S10e", "SM-G973" to "Galaxy S10", "SM-G975" to "Galaxy S10+", "SM-G977" to "Galaxy S10 5G",
        "SM-G980" to "Galaxy S20", "SM-G981" to "Galaxy S20 5G", "SM-G985" to "Galaxy S20+", "SM-G986" to "Galaxy S20+ 5G",
        "SM-G988" to "Galaxy S20 Ultra", "SM-G780" to "Galaxy S20 FE", "SM-G781" to "Galaxy S20 FE 5G",
        "SM-G991" to "Galaxy S21", "SM-G996" to "Galaxy S21+", "SM-G998" to "Galaxy S21 Ultra", "SM-G990" to "Galaxy S21 FE",
        "SM-S901" to "Galaxy S22", "SM-S906" to "Galaxy S22+", "SM-S908" to "Galaxy S22 Ultra",
        "SM-S911" to "Galaxy S23", "SM-S916" to "Galaxy S23+", "SM-S918" to "Galaxy S23 Ultra", "SM-S711" to "Galaxy S23 FE",
        "SM-S921" to "Galaxy S24", "SM-S926" to "Galaxy S24+", "SM-S928" to "Galaxy S24 Ultra", "SM-S721" to "Galaxy S24 FE",
        "SM-S931" to "Galaxy S25", "SM-S936" to "Galaxy S25+", "SM-S938" to "Galaxy S25 Ultra", "SM-S937" to "Galaxy S25 Edge",
        "SM-S731" to "Galaxy S25 FE",
        // Galaxy Note
        "SM-N960" to "Galaxy Note9", "SM-N970" to "Galaxy Note10", "SM-N975" to "Galaxy Note10+",
        "SM-N980" to "Galaxy Note20", "SM-N981" to "Galaxy Note20 5G", "SM-N985" to "Galaxy Note20 Ultra", "SM-N986" to "Galaxy Note20 Ultra 5G",
        // Galaxy Z
        "SM-F700" to "Galaxy Z Flip", "SM-F707" to "Galaxy Z Flip 5G", "SM-F711" to "Galaxy Z Flip3", "SM-F721" to "Galaxy Z Flip4",
        "SM-F731" to "Galaxy Z Flip5", "SM-F741" to "Galaxy Z Flip6", "SM-F761" to "Galaxy Z Flip7", "SM-F766" to "Galaxy Z Flip7 FE",
        "SM-F916" to "Galaxy Z Fold2", "SM-F926" to "Galaxy Z Fold3", "SM-F936" to "Galaxy Z Fold4", "SM-F946" to "Galaxy Z Fold5",
        "SM-F956" to "Galaxy Z Fold6", "SM-F966" to "Galaxy Z Fold7",
        // Galaxy A
        "SM-A105" to "Galaxy A10", "SM-A107" to "Galaxy A10s", "SM-A115" to "Galaxy A11", "SM-A125" to "Galaxy A12", "SM-A127" to "Galaxy A12",
        "SM-A135" to "Galaxy A13", "SM-A137" to "Galaxy A13", "SM-A136" to "Galaxy A13 5G", "SM-A145" to "Galaxy A14", "SM-A146" to "Galaxy A14 5G",
        "SM-A155" to "Galaxy A15", "SM-A156" to "Galaxy A15 5G", "SM-A165" to "Galaxy A16", "SM-A166" to "Galaxy A16 5G",
        "SM-A205" to "Galaxy A20", "SM-A207" to "Galaxy A20s", "SM-A215" to "Galaxy A21", "SM-A217" to "Galaxy A21s",
        "SM-A225" to "Galaxy A22", "SM-A226" to "Galaxy A22 5G", "SM-A235" to "Galaxy A23", "SM-A236" to "Galaxy A23 5G",
        "SM-A245" to "Galaxy A24", "SM-A253" to "Galaxy A25", "SM-A256" to "Galaxy A25 5G", "SM-A266" to "Galaxy A26 5G",
        "SM-A305" to "Galaxy A30", "SM-A307" to "Galaxy A30s", "SM-A315" to "Galaxy A31", "SM-A325" to "Galaxy A32", "SM-A326" to "Galaxy A32 5G",
        "SM-A336" to "Galaxy A33 5G", "SM-A346" to "Galaxy A34 5G", "SM-A356" to "Galaxy A35 5G", "SM-A366" to "Galaxy A36 5G",
        "SM-A405" to "Galaxy A40", "SM-A415" to "Galaxy A41",
        "SM-A505" to "Galaxy A50", "SM-A507" to "Galaxy A50s", "SM-A515" to "Galaxy A51", "SM-A516" to "Galaxy A51 5G",
        "SM-A525" to "Galaxy A52", "SM-A526" to "Galaxy A52 5G", "SM-A528" to "Galaxy A52s 5G", "SM-A536" to "Galaxy A53 5G",
        "SM-A546" to "Galaxy A54 5G", "SM-A556" to "Galaxy A55 5G", "SM-A566" to "Galaxy A56 5G",
        "SM-A705" to "Galaxy A70", "SM-A715" to "Galaxy A71", "SM-A716" to "Galaxy A71 5G", "SM-A725" to "Galaxy A72", "SM-A736" to "Galaxy A73 5G",
        "SM-A025" to "Galaxy A02s", "SM-A022" to "Galaxy A02", "SM-A032" to "Galaxy A03 Core", "SM-A035" to "Galaxy A03", "SM-A037" to "Galaxy A03s",
        "SM-A045" to "Galaxy A04", "SM-A047" to "Galaxy A04s", "SM-A055" to "Galaxy A05", "SM-A057" to "Galaxy A05s", "SM-A065" to "Galaxy A06",
        // Galaxy M
        "SM-M215" to "Galaxy M21", "SM-M315" to "Galaxy M31", "SM-M317" to "Galaxy M31s", "SM-M325" to "Galaxy M32", "SM-M336" to "Galaxy M33 5G",
        "SM-M346" to "Galaxy M34 5G", "SM-M515" to "Galaxy M51", "SM-M526" to "Galaxy M52 5G", "SM-M536" to "Galaxy M53 5G",
        // Планшеты
        "SM-X200" to "Galaxy Tab A8", "SM-X205" to "Galaxy Tab A8", "SM-X110" to "Galaxy Tab A9", "SM-X115" to "Galaxy Tab A9",
        "SM-X210" to "Galaxy Tab A9+", "SM-X216" to "Galaxy Tab A9+", "SM-X700" to "Galaxy Tab S8", "SM-X800" to "Galaxy Tab S8+",
        "SM-X900" to "Galaxy Tab S8 Ultra", "SM-X710" to "Galaxy Tab S9", "SM-X810" to "Galaxy Tab S9+", "SM-X910" to "Galaxy Tab S9 Ultra",
        "SM-X510" to "Galaxy Tab S9 FE", "SM-X610" to "Galaxy Tab S9 FE+", "SM-P610" to "Galaxy Tab S6 Lite", "SM-P620" to "Galaxy Tab S6 Lite",
        "SM-T500" to "Galaxy Tab A7", "SM-T505" to "Galaxy Tab A7", "SM-T220" to "Galaxy Tab A7 Lite", "SM-T225" to "Galaxy Tab A7 Lite",
    )

    private val brandNames = mapOf(
        "samsung" to "Samsung", "xiaomi" to "Xiaomi", "redmi" to "Redmi", "poco" to "POCO", "honor" to "Honor",
        "huawei" to "Huawei", "google" to "Google", "oneplus" to "OnePlus", "oppo" to "OPPO", "vivo" to "vivo",
        "realme" to "realme", "motorola" to "Motorola", "nokia" to "Nokia", "hmd global" to "Nokia", "sony" to "Sony",
        "asus" to "ASUS", "lenovo" to "Lenovo", "tecno" to "TECNO", "infinix" to "Infinix", "itel" to "itel",
        "zte" to "ZTE", "nubia" to "nubia", "meizu" to "Meizu", "lge" to "LG", "lg" to "LG", "nothing" to "Nothing",
        "fairphone" to "Fairphone", "blackview" to "Blackview", "doogee" to "DOOGEE", "ulefone" to "Ulefone",
        "umidigi" to "UMIDIGI", "cubot" to "Cubot", "oukitel" to "OUKITEL", "tcl" to "TCL", "alcatel" to "Alcatel",
    )

    private val codeLike = Regex("^[0-9A-Z]{6,}$")
    private val marketWords = Regex("(?i)(xiaomi|redmi|poco|\\bmi\\b|honor|huawei|galaxy|oppo|realme|vivo|oneplus)")

    /**
     * Производитель + модель без повторов («Xiaomi Xiaomi 14» → «Xiaomi 14»), коды Samsung — названиями.
     * [deviceName] — имя из настроек системы: подставляется, если модель — непонятный код, а имя похоже на название.
     */
    fun marketingName(manufacturer: String, model: String, deviceName: String? = null): String {
        val maker = manufacturer.trim()
        val rawModel = model.trim().replace('_', ' ').replace(Regex("\\s+"), " ")
        val brand = brandNames[maker.lowercase(Locale.ROOT)] ?: maker.replaceFirstChar { it.titlecase(Locale.ROOT) }
        if (deviceName != null && codeLike.matches(rawModel) && rawModel.count { it.isDigit() } >= 4 && marketWords.containsMatchIn(deviceName)) {
            val name = deviceName.trim()
            val keepAsIs = brand.isEmpty() || name.lowercase(Locale.ROOT).startsWith(brand.lowercase(Locale.ROOT)) ||
                Regex("(?i)^(redmi|poco)").containsMatchIn(name)
            return if (keepAsIs) name else "$brand $name"
        }
        if (maker.equals("samsung", ignoreCase = true) || rawModel.startsWith("SM-", ignoreCase = true)) {
            samsungName(rawModel)?.let { return "Samsung $it" }
        }
        if (rawModel.isEmpty()) return brand.ifEmpty { "Android-устройство" }
        if (brand.isEmpty()) return rawModel
        // Модель уже начинается с бренда или суббренда (Redmi, POCO у Xiaomi; «Pixel» у Google — оставляем бренд).
        val lowerModel = rawModel.lowercase(Locale.ROOT)
        if (lowerModel.startsWith(brand.lowercase(Locale.ROOT))) return rawModel.replaceFirstChar { it.titlecase(Locale.ROOT) }
        val subBrands = listOf("redmi", "poco", "galaxy", "honor", "nova", "mate")
        if (maker.equals("xiaomi", ignoreCase = true) && subBrands.take(2).any { lowerModel.startsWith(it) }) {
            return rawModel.replaceFirstChar { it.titlecase(Locale.ROOT) }.replaceFirst(Regex("^(?i)poco"), "POCO")
        }
        return "$brand $rawModel"
    }

    /** «SM-S928B» → «Galaxy S24 Ultra»; null — неизвестный код. */
    fun samsungName(model: String): String? {
        val code = model.uppercase(Locale.ROOT).substringBefore('/').trim()
        if (!code.startsWith("SM-")) return null
        val base = Regex("^(SM-[A-Z]\\d{3})").find(code)?.groupValues?.get(1) ?: return null
        return samsung[base]
    }
}
