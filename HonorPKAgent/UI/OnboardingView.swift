import SwiftUI
import UniformTypeIdentifiers

/// Первый экран: знакомство. Яркий живой фон, крупный логотип Honer AI и поле имени.
struct OnboardingView: View {
    @EnvironmentObject private var settings: AppSettings
    @EnvironmentObject private var store: ChatStore
    @State private var restoring = false
    @State private var restoreStatus: String?
    @State private var restoreBusy = false
    @Environment(\.colorScheme) private var colorScheme
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @State private var name = ""
    @State private var appeared = false
    @FocusState private var nameFocused: Bool
    private var trimmedName: String { name.trimmingCharacters(in: .whitespacesAndNewlines) }

    var body: some View {
        GeometryReader { geometry in
            ZStack {
                OnboardingBackground(dark: colorScheme == .dark, animated: !reduceMotion)
                    .ignoresSafeArea()
                ScrollView {
                    VStack(spacing: 26) {
                        Spacer(minLength: 30)
                        AnimatedLogo(animated: !reduceMotion)
                            .frame(width: 150, height: 150)
                            .opacity(appeared ? 1 : 0)
                            .scaleEffect(appeared ? 1 : 0.7)
                        VStack(spacing: 10) {
                            Text("Honer AI")
                                .font(.system(size: 40, weight: .heavy, design: .rounded))
                                .foregroundStyle(LinearGradient(colors: [Color(red: 0.36, green: 0.62, blue: 1), Color(red: 0.62, green: 0.45, blue: 1), Color(red: 0.2, green: 0.83, blue: 0.95)],
                                                                startPoint: .leading, endPoint: .trailing))
                                .shadow(color: Color(red: 0.4, green: 0.5, blue: 1).opacity(0.35), radius: 12)
                            Text(settings.text("Давайте познакомимся", "Let's get acquainted"))
                                .font(.system(size: 23, weight: .semibold, design: .rounded))
                                .foregroundStyle(HonorTheme.foreground)
                            Text(settings.text("Как к вам обращаться? Подойдёт имя, ник или позывной.",
                                               "What should I call you? Use your name, nickname or callsign."))
                                .font(.system(size: 16))
                                .foregroundStyle(HonorTheme.secondary)
                                .multilineTextAlignment(.center)
                        }
                        .opacity(appeared ? 1 : 0)
                        .offset(y: appeared ? 0 : 18)

                        VStack(spacing: 16) {
                            HStack(spacing: 12) {
                                Image(systemName: "person.crop.circle.fill")
                                    .font(.system(size: 24))
                                    .foregroundStyle(HonorTheme.accent)
                                TextField(settings.text("Имя, ник или позывной", "Name, nickname or callsign"), text: $name)
                                    .textContentType(.nickname)
                                    .submitLabel(.continue)
                                    .font(.system(size: 20))
                                    .focused($nameFocused)
                                    .onSubmit(complete)
                                    .accessibilityIdentifier("onboarding.name")
                                    .onChange(of: name) { if $0.count > 60 { name = String($0.prefix(60)) } }
                            }
                            .padding(.horizontal, 18).padding(.vertical, 16)
                            .background(HonorTheme.background.opacity(colorScheme == .dark ? 0.55 : 0.8),
                                        in: RoundedRectangle(cornerRadius: 20, style: .continuous))
                            .overlay(RoundedRectangle(cornerRadius: 20, style: .continuous)
                                .stroke(nameFocused ? HonorTheme.accent : HonorTheme.divider, lineWidth: nameFocused ? 1.5 : 0.8))
                            .animation(.easeOut(duration: 0.2), value: nameFocused)

                            Button(action: complete) {
                                HStack(spacing: 8) {
                                    Text(settings.text("Начать общение", "Start chatting"))
                                    Image(systemName: "arrow.right")
                                }
                                .font(.system(size: 18, weight: .semibold))
                                .frame(maxWidth: .infinity)
                                .padding(.vertical, 17)
                                .background(
                                    LinearGradient(colors: [Color(red: 0.3, green: 0.55, blue: 1), Color(red: 0.55, green: 0.4, blue: 1)],
                                                   startPoint: .leading, endPoint: .trailing),
                                    in: Capsule())
                                .overlay(ShineOverlay(animated: !reduceMotion && !trimmedName.isEmpty).clipShape(Capsule()))
                                .foregroundStyle(.white)
                                .shadow(color: Color(red: 0.35, green: 0.45, blue: 1).opacity(trimmedName.isEmpty ? 0 : 0.45), radius: 16, y: 6)
                            }
                            .buttonStyle(.plain)
                            .disabled(trimmedName.isEmpty)
                            .opacity(trimmedName.isEmpty ? 0.55 : 1)
                            .animation(.easeOut(duration: 0.2), value: trimmedName.isEmpty)
                            .accessibilityIdentifier("onboarding.continue")
                        }
                        .padding(18)
                        .background(.ultraThinMaterial, in: RoundedRectangle(cornerRadius: 28, style: .continuous))
                        .overlay(RoundedRectangle(cornerRadius: 28, style: .continuous).stroke(Color.white.opacity(colorScheme == .dark ? 0.1 : 0.5), lineWidth: 1))
                        .opacity(appeared ? 1 : 0)
                        .offset(y: appeared ? 0 : 30)

                        Button { restoring = true } label: {
                            HStack(spacing: 8) {
                                if restoreBusy { ProgressView() } else { Image(systemName: "arrow.clockwise.icloud") }
                                Text(BackupService.shared.hadBackupBefore
                                     ? settings.text("С возвращением! Восстановить из резервной копии", "Welcome back! Restore from backup")
                                     : settings.text("Восстановить из резервной копии", "Restore from backup"))
                            }
                            .font(.system(size: 16, weight: .semibold))
                            .foregroundStyle(HonorTheme.accent)
                        }
                        .buttonStyle(.plain)
                        .disabled(restoreBusy)
                        .opacity(appeared ? 1 : 0)
                        .accessibilityIdentifier("onboarding.restore")
                        if let restoreStatus {
                            Text(restoreStatus).font(.footnote).foregroundStyle(.orange).multilineTextAlignment(.center)
                        }
                        Text(settings.text("Имя появится в профиле, и Honer AI будет учитывать его в разговоре. Изменить его можно в настройках.",
                                           "Your name will appear in your profile and Honer AI will use it in conversation. You can change it in Settings."))
                            .font(.footnote)
                            .foregroundStyle(HonorTheme.secondary)
                            .multilineTextAlignment(.center)
                            .opacity(appeared ? 1 : 0)
                        Spacer(minLength: 30)
                    }
                    .padding(.horizontal, 26)
                    .frame(minHeight: geometry.size.height)
                }
                .scrollDismissesKeyboard(.interactively)
            }
        }
        .fileImporter(isPresented: $restoring, allowedContentTypes: [.json]) { result in
            guard case .success(let url) = result else { return }
            restoreBusy = true
            restoreStatus = nil
            Task { @MainActor in
                let access = url.startAccessingSecurityScopedResource()
                defer { if access { url.stopAccessingSecurityScopedResource() }; restoreBusy = false }
                do {
                    try await store.importDataAsync(from: url)
                    if settings.displayName.trimmingCharacters(in: .whitespaces).isEmpty {
                        name = ""
                        restoreStatus = settings.text("Чаты восстановлены. Введите имя, чтобы продолжить.", "Chats restored. Enter your name to continue.")
                    } else {
                        UINotificationFeedbackGenerator().notificationOccurred(.success)
                        withAnimation(.easeInOut(duration: 0.35)) { settings.completedOnboarding = true }
                    }
                } catch {
                    restoreStatus = error.localizedDescription
                }
            }
        }
        .onAppear {
            name = settings.displayName
            withAnimation(.spring(response: 0.7, dampingFraction: 0.8).delay(0.1)) { appeared = true }
        }
        .accessibilityIdentifier("onboarding.page")
    }

    private func complete() {
        guard !trimmedName.isEmpty else { return }
        nameFocused = false
        UINotificationFeedbackGenerator().notificationOccurred(.success)
        settings.displayName = trimmedName
        withAnimation(.easeInOut(duration: 0.35)) { settings.completedOnboarding = true }
    }
}

/// Живой фон: мягкие цветные пятна медленно плывут и переливаются.
private struct OnboardingBackground: View {
    let dark: Bool
    let animated: Bool

    var body: some View {
        TimelineView(.animation(minimumInterval: 1.0 / 30.0, paused: !animated)) { context in
            let time = animated ? context.date.timeIntervalSinceReferenceDate : 0
            GeometryReader { geometry in
                let size = geometry.size
                ZStack {
                    (dark ? Color(red: 0.035, green: 0.043, blue: 0.08) : Color(red: 0.95, green: 0.96, blue: 1))
                    blob(Color(red: 0.25, green: 0.5, blue: 1), size: size, radius: 0.55, phase: 0, time: time)
                    blob(Color(red: 0.6, green: 0.35, blue: 1), size: size, radius: 0.48, phase: 2.1, time: time)
                    blob(Color(red: 0.1, green: 0.85, blue: 0.95), size: size, radius: 0.42, phase: 4.2, time: time)
                    blob(Color(red: 1, green: 0.45, blue: 0.75), size: size, radius: 0.3, phase: 5.3, time: time)
                }
                .blur(radius: 60)
                .overlay((dark ? Color.black : Color.white).opacity(dark ? 0.25 : 0.35))
            }
        }
    }

    private func blob(_ color: Color, size: CGSize, radius: CGFloat, phase: Double, time: Double) -> some View {
        let speed = 0.18
        let x = size.width * (0.5 + 0.34 * cos(time * speed + phase))
        let y = size.height * (0.45 + 0.28 * sin(time * speed * 1.3 + phase * 1.7))
        let diameter = max(size.width, size.height) * radius
        return Circle()
            .fill(color.opacity(dark ? 0.75 : 0.55))
            .frame(width: diameter, height: diameter)
            .position(x: x, y: y)
    }
}

/// Логотип со светящимся вращающимся кольцом и мягким «парением».
private struct AnimatedLogo: View {
    let animated: Bool

    var body: some View {
        TimelineView(.animation(minimumInterval: 1.0 / 60.0, paused: !animated)) { context in
            let time = animated ? context.date.timeIntervalSinceReferenceDate : 0
            let float = CGFloat(sin(time * 1.4)) * 6
            let pulse = 1 + 0.04 * sin(time * 2)
            ZStack {
                Circle()
                    .fill(RadialGradient(colors: [Color(red: 0.35, green: 0.55, blue: 1).opacity(0.55), .clear],
                                         center: .center, startRadius: 10, endRadius: 80))
                    .scaleEffect(pulse * 1.15)
                Circle()
                    .stroke(AngularGradient(colors: [Color(red: 0.3, green: 0.6, blue: 1), Color(red: 0.65, green: 0.4, blue: 1),
                                                     Color(red: 0.15, green: 0.9, blue: 0.95), Color(red: 0.3, green: 0.6, blue: 1)],
                                            center: .center),
                            style: StrokeStyle(lineWidth: 3.5, lineCap: .round))
                    .rotationEffect(.degrees(time * 40))
                    .frame(width: 128, height: 128)
                Circle()
                    .fill(.ultraThinMaterial)
                    .frame(width: 112, height: 112)
                    .shadow(color: Color(red: 0.3, green: 0.45, blue: 1).opacity(0.45), radius: 18)
                HonorMark(size: 70)
                    .scaleEffect(pulse)
            }
            .offset(y: float)
        }
        .accessibilityElement(children: .ignore)
        .accessibilityLabel("Honer AI")
        .accessibilityIdentifier("onboarding.logo")
    }
}

/// Блик, который пробегает по кнопке.
private struct ShineOverlay: View {
    let animated: Bool

    var body: some View {
        TimelineView(.animation(minimumInterval: 1.0 / 60.0, paused: !animated)) { context in
            GeometryReader { geometry in
                let cycle = context.date.timeIntervalSinceReferenceDate.truncatingRemainder(dividingBy: 2.8) / 2.8
                let x = geometry.size.width * (CGFloat(cycle) * 1.8 - 0.4)
                LinearGradient(colors: [.clear, .white.opacity(animated ? 0.35 : 0), .clear], startPoint: .leading, endPoint: .trailing)
                    .frame(width: geometry.size.width * 0.35)
                    .rotationEffect(.degrees(18))
                    .offset(x: x)
            }
        }
        .allowsHitTesting(false)
    }
}
