import SwiftUI

struct OnboardingView: View {
    @EnvironmentObject private var settings: AppSettings
    @Environment(\.colorScheme) private var colorScheme
    @State private var name = ""
    @FocusState private var nameFocused: Bool
    private var trimmedName: String { name.trimmingCharacters(in: .whitespacesAndNewlines) }

    var body: some View {
        GeometryReader { geometry in
            ScrollView {
                VStack(spacing: 25) {
                    Spacer(minLength: 35)
                    HonorMark().frame(width: 68, height: 68)
                    VStack(spacing: 12) {
                        Text("Honer AI").font(.system(size: 32, weight: .bold))
                        Text(settings.text("Давайте познакомимся", "Let's get acquainted"))
                            .font(.system(size: 23, weight: .semibold))
                        Text(settings.text("Как к вам обращаться? Подойдёт имя, ник или позывной.",
                                           "What should I call you? Use your name, nickname or callsign."))
                            .foregroundStyle(.secondary).multilineTextAlignment(.center)
                    }
                    TextField(settings.text("Имя, ник или позывной", "Name, nickname or callsign"), text: $name)
                        .textContentType(.nickname).submitLabel(.continue)
                        .font(.system(size: 20)).padding(20)
                        .background(Color.primary.opacity(0.06), in: RoundedRectangle(cornerRadius: 22))
                        .overlay(RoundedRectangle(cornerRadius: 22).stroke(Color.primary.opacity(0.10)))
                        .focused($nameFocused).onSubmit(complete)
                        .accessibilityIdentifier("onboarding.name")
                        .onChange(of: name) { if $0.count > 60 { name = String($0.prefix(60)) } }
                    Button(action: complete) {
                        Text(settings.text("Начать общение", "Start chatting")).font(.headline)
                            .frame(maxWidth: .infinity).padding(18)
                            .background(Color(red: 0.38, green: 0.57, blue: 0.96), in: Capsule())
                            .foregroundStyle(.white)
                    }
                    .disabled(trimmedName.isEmpty).opacity(trimmedName.isEmpty ? 0.5 : 1)
                    .accessibilityIdentifier("onboarding.continue")
                    Text(settings.text("Имя появится в профиле, и Honer AI будет учитывать его в разговоре. Изменить его можно в настройках.",
                                       "Your name will appear in your profile and Honer AI will use it in conversation. You can change it in Settings."))
                        .font(.footnote).foregroundStyle(.secondary).multilineTextAlignment(.center)
                    Spacer(minLength: 35)
                }
                .padding(.horizontal, 30).frame(minHeight: geometry.size.height)
            }.scrollDismissesKeyboard(.interactively)
        }
        .background(colorScheme == .dark ? Color(white: 0.059) : Color.white)
        .onAppear { name = settings.displayName }
        .accessibilityIdentifier("onboarding.page")
    }

    private func complete() {
        guard !trimmedName.isEmpty else { return }
        nameFocused = false
        settings.displayName = trimmedName
        withAnimation(.easeInOut(duration: 0.28)) { settings.completedOnboarding = true }
    }
}
