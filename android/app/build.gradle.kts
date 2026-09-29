import java.security.SecureRandom
import java.util.Base64

import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
}

// Ключ DeepSeek и данные подписи не хранятся в репозитории: они приходят из
// переменных окружения (сборка на GitHub) или из local.properties (локальная сборка).
val localProps = Properties().apply {
    val file = rootProject.file("local.properties")
    if (file.exists()) file.inputStream().use { load(it) }
}
fun secret(name: String): String = System.getenv(name) ?: localProps.getProperty(name) ?: ""

/** Секрет → (секрет XOR маска, маска) в Base64; маска случайная для каждой сборки. */
fun maskSecret(value: String): Pair<String, String> {
    val bytes = value.toByteArray(Charsets.UTF_8)
    val mask = ByteArray(bytes.size).also { SecureRandom().nextBytes(it) }
    val masked = ByteArray(bytes.size) { (bytes[it].toInt() xor mask[it].toInt()).toByte() }
    val encoder = Base64.getEncoder()
    return encoder.encodeToString(masked) to encoder.encodeToString(mask)
}

val appVersionCode = (project.findProperty("versionCode") as String?)?.toIntOrNull() ?: 104400
val appVersionName = (project.findProperty("versionName") as String?) ?: "10.44.0"

android {
    namespace = "com.honerai.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.honerai.app"
        minSdk = 24
        targetSdk = 35
        versionCode = appVersionCode
        versionName = appVersionName
        vectorDrawables { useSupportLibrary = true }
        // Ключ не лежит в APK открытой строкой: XOR со случайной маской, разной в каждой сборке
        // (поиск «sk-» по dex ничего не находит). Полностью ключ скрывает только сервер-прокси Honer Cloud.
        val (maskedKey, keyMask) = maskSecret(secret("DEEPSEEK_API_KEY"))
        buildConfigField("String", "DEEPSEEK_KEY_A", "\"$maskedKey\"")
        buildConfigField("String", "DEEPSEEK_KEY_B", "\"$keyMask\"")
        buildConfigField("String", "UPDATE_REPOSITORY", "\"XONER4/honor-pk-agent-ios\"")
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        create("release") {
            val path = secret("ANDROID_KEYSTORE_PATH")
            if (path.isNotEmpty() && file(path).exists()) {
                storeFile = file(path)
                storePassword = secret("ANDROID_KEYSTORE_PASSWORD")
                keyAlias = secret("ANDROID_KEY_ALIAS")
                keyPassword = secret("ANDROID_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            val release = signingConfigs.getByName("release")
            signingConfig = if (release.storeFile != null) release else signingConfigs.getByName("debug")
        }
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
    }

    compileOptions {
        isCoreLibraryDesugaringEnabled = true
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        resources.excludes += setOf("/META-INF/{AL2.0,LGPL2.1}", "META-INF/DEPENDENCIES", "META-INF/LICENSE*", "META-INF/NOTICE*")
    }

    testOptions { unitTests.isReturnDefaultValues = true }
}

dependencies {
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.1.3")

    val composeBom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material3:material3-window-size-class")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.animation:animation")
    implementation("androidx.compose.foundation:foundation")
    debugImplementation("androidx.compose.ui:ui-tooling")

    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-process:2.8.7")
    implementation("androidx.navigation:navigation-compose:2.8.5")
    implementation("androidx.work:work-runtime-ktx:2.10.0")
    implementation("androidx.profileinstaller:profileinstaller:1.4.1")
    implementation("androidx.documentfile:documentfile:1.0.1")
    implementation("androidx.exifinterface:exifinterface:1.3.7")
    implementation("androidx.core:core-splashscreen:1.0.1")
    implementation("androidx.webkit:webkit:1.12.1")

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.jsoup:jsoup:1.18.3")
    implementation("io.coil-kt:coil-compose:2.7.0")
    implementation("io.coil-kt:coil-gif:2.7.0")
    implementation("io.coil-kt:coil-video:2.7.0")
    implementation("androidx.media3:media3-exoplayer:1.5.1")
    implementation("androidx.media3:media3-ui:1.5.1")
    implementation("androidx.media3:media3-transformer:1.5.1")
    implementation("androidx.media3:media3-effect:1.5.1")
    implementation("androidx.media3:media3-common:1.5.1")
    implementation("com.tom-roush:pdfbox-android:2.0.27.0")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
    testImplementation("org.json:json:20240303")
}
