import Combine
import Foundation
import SwiftUI
import UniformTypeIdentifiers

/// Настройки приложения в виде словаря: для резервной копии и восстановления
/// после переустановки.
extension AppSettings {
    func exportSnapshot() -> [String: String] {
        [
            "appearance": appearance.rawValue, "language": language.rawValue,
            "fontScale": String(fontScale), "voiceIdentifier": voiceIdentifier,
            "speechLanguage": speechLanguage, "voiceGender": voiceGender,
            "autoRead": String(autoRead), "displayName": displayName,
            "voiceRate": String(voiceRate), "autoDeleteDays": String(autoDeleteDays),
            "notificationsEnabled": String(notificationsEnabled),
            "crossChatMemoryEnabled": String(crossChatMemoryEnabled),
            "stickersEnabled": String(stickersEnabled), "clonedVoiceID": clonedVoiceID,
            "useClonedVoice": String(useClonedVoice), "personalVoiceEnglish": String(personalVoiceEnglish),
            "birthday": birthday
        ]
    }

    func applySnapshot(_ values: [String: String]) {
        if let value = values["appearance"].flatMap(AppAppearance.init(rawValue:)) { appearance = value }
        if let value = values["language"].flatMap(AppLanguage.init(rawValue:)) { language = value }
        if let value = values["fontScale"].flatMap(Double.init) { fontScale = min(1.4, max(0.85, value)) }
        if let value = values["voiceIdentifier"] { voiceIdentifier = value }
        if let value = values["speechLanguage"], !value.isEmpty { speechLanguage = value }
        if let value = values["voiceGender"], ["male", "female"].contains(value) { voiceGender = value }
        if let value = values["autoRead"].flatMap(Bool.init) { autoRead = value }
        if let value = values["displayName"], !value.isEmpty { displayName = value }
        if let value = values["voiceRate"].flatMap(Double.init) { voiceRate = min(1.8, max(0.4, value)) }
        if let value = values["autoDeleteDays"].flatMap(Int.init) { autoDeleteDays = value }
        if let value = values["notificationsEnabled"].flatMap(Bool.init) { notificationsEnabled = value }
        if let value = values["crossChatMemoryEnabled"].flatMap(Bool.init) { crossChatMemoryEnabled = value }
        if let value = values["stickersEnabled"].flatMap(Bool.init) { stickersEnabled = value }
        if let value = values["clonedVoiceID"], !value.isEmpty { clonedVoiceID = value }
        if let value = values["useClonedVoice"].flatMap(Bool.init) { useClonedVoice = value }
        if let value = values["personalVoiceEnglish"].flatMap(Bool.init) { personalVoiceEnglish = value }
        if let value = values["birthday"], !value.isEmpty { birthday = value }
    }

    /// Копия настроек в Keychain: она переживает удаление приложения.
    func mirrorToKeychain() {
        guard let data = try? JSONEncoder().encode(exportSnapshot()),
              let text = String(data: data, encoding: .utf8) else { return }
        KeychainStore.set(text, for: "settingsSnapshot")
    }

    /// После переустановки настройки возвращаются из Keychain сами.
    /// - Returns: `true`, если настройки восстановлены.
    @discardableResult
    func restoreFromKeychainIfFreshInstall() -> Bool {
        guard UserDefaults.standard.object(forKey: "honor.installMarker") == nil else { return false }
        UserDefaults.standard.set(true, forKey: "honor.installMarker")
        guard let text = KeychainStore.get("settingsSnapshot"), let data = text.data(using: .utf8),
              let values = try? JSONDecoder().decode([String: String].self, from: data) else { return false }
        applySnapshot(values)
        return true
    }
}

/// Автоматическая резервная копия в выбранную папку (iCloud Drive, «Файлы»).
@MainActor
final class BackupService: ObservableObject {
    static let shared = BackupService()
    static let fileName = "Honer AI — резервная копия.json"

    @Published var autoBackup: Bool {
        didSet { UserDefaults.standard.set(autoBackup, forKey: "honor.autoBackup") }
    }
    @Published private(set) var folderName: String?
    @Published private(set) var lastBackupAt: Date?
    @Published private(set) var lastError: String?
    @Published private(set) var isWorking = false

    private init() {
        autoBackup = UserDefaults.standard.object(forKey: "honor.autoBackup") as? Bool ?? true
        folderName = UserDefaults.standard.string(forKey: "honor.backupFolderName")
        lastBackupAt = UserDefaults.standard.object(forKey: "honor.lastBackupAt") as? Date
    }

    private var bookmark: Data? {
        if let data = UserDefaults.standard.data(forKey: "honor.backupBookmark") { return data }
        return KeychainStore.get("backupBookmark").flatMap { Data(base64Encoded: $0) }
    }

    /// Запомнить папку для копий.
    func setFolder(_ url: URL) {
        let access = url.startAccessingSecurityScopedResource()
        defer { if access { url.stopAccessingSecurityScopedResource() } }
        do {
            let data = try url.bookmarkData()
            UserDefaults.standard.set(data, forKey: "honor.backupBookmark")
            KeychainStore.set(data.base64EncodedString(), for: "backupBookmark")
            folderName = url.lastPathComponent
            UserDefaults.standard.set(folderName, forKey: "honor.backupFolderName")
            lastError = nil
        } catch {
            lastError = "Не удалось запомнить папку: \(error.localizedDescription)"
        }
    }

    private func resolveFolder() -> URL? {
        guard let bookmark else { return nil }
        var stale = false
        guard let url = try? URL(resolvingBookmarkData: bookmark, bookmarkDataIsStale: &stale) else { return nil }
        if stale { setFolder(url) }
        return url
    }

    /// Сделать копию сейчас.
    func backupNow(store: ChatStore) async {
        guard !isWorking else { return }
        guard let folder = resolveFolder() else {
            lastError = "Выберите папку для резервных копий."
            return
        }
        isWorking = true
        defer { isWorking = false }
        do {
            let exported = try await store.exportDataAsync()
            let access = folder.startAccessingSecurityScopedResource()
            defer { if access { folder.stopAccessingSecurityScopedResource() } }
            let target = folder.appendingPathComponent(Self.fileName)
            let coordinator = NSFileCoordinator()
            var coordinationError: NSError?
            var writeError: Error?
            coordinator.coordinate(writingItemAt: target, options: .forReplacing, error: &coordinationError) { url in
                do {
                    if FileManager.default.fileExists(atPath: url.path) { try FileManager.default.removeItem(at: url) }
                    try FileManager.default.copyItem(at: exported, to: url)
                } catch { writeError = error }
            }
            try? FileManager.default.removeItem(at: exported)
            if let error = coordinationError ?? writeError { throw error }
            lastBackupAt = Date()
            UserDefaults.standard.set(lastBackupAt, forKey: "honor.lastBackupAt")
            KeychainStore.set("1", for: "hadBackup")
            lastError = nil
        } catch {
            lastError = "Копия не сохранилась: \(error.localizedDescription)"
        }
    }

    /// Копия при сворачивании приложения — не чаще раза в 5 минут.
    func backupIfNeeded(store: ChatStore) {
        guard autoBackup, bookmark != nil else { return }
        if let last = lastBackupAt, Date().timeIntervalSince(last) < 300 { return }
        Task { await backupNow(store: store) }
    }

    /// Была ли когда-то сделана копия (подсказка после переустановки).
    var hadBackupBefore: Bool { KeychainStore.get("hadBackup") == "1" }
}

/// Раздел «Синхронизация и резервная копия».
struct BackupSection: View {
    @EnvironmentObject private var settings: AppSettings
    @EnvironmentObject private var store: ChatStore
    @ObservedObject private var backup = BackupService.shared
    @State private var choosingFolder = false

    var body: some View {
        Section {
            Toggle(settings.text("Автоматическая резервная копия", "Automatic backup"), isOn: $backup.autoBackup)
                .accessibilityIdentifier("backup.auto")
            Button { choosingFolder = true } label: {
                HStack {
                    Label(settings.text("Папка для копий", "Backup folder"), systemImage: "folder")
                    Spacer()
                    Text(backup.folderName ?? settings.text("не выбрана", "not chosen"))
                        .font(.caption).foregroundStyle(.secondary)
                }
            }
            .accessibilityIdentifier("backup.folder")
            Button {
                Task { await backup.backupNow(store: store) }
            } label: {
                HStack {
                    Label(settings.text("Сделать копию сейчас", "Back up now"), systemImage: "arrow.clockwise.icloud")
                    if backup.isWorking { Spacer(); ProgressView() }
                }
            }
            .disabled(backup.folderName == nil || backup.isWorking)
            .accessibilityIdentifier("backup.now")
            if let last = backup.lastBackupAt {
                LabeledContent(settings.text("Последняя копия", "Last backup"),
                               value: last.formatted(date: .abbreviated, time: .shortened))
                    .accessibilityIdentifier("backup.last")
            }
            if let error = backup.lastError {
                Text(error).font(.footnote).foregroundStyle(.orange)
            }
        } header: {
            Text(settings.text("Синхронизация и резервная копия", "Sync and backup"))
        } footer: {
            Text(settings.text("Выберите папку в iCloud Drive — туда будут сохраняться все чаты, память, инструкции и настройки. После переустановки приложения нажмите на первом экране «Восстановить из резервной копии». Настройки и ключи возвращаются сами — они хранятся в защищённом хранилище iPhone.",
                               "Pick a folder in iCloud Drive for chats, memory, instructions and settings. After reinstalling, tap “Restore from backup” on the first screen."))
        }
        .fileImporter(isPresented: $choosingFolder, allowedContentTypes: [.folder]) { result in
            if case .success(let url) = result {
                backup.setFolder(url)
                Task { await backup.backupNow(store: store) }
            }
        }
    }
}
