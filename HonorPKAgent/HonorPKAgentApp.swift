import SwiftUI
import Darwin

@main
struct HonorPKAgentApp: App {
    @StateObject private var settings = AppSettings()
    @StateObject private var store = ChatStore()
    @Environment(\.scenePhase) private var scenePhase

    var body: some Scene {
        WindowGroup {
            Group {
                if DeviceCompatibility.isSupported {
                    ChatRootView()
                } else {
                    VStack(spacing: 20) {
                        HonorMark().frame(width: 70, height: 70)
                        Text("Honor PK Агент").font(.title.bold())
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
                store.systemInstruction = settings.customInstructions
                if !settings.apiKeyOverride.isEmpty { store.updateAPIKey(settings.apiKeyOverride) }
                if ProcessInfo.processInfo.arguments.contains("-UITestWelcome") {
                    store.clearAllChats()
                    settings.appearance = .dark
                } else if ProcessInfo.processInfo.arguments.contains("-UITestDemo") {
                    store.clearAllChats()
                    settings.appearance = .dark
                    var answer = ChatMessage(role: .assistant, content: "Привет! У меня всё отлично, спасибо, что спросил. А как твои дела?")
                    answer.reasoning = "Это демонстрационный текст для проверки раскрывающейся панели интерфейса."
                    answer.reasoningSeconds = 1
                    let chat = Conversation(title: "Приветствие", messages: [ChatMessage(role: .user, content: "Привет, как твои дела?"), answer])
                    var pinned = Conversation(title: "Идеи для проекта")
                    pinned.pinned = true
                    store.conversations = [chat, pinned]
                    store.selectedConversationID = chat.id
                }
            }
            .onChange(of: settings.customInstructions) { store.systemInstruction = $0 }
            .onChange(of: settings.apiKeyOverride) { store.updateAPIKey($0.isEmpty ? DeepSeekConfiguration.bundled.apiKey : $0) }
            .onChange(of: scenePhase) { phase in
                if phase != .active { store.persistNow() }
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
        guard identifier.hasPrefix("iPhone"), identifier != "iPhone14,6",
              let generation = Int(identifier.dropFirst(6).split(separator: ",").first ?? "") else { return false }
        return generation >= 14
        #endif
    }
}
