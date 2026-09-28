import Foundation
import UserNotifications
import UIKit

/// Уведомления о готовом ответе, когда приложение свёрнуто или экран заблокирован.
///
/// В уведомлении — название чата, текст ответа (целиком раскрывается долгим нажатием),
/// картинка, если она есть в ответе, и иконка Honer AI. Нажатие открывает этот чат.
@MainActor
final class NotificationCenterService: NSObject, UNUserNotificationCenterDelegate {
    static let shared = NotificationCenterService()

    private var authorized = false
    /// Настройка «Уведомления о готовом ответе». По умолчанию включена.
    var isEnabled = true {
        didSet {
            if isEnabled { configure() }
        }
    }

    /// Включено ли в настройках (читается и когда приложение разбужено в фоне).
    private var enabledInSettings: Bool {
        UserDefaults.standard.object(forKey: "honor.notificationsEnabled") as? Bool ?? true
    }

    func attachDelegate() {
        UNUserNotificationCenter.current().delegate = self
    }

    func configure() {
        attachDelegate()
        UNUserNotificationCenter.current().requestAuthorization(options: [.alert, .sound, .badge]) { [weak self] granted, _ in
            Task { @MainActor in self?.authorized = granted }
        }
    }

    /// Проверить и при необходимости запросить разрешение, дождавшись ответа системы.
    @discardableResult
    func ensureNotifications() async -> Bool {
        attachDelegate()
        let settings = await UNUserNotificationCenter.current().notificationSettings()
        switch settings.authorizationStatus {
        case .authorized, .provisional, .ephemeral:
            authorized = true
        case .notDetermined:
            let granted = (try? await UNUserNotificationCenter.current()
                .requestAuthorization(options: [.alert, .sound, .badge])) ?? false
            authorized = granted
        default:
            authorized = false
        }
        return authorized
    }

    /// Текущее состояние разрешения для понятного текста в настройках.
    func authorizationStatus() async -> UNAuthorizationStatus {
        await UNUserNotificationCenter.current().notificationSettings().authorizationStatus
    }

    /// Показывать баннер даже при активном приложении.
    nonisolated func userNotificationCenter(_ center: UNUserNotificationCenter,
                                            willPresent notification: UNNotification,
                                            withCompletionHandler completionHandler: @escaping (UNNotificationPresentationOptions) -> Void) {
        completionHandler([.banner, .list, .sound])
    }

    /// Нажали уведомление — открыть чат с ответом.
    nonisolated func userNotificationCenter(_ center: UNUserNotificationCenter,
                                            didReceive response: UNNotificationResponse,
                                            withCompletionHandler completionHandler: @escaping () -> Void) {
        let raw = response.notification.request.content.userInfo["chatID"] as? String
        Task { @MainActor in
            if let raw, let id = UUID(uuidString: raw) {
                NotificationCenter.default.post(name: .honerOpenChat, object: id)
            }
            completionHandler()
        }
    }

    /// Ответ готов. Уведомление показывается, только если приложение не на экране.
    func notifyAnswerReady(_ text: String, chatTitle: String = "", chatID: UUID? = nil) {
        guard isEnabled, enabledInSettings else { return }
        let trimmed = text.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty else { return }
        guard UIApplication.shared.applicationState != .active else { return }

        let content = UNMutableNotificationContent()
        content.title = "Honer AI"
        let title = chatTitle.trimmingCharacters(in: .whitespacesAndNewlines)
        if !title.isEmpty { content.subtitle = String(title.prefix(60)) }
        content.body = Self.preview(trimmed)
        content.sound = .default
        content.threadIdentifier = chatID?.uuidString ?? "honer.answers"
        content.interruptionLevel = .active
        content.relevanceScore = 0.8
        if let chatID { content.userInfo = ["chatID": chatID.uuidString] }
        let imageURL = Self.firstImageURL(in: trimmed)

        Task {
            if let imageURL, let attachment = await Self.attachment(for: imageURL) {
                content.attachments = [attachment]
            }
            let request = UNNotificationRequest(identifier: UUID().uuidString, content: content, trigger: nil)
            try? await UNUserNotificationCenter.current().add(request)
        }
    }

    /// Читаемый текст уведомления: без разметки, до 1500 символов (целиком
    /// раскрывается долгим нажатием на уведомление).
    static func preview(_ text: String) -> String {
        var value = SpeechService.sanitizedSpeechText(text)
        if value.isEmpty { value = text }
        value = value.replacingOccurrences(of: "\n{2,}", with: "\n", options: .regularExpression)
        return String(value.prefix(1500))
    }

    /// Первая картинка из ответа (![подпись](ссылка)), но не видео.
    static func firstImageURL(in text: String) -> URL? {
        guard let expression = try? NSRegularExpression(pattern: "!\\[[^\\]]*\\]\\(\\s*(https?://[^\\s)]+)") else { return nil }
        for match in expression.matches(in: text, range: NSRange(text.startIndex..., in: text)) {
            guard let range = Range(match.range(at: 1), in: text), let url = URL(string: String(text[range])) else { continue }
            if !MediaLinks.isVideo(url) { return url }
            if let thumbnail = MediaLinks.videoThumbnail(url) { return thumbnail }
        }
        return nil
    }

    /// Картинка для уведомления: скачивается во временный файл.
    private static func attachment(for url: URL) async -> UNNotificationAttachment? {
        var request = URLRequest(url: url)
        request.timeoutInterval = 12
        guard let (data, response) = try? await URLSession.shared.data(for: request),
              (response as? HTTPURLResponse).map({ (200...299).contains($0.statusCode) }) ?? true,
              data.count < 8 * 1024 * 1024, let image = UIImage(data: data),
              let jpeg = image.jpegData(compressionQuality: 0.85) else { return nil }
        let file = FileManager.default.temporaryDirectory.appendingPathComponent("honer-note-\(UUID().uuidString).jpg")
        do {
            try jpeg.write(to: file)
            return try UNNotificationAttachment(identifier: "image", url: file, options: nil)
        } catch {
            return nil
        }
    }
}
