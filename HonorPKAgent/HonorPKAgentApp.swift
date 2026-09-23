import SwiftUI
import Darwin

@main
struct HonorPKAgentApp: App {
    @StateObject private var settings: AppSettings
    @StateObject private var store: ChatStore
    @State private var didConfigure = false
    @Environment(\.scenePhase) private var scenePhase

    init() {
        #if DEBUG
        if UITestSupport.isEnabled {
            let test = UITestSupport.makeEnvironment()
            _settings = StateObject(wrappedValue: test.settings)
            _store = StateObject(wrappedValue: test.store)
            return
        }
        #endif
        _settings = StateObject(wrappedValue: AppSettings())
        _store = StateObject(wrappedValue: ChatStore(loadHistoryAsynchronously: true))
    }

    var body: some Scene {
        WindowGroup {
            Group {
                if DeviceCompatibility.isSupported {
                    if settings.completedOnboarding {
                        ChatRootView()
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
                store.systemInstruction = settings.customInstructions
                store.profileName = settings.displayName
                #if DEBUG
                UITestSupport.seedIfNeeded(store: store)
                #endif
            }
            .onChange(of: settings.customInstructions) { store.systemInstruction = $0 }
            .onChange(of: settings.displayName) { store.profileName = $0 }
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
        return supports(identifier: identifier)
        #endif
    }

    static func supports(identifier: String) -> Bool {
        guard identifier.hasPrefix("iPhone"), identifier != "iPhone14,6",
              let generation = Int(identifier.dropFirst(6).split(separator: ",").first ?? "") else { return false }
        return generation >= 14
    }
}
