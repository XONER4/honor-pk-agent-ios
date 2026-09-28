import Foundation
import UIKit

extension Notification.Name {
    /// Фоновый ответ готов: его нужно показать в чате.
    static let honerBackgroundAnswerReady = Notification.Name("honer.backgroundAnswerReady")
    /// Нажали уведомление: открыть чат.
    static let honerOpenChat = Notification.Name("honer.openChat")
}

/// Ответ, догруженный в фоне, пока приложение было свёрнуто или экран заблокирован.
struct PendingBackgroundAnswer: Codable, Equatable {
    var chatID: UUID
    var messageID: UUID
    var chatTitle: String
    var content: String
}

/// Догрузка ответа, когда приложение свёрнуто.
///
/// iOS замораживает свёрнутое приложение через несколько секунд, и потоковый ответ
/// обрывался — уведомление о готовом ответе не приходило никогда. Теперь, если
/// выделенного системой фонового времени не хватает, запрос передаётся системной
/// фоновой загрузке: она доводит его до конца даже при заблокированном экране,
/// а приложение сохраняет ответ и показывает уведомление.
final class BackgroundAnswerService: NSObject, URLSessionDataDelegate, @unchecked Sendable {
    static let shared = BackgroundAnswerService()
    static let identifier = "com.honorpk.agent.background-answers"

    private let lock = NSLock()
    private var buffers: [Int: Data] = [:]
    private var completionHandler: (() -> Void)?

    private lazy var session: URLSession = {
        let configuration = URLSessionConfiguration.background(withIdentifier: Self.identifier)
        configuration.sessionSendsLaunchEvents = true
        configuration.isDiscretionary = false
        configuration.timeoutIntervalForResource = 30 * 60
        return URLSession(configuration: configuration, delegate: self, delegateQueue: nil)
    }()

    /// Файл с готовыми фоновыми ответами, которые ещё не показаны в чате.
    static var pendingURL: URL {
        FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
            .appendingPathComponent("HonorPK", isDirectory: true)
            .appendingPathComponent("background-answers.json")
    }

    /// Система разбудила приложение ради фоновой загрузки.
    func reconnect(completion: @escaping () -> Void) {
        lock.lock(); completionHandler = completion; lock.unlock()
        _ = session
    }

    /// Запустить запрос в фоне. Тело запроса передаётся файлом — так требует фоновая загрузка.
    func start(request: URLRequest, chatID: UUID, messageID: UUID, chatTitle: String) -> Bool {
        guard let body = request.httpBody else { return false }
        let folder = FileManager.default.urls(for: .cachesDirectory, in: .userDomainMask)[0]
        let file = folder.appendingPathComponent("honer-bg-\(messageID.uuidString).json")
        do { try body.write(to: file, options: .atomic) } catch { return false }
        var upload = request
        upload.httpBody = nil
        upload.setValue("application/json", forHTTPHeaderField: "Accept")
        let task = session.uploadTask(with: upload, fromFile: file)
        let meta = PendingBackgroundAnswer(chatID: chatID, messageID: messageID, chatTitle: chatTitle, content: "")
        task.taskDescription = (try? JSONEncoder().encode(meta)).flatMap { String(data: $0, encoding: .utf8) }
        task.resume()
        return true
    }

    func urlSession(_ session: URLSession, dataTask: URLSessionDataTask, didReceive data: Data) {
        lock.lock(); buffers[dataTask.taskIdentifier, default: Data()].append(data); lock.unlock()
    }

    func urlSession(_ session: URLSession, task: URLSessionTask, didCompleteWithError error: Error?) {
        lock.lock(); let data = buffers.removeValue(forKey: task.taskIdentifier) ?? Data(); lock.unlock()
        guard let description = task.taskDescription, let metaData = description.data(using: .utf8),
              var meta = try? JSONDecoder().decode(PendingBackgroundAnswer.self, from: metaData) else { return }
        let content = Self.content(from: data)
        guard error == nil, !content.isEmpty else { return }
        meta.content = content
        Self.appendPending(meta)
        let ready = meta
        Task { @MainActor in
            NotificationCenter.default.post(name: .honerBackgroundAnswerReady, object: nil)
            NotificationCenterService.shared.notifyAnswerReady(ready.content, chatTitle: ready.chatTitle, chatID: ready.chatID)
        }
    }

    func urlSessionDidFinishEvents(forBackgroundURLSession session: URLSession) {
        lock.lock(); let handler = completionHandler; completionHandler = nil; lock.unlock()
        DispatchQueue.main.async { handler?() }
    }

    /// Текст ответа из обычного (не потокового) ответа сервиса.
    static func content(from data: Data) -> String {
        guard let json = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
              let choices = json["choices"] as? [[String: Any]],
              let message = choices.first?["message"] as? [String: Any],
              let content = message["content"] as? String else { return "" }
        return content.trimmingCharacters(in: .whitespacesAndNewlines)
    }

    static func appendPending(_ answer: PendingBackgroundAnswer) {
        var list = loadPending()
        list.removeAll { $0.messageID == answer.messageID }
        list.append(answer)
        try? FileManager.default.createDirectory(at: pendingURL.deletingLastPathComponent(), withIntermediateDirectories: true)
        try? JSONEncoder().encode(list).write(to: pendingURL, options: .atomic)
    }

    static func loadPending() -> [PendingBackgroundAnswer] {
        guard let data = try? Data(contentsOf: pendingURL) else { return [] }
        return (try? JSONDecoder().decode([PendingBackgroundAnswer].self, from: data)) ?? []
    }

    static func clearPending() {
        try? FileManager.default.removeItem(at: pendingURL)
    }
}

/// Приложение получает события фоновой загрузки через делегат приложения.
final class HonerAppDelegate: NSObject, UIApplicationDelegate {
    func application(_ application: UIApplication, handleEventsForBackgroundURLSession identifier: String,
                     completionHandler: @escaping () -> Void) {
        guard identifier == BackgroundAnswerService.identifier else { completionHandler(); return }
        BackgroundAnswerService.shared.reconnect(completion: completionHandler)
    }

    func application(_ application: UIApplication,
                     didFinishLaunchingWithOptions launchOptions: [UIApplication.LaunchOptionsKey: Any]? = nil) -> Bool {
        NotificationCenterService.shared.attachDelegate()
        return true
    }
}
