# Honer AI для Android — архитектура и правила модулей

Приложение — полный перенос iOS-версии Honer AI (папка `../HonorPKAgent`, Swift/SwiftUI) на Android:
Kotlin + Jetpack Compose (Material 3), один модуль `:app`, пакет `com.honerai.app`.
Интерфейс, поведение, тексты (русский по умолчанию + английский), системная инструкция нейросети,
инструменты и формат данных — как на iPhone. Эталон поведения — исходники iOS: читайте их.

## Сборка
- JDK 17: `C:/hw/tools/jdk/jdk-17.0.20.1+1`, Android SDK: `C:/hw/tools/android-sdk` (прописан в `local.properties`).
- Команды (Git Bash, из папки проекта):
  `export JAVA_HOME="C:/hw/tools/jdk/jdk-17.0.20.1+1"; export PATH="$JAVA_HOME/bin:$PATH"; ./gradlew compileDebugKotlin --console=plain -q`
  и для тестов `./gradlew testDebugUnitTest --console=plain -q`.
- minSdk 24 (Android 7.0), targetSdk/compileSdk 35. Зависимости уже подключены в `app/build.gradle.kts`
  (Compose BOM 2024.12.01, Material3, OkHttp 4.12, kotlinx.serialization 1.7, Jsoup, Coil 2.7, Media3 1.5.1,
  WorkManager, pdfbox-android). Новые зависимости НЕ добавлять без крайней необходимости — если нужна,
  напишите об этом в отчёте (координаты), не меняйте build.gradle.kts сами.
- Ключ DeepSeek приходит как `BuildConfig.DEEPSEEK_API_KEY` (в локальной сборке пустой — это нормально).

## Совместимость и производительность (обязательно)
- Должно работать от слабых телефонов (1–2 ГБ ОЗУ, Android 7, 60 Гц, маленький экран 320 dp) до флагманов
  (120/144 Гц, планшеты, складные). `AppContainer.isLowEndDevice` — на слабых меньше анимаций/размытий.
- Никакой тяжёлой работы на главном потоке: сеть, файлы, разбор — `Dispatchers.IO/Default`.
- Списки — `LazyColumn` со стабильными `key`. Compose: избегайте лишних рекомпозиций (remember, derivedStateOf,
  стабильные параметры). Анимации — через Compose animation API; кадровые обновления — `withFrameNanos`.
- Разные экраны: используйте `BoxWithConstraints`/`WindowSizeClass`; на широких экранах (>= 600 dp) — адаптивная раскладка.
- Системные отступы (edge-to-edge): `WindowInsets.safeDrawing`/`imePadding()`.
- Все строки интерфейса двуязычные: `settings.text("Русский", "English")` (см. `AppSettings`).
- Цвета только из `HonerTheme.colors` (как HonorTheme на iOS), тёмная тема по умолчанию.

## Общие объекты (уже есть — НЕ меняйте без необходимости)
- `data/Models.kt` — модели (как Models.swift), `HonerJson`, `newId()`, `IsoInstantSerializer`.
- `core/AppSettings.kt` — настройки (StateFlow), `text(ru, en)`.
- `core/ChatStoreApi.kt` — КОНТРАКТ хранилища чатов (методы и потоки состояния, как ChatStore.swift).
- `core/TypingPacer.kt` — плавная печать ответа (порт StreamPacing.swift), кадры от Choreographer.
- `AppContainer` (`AppContainer.get(context).store / .settings / .isLowEndDevice`), `HonerApp`, `MainActivity`.
- `ui/theme/Theme.kt` — `HonerAppTheme`, `HonerTheme.colors`.
- `device/HonerNotifications.kt` — каналы уведомлений (свой звук `R.raw.honer_notify`).

## Модули и владельцы файлов
Каждый модуль меняет ТОЛЬКО свои файлы. Файлы-заготовки других модулей (помечены «ЗАГОТОВКА»)
можно вызывать, но не менять: их подписи — контракт. Можно создавать новые файлы в своих пакетах.

| Модуль | Пакеты / файлы |
|---|---|
| core — движок чата | `core/*` (кроме AppSettings, TypingPacer, ChatStoreApi), в т.ч. `core/ChatStore.kt` (заменить заглушку) |
| chat — экран чата | `ui/HonerRoot.kt`, `ui/chat/**`, `ui/onboarding/**`, `ui/common/**` |
| render — отображение ответов | `ui/markdown/**`, `ui/tables/**`, `ui/questions/**` |
| settings — настройки | `ui/settings/**`, `ui/help/**`, `ui/parental/**`, `device/ParentalControl.kt` |
| device — устройство | `device/**` (кроме ParentalControl.kt и HonerNotifications.kt), в т.ч. `device/Stubs.kt` |
| games — игры и редактор | `ui/games/**`, `ui/editor/**`, `media/**` |

## Контракты между модулями (подписи в файлах-заготовках)
- render → chat: `MarkdownContent(text, modifier, fontScale, streaming, sources, messageId, isLatest, findQuery, onAnswer)`,
  `ChatTableCards(ids, fontScale)`, `TableEditorScreen(tableId, onClose)`.
- settings → chat: `SettingsScreen(onClose)`, `HelpCenterScreen(onBack)`, `ParentalGate { content }`, объект `ParentalControl`.
- device → chat/core: `SpeechService`, `AttachmentImporter.import()`, `DeviceInfo`, `UpdateManager`,
  службы `GenerationService`, `UpdateScheduler`, приёмники в `device/Stubs.kt`.
- games → chat/core: `GameHub`, `GameScreen`, `PhotoEditorScreen`, `VideoEditorScreen`, `ImageEditing`.
- core → все: `ChatStoreApi` (реализация `ChatStore(context, settings)`).

## Качество
- Код и комментарии в стиле проекта (комментарии по-русски, коротко, «почему»).
- Каждый модуль пишет модульные тесты в `app/src/test/java/com/honerai/app/<модуль>/` (JUnit4, без Android-зависимостей
  или с `unitTests.isReturnDefaultValues = true`) для своей логики.
- Перед сдачей: `./gradlew compileDebugKotlin` и `./gradlew testDebugUnitTest` без ошибок.
- Никаких заглушек и «TODO» в готовом коде: всё, что обещано, работает. Если что-то невозможно на Android —
  честно сделайте лучший вариант и опишите ограничение в отчёте.
