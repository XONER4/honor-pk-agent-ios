import SwiftUI
import Darwin

@main
struct HonorPKAgentApp: App {
    @StateObject private var settings: AppSettings
    @StateObject private var store: ChatStore
    @State private var didConfigure = false
    /// Начало текущей сессии — для статистики времени в приложении.
    @State private var sessionStartedAt: Date?
    @Environment(\.scenePhase) private var scenePhase
    @UIApplicationDelegateAdaptor(HonerAppDelegate.self) private var appDelegate

    init() {
        #if DEBUG
        if UITestSupport.isEnabled {
            let test = UITestSupport.makeEnvironment()
            _settings = StateObject(wrappedValue: test.settings)
            _store = StateObject(wrappedValue: test.store)
            return
        }
        #endif
        let settings = AppSettings()
        // После переустановки настройки возвращаются из защищённого хранилища iPhone.
        settings.restoreFromKeychainIfFreshInstall()
        _settings = StateObject(wrappedValue: settings)
        _store = StateObject(wrappedValue: ChatStore(loadHistoryAsynchronously: true))
    }

    var body: some Scene {
        WindowGroup {
            Group {
                if DeviceCompatibility.isSupported {
                    if settings.completedOnboarding {
                        // Родительский контроль: экран блокировки по лимиту и тихим часам, учёт времени.
                        ChatRootView().parentalGate()
                    } else {
                        OnboardingView()
                    }
                } else {
                    VStack(spacing: 20) {
                        HonorMark().frame(width: 70, height: 70)
                        Text("Honer AI").font(.title.bold())
                        Text("Приложение доступно на iPhone 13 и более новых моделях.")
                            .multilineTextAlignment(.center)
                    }.padding(32)
                }
            }
            .environmentObject(store)
            .environmentObject(settings)
            .preferredColorScheme(settings.preferredColorScheme)
            .tint(Color(red: 0.40, green: 0.60, blue: 0.98))
            .onAppear {
                guard !didConfigure else { return }
                didConfigure = true
                // Путь к фото профиля хранится в настройках как абсолютный. iOS меняет
                // идентификатор песочницы при обновлении приложения, и после установки
                // новой версии фото «терялось»: файл лежал на месте, а путь в настройках
                // указывал в несуществующую папку. Здесь путь пересчитывается.
                settings.repairProfilePhotoPath()
                store.settingsSnapshotProvider = { [weak settings] in settings?.exportSnapshot() ?? [:] }
                store.settingsRestorer = { [weak settings] values in settings?.applySnapshot(values) }
                store.profileName = settings.displayName
                store.profileBirthday = settings.birthday
                store.accountCreatedAt = settings.accountCreatedAt
                store.setResponseLanguage(settings.language.rawValue)
                // Автоудаление старых чатов по настройке (пункт 40).
                if settings.autoDeleteDays > 0 {
                    store.purgeOldChats(olderThan: settings.autoDeleteDays)
                }
                sessionStartedAt = Date()
                // Сервис проверяет настройку сам — здесь только начальная синхронизация.
                NotificationCenterService.shared.isEnabled = settings.notificationsEnabled
                if settings.notificationsEnabled {
                    Task { await NotificationCenterService.shared.ensureNotifications() }
                }
                #if DEBUG
                UITestSupport.seedIfNeeded(store: store)
                #endif
            }
            .onChange(of: settings.displayName) { store.profileName = $0 }
            .onChange(of: settings.birthday) { store.profileBirthday = $0 }
            .onChange(of: settings.language) { (language: AppLanguage) in store.setResponseLanguage(language.rawValue) }
            .onChange(of: settings.notificationsEnabled) { (enabled: Bool) in
                NotificationCenterService.shared.isEnabled = enabled
                if enabled {
                    // Ждём ответ системы: без этого переключатель мог быть включён,
                    // а разрешения не было — и уведомления молча не приходили.
                    Task { await NotificationCenterService.shared.ensureNotifications() }
                }
            }
            .onReceive(NotificationCenter.default.publisher(for: .honerOpenChat)) { note in
                // Нажали уведомление — открываем чат с ответом.
                if let id = note.object as? UUID { store.selectChat(id: id) }
            }
            .onChange(of: settings.autoDeleteDays) { (days: Int) in
                if days > 0 { store.purgeOldChats(olderThan: days) }
            }
            .onChange(of: scenePhase) { phase in
                // Считаем время, проведённое в приложении (пункт 30 «Статистика»).
                if phase == .active {
                    sessionStartedAt = Date()
                    PermissionsCenter.shared.updateLocation()
                    store.applyPendingBackgroundAnswers()
                } else {
                    if let started = sessionStartedAt {
                        store.addSessionTime(Date().timeIntervalSince(started))
                        sessionStartedAt = nil
                    }
                    store.persistNow()
                    settings.mirrorToKeychain()
                    if phase == .background { BackupService.shared.backupIfNeeded(store: store) }
                }
            }
        }
    }
}

enum DeviceCompatibility {
    static var isSupported: Bool {
        #if targetEnvironment(simulator)
        return true
        #else
        var info = utsname()
        uname(&info)
        let identifier = withUnsafePointer(to: &info.machine) {
            $0.withMemoryRebound(to: CChar.self, capacity: 256) { String(cString: $0) }
        }
        return supports(identifier: identifier)
        #endif
    }

    static func supports(identifier: String) -> Bool {
        guard identifier.hasPrefix("iPhone"), identifier != "iPhone14,6",
              let generation = Int(identifier.dropFirst(6).split(separator: ",").first ?? "") else { return false }
        return generation >= 14
    }
}
