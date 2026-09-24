import Foundation
import UserNotifications
import UIKit

/// Уведомления о готовом ответе, когда приложение свёрнуто (пункт 36).
///
/// Раньше фреймворк UserNotifications вообще не был подключён, поэтому уведомлений
/// не существовало. Здесь — разрешение, показ баннера и понятное тело уведомления.
@MainActor
final class NotificationCenterService: NSObject, UNUserNotificationCenterDelegate {
    static let shared = NotificationCenterService()

    private var authorized = false
    /// Настройка «Уведомления о готовом ответе». Сервис проверяет её сам,
    /// а не полагается на вызывающий код: иначе уведомление могло прийти
    /// при выключенном переключателе.
    var isEnabled = false {
        didSet {
            if isEnabled { configure() }
        }
    }

    func configure() {
        UNUserNotificationCenter.current().delegate = self
        UNUserNotificationCenter.current().requestAuthorization(options: [.alert, .sound, .badge]) { [weak self] granted, _ in
            Task { @MainActor in self?.authorized = granted }
        }
    }

    /// Проверить и при необходимости запросить разрешение, дождавшись ответа системы.
    ///
    /// `configure()` запрашивает разрешение асинхронно и результат не возвращает.
    /// Из-за этого приложение не знало, разрешены ли уведомления, и не могло честно
    /// сказать об этом пользователю. Здесь ответ системы дожидается.
    @discardableResult
    func ensureNotifications() async -> Bool {
        UNUserNotificationCenter.current().delegate = self
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

    /// Показывать баннер даже при активном приложении — так ответ не теряется,
    /// если пользователь ушёл в другой чат или свернул окно.
    nonisolated func userNotificationCenter(_ center: UNUserNotificationCenter,
                                            willPresent notification: UNNotification,
                                            withCompletionHandler completionHandler: @escaping (UNNotificationPresentationOptions) -> Void) {
        completionHandler([.banner, .sound])
    }

    /// Вызывается после завершения ответа. Уведомление показывается только если
    /// приложение не на переднем плане.
    func notifyAnswerReady(_ text: String) {
        guard isEnabled else { return }
        let trimmed = text.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty else { return }
        guard UIApplication.shared.applicationState != .active else { return }

        let content = UNMutableNotificationContent()
        content.title = "Honer AI"
        content.body = Self.preview(trimmed)
        content.sound = .default
        content.threadIdentifier = "honer.answers"

        let request = UNNotificationRequest(identifier: UUID().uuidString,
                                           content: content,
                                           trigger: nil)
        UNUserNotificationCenter.current().add(request, withCompletionHandler: nil)
    }

    private static func preview(_ text: String) -> String {
        // Убираем разметку, чтобы в баннере был читаемый текст.
        var value = text
        for token in ["#", "*", "`", ">", "|", "_"] {
            value = value.replacingOccurrences(of: token, with: "")
        }
        value = value.replacingOccurrences(of: "\n", with: " ")
        value = value.split(separator: " ").joined(separator: " ")
        return String(value.prefix(160))
    }
}
