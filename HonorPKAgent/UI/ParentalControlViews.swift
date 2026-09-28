import SwiftUI
import UIKit

// MARK: - Привязки к правилам

extension ParentalControl {
    /// Binding к полю правил. Запись проходит через `update`, то есть только в открытой сессии.
    func binding<Value>(_ keyPath: WritableKeyPath<ParentalRules, Value>) -> Binding<Value> {
        Binding<Value>(
            get: { self.rules[keyPath: keyPath] },
            set: { newValue in
                _ = self.update { r in r[keyPath: keyPath] = newValue }
            }
        )
    }
}

enum ParentalSheet: String, Identifiable {
    case changePIN, disable, reset
    var id: String { rawValue }
}

// MARK: - Страница настроек

/// Страница «Родительский контроль» (открывается из Настроек через NavigationLink).
struct ParentalControlPage: View {
    @EnvironmentObject var settings: AppSettings
    @ObservedObject var control = ParentalControl.shared
    @Environment(\.scenePhase) private var scenePhase
    @State private var sheet: ParentalSheet?

    var body: some View {
        pageContent
            .navigationTitle(settings.text("Родительский контроль", "Parental control"))
            .navigationBarTitleDisplayMode(.inline)
            .animation(.spring(response: 0.45, dampingFraction: 0.85), value: phase)
            .onDisappear { control.lock() }
            .onChange(of: scenePhase) { newPhase in
                if newPhase != .active { control.lock() }
            }
            .sheet(item: $sheet) { item in
                sheetView(item).environmentObject(settings)
            }
    }

    /// 0 — PIN ещё нет, 1 — нужен PIN, 2 — настройки открыты.
    private var phase: Int {
        if !control.hasPIN { return 0 }
        return control.unlocked ? 2 : 1
    }

    @ViewBuilder
    private var pageContent: some View {
        switch phase {
        case 0:
            ParentalIntroView(control: control)
                .transition(.opacity)
        case 1:
            ParentalUnlockView(control: control)
                .transition(.opacity)
        default:
            ParentalSettingsForm(control: control, sheet: $sheet)
                .transition(.opacity)
        }
    }

    @ViewBuilder
    private func sheetView(_ item: ParentalSheet) -> some View {
        switch item {
        case .changePIN:
            ParentalChangePINSheet(control: control)
        case .disable:
            ParentalPINPromptSheet(
                control: control,
                title: settings.text("Выключить контроль", "Turn off control"),
                subtitle: settings.text("Введите PIN родителя, чтобы выключить защиту.", "Enter the parent PIN to turn protection off."),
                symbol: "power",
                action: { pin in control.disable(pin: pin) }
            )
        case .reset:
            ParentalPINPromptSheet(
                control: control,
                title: settings.text("Сбросить всё", "Reset everything"),
                subtitle: settings.text("PIN и все настройки будут удалены.", "The PIN and all settings will be removed."),
                symbol: "trash",
                action: { pin in control.resetEverything(confirmWithPIN: pin) }
            )
        }
    }
}

// MARK: - Вступление и создание PIN

private struct ParentalIntroView: View {
    @EnvironmentObject private var settings: AppSettings
    @ObservedObject var control: ParentalControl
    @State private var settingUp: Bool = false

    var body: some View {
        ScrollView {
            VStack(spacing: 22) {
                if settingUp {
                    ParentalPINSetupFlow(onComplete: finishSetup)
                        .transition(.move(edge: .trailing).combined(with: .opacity))
                    Button(settings.text("Отмена", "Cancel")) {
                        withAnimation(.spring(response: 0.4, dampingFraction: 0.85)) { settingUp = false }
                    }
                    .accessibilityIdentifier("parental.setup.cancel")
                } else {
                    ParentalIntroCard()
                        .transition(.opacity)
                    enableButton
                }
            }
            .padding(.horizontal, 18)
            .padding(.vertical, 24)
        }
        .background(HonorTheme.background.ignoresSafeArea())
        .accessibilityIdentifier("parental.page")
    }

    private var enableButton: some View {
        Button {
            withAnimation(.spring(response: 0.4, dampingFraction: 0.85)) { settingUp = true }
        } label: {
            Label(settings.text("Включить родительский контроль", "Turn on parental control"), systemImage: "lock.shield")
                .font(.system(size: 17, weight: .semibold))
                .foregroundStyle(Color.white)
                .frame(maxWidth: .infinity)
                .padding(.vertical, 16)
                .background(ParentalStyle.accentGradient, in: RoundedRectangle(cornerRadius: 18, style: .continuous))
        }
        .buttonStyle(.plain)
        .accessibilityIdentifier("parental.enable")
    }

    private func finishSetup(_ pin: String) {
        guard control.setPIN(pin) else { return }
        _ = control.update { r in r.enabled = true }
        UINotificationFeedbackGenerator().notificationOccurred(.success)
    }
}

private struct ParentalIntroCard: View {
    @EnvironmentObject private var settings: AppSettings

    var body: some View {
        VStack(spacing: 18) {
            ParentalHeroIcon(symbol: "figure.and.child.holdinghands", badge: "lock.shield.fill")
            Text(settings.text("Родительский контроль", "Parental control"))
                .font(.system(size: 24, weight: .bold))
                .multilineTextAlignment(.center)
            Text(settings.text("Выключен по умолчанию и никогда не включается сам — ни по возрасту, ни по другим признакам. Включить его может только родитель, защитив настройки PIN-кодом.",
                               "Off by default and never turns on by itself — not by age or anything else. Only a parent can turn it on and protect the settings with a PIN."))
                .font(.system(size: 15))
                .foregroundStyle(.secondary)
                .multilineTextAlignment(.center)
                .fixedSize(horizontal: false, vertical: true)
            bullets
        }
        .padding(22)
        .frame(maxWidth: .infinity)
        .background(HonorTheme.surface, in: RoundedRectangle(cornerRadius: 26, style: .continuous))
        .overlay(RoundedRectangle(cornerRadius: 26, style: .continuous).stroke(HonorTheme.divider, lineWidth: 0.7))
    }

    private var bullets: some View {
        VStack(alignment: .leading, spacing: 12) {
            ParentalBullet(symbol: "hand.raised.fill", tint: .pink,
                           text: settings.text("Фильтр взрослых, опасных и пугающих тем", "Filters adult, dangerous and scary topics"))
            ParentalBullet(symbol: "clock.fill", tint: .orange,
                           text: settings.text("Лимит времени и тихие часы", "Daily time limit and quiet hours"))
            ParentalBullet(symbol: "globe", tint: .blue,
                           text: settings.text("Ограничение сайтов, поиска и игр", "Limits sites, web search and games"))
            ParentalBullet(symbol: "lock.shield.fill", tint: .green,
                           text: settings.text("Ребёнок не сможет выключить контроль без PIN", "A child can't turn it off without the PIN"))
        }
        .frame(maxWidth: .infinity, alignment: .leading)
    }
}

private struct ParentalBullet: View {
    let symbol: String
    let tint: Color
    let text: String

    var body: some View {
        HStack(spacing: 12) {
            ParentalIconBadge(symbol: symbol, tint: tint)
            Text(text)
                .font(.system(size: 15))
                .fixedSize(horizontal: false, vertical: true)
        }
    }
}

/// Создание PIN: ввести дважды. При несовпадении — тряска и повтор с начала.
private struct ParentalPINSetupFlow: View {
    @EnvironmentObject private var settings: AppSettings
    let onComplete: (String) -> Void
    @State private var first: String = ""
    @State private var pin: String = ""
    @State private var confirming: Bool = false
    @State private var mismatch: Bool = false
    @State private var shakes: CGFloat = 0

    var body: some View {
        VStack(spacing: 20) {
            ParentalHeroIcon(symbol: confirming ? "checkmark.shield" : "key.fill", badge: nil)
            Text(title)
                .font(.system(size: 21, weight: .bold))
                .multilineTextAlignment(.center)
            Text(subtitle)
                .font(.system(size: 14))
                .foregroundStyle(mismatch ? Color.red : Color.secondary)
                .multilineTextAlignment(.center)
                .frame(minHeight: 36)
            ParentalPINDots(count: pin.count, error: mismatch)
                .modifier(ParentalShakeEffect(animatableData: shakes))
            PINPadView(pin: $pin, onSubmit: { submit() })
        }
        .accessibilityIdentifier("parental.setup")
    }

    private var title: String {
        confirming
            ? settings.text("Повторите PIN", "Repeat the PIN")
            : settings.text("Придумайте PIN родителя", "Create a parent PIN")
    }

    private var subtitle: String {
        if mismatch { return settings.text("PIN не совпал. Попробуйте ещё раз.", "PINs didn't match. Try again.") }
        if confirming { return settings.text("Введите тот же PIN ещё раз.", "Enter the same PIN once more.") }
        return settings.text("От 4 до 8 цифр. Не говорите его ребёнку.", "4 to 8 digits. Don't tell it to your child.")
    }

    private func submit() {
        guard ParentalMath.isValidPIN(pin) else { return }
        if !confirming {
            first = pin
            pin = ""
            mismatch = false
            withAnimation(.spring(response: 0.35, dampingFraction: 0.8)) { confirming = true }
            return
        }
        if pin == first {
            onComplete(pin)
            return
        }
        UINotificationFeedbackGenerator().notificationOccurred(.error)
        pin = ""
        first = ""
        mismatch = true
        withAnimation(.default) { shakes += 1 }
        withAnimation(.spring(response: 0.35, dampingFraction: 0.8)) { confirming = false }
    }
}

// MARK: - Ввод PIN

private struct ParentalUnlockView: View {
    @EnvironmentObject private var settings: AppSettings
    @ObservedObject var control: ParentalControl

    var body: some View {
        ScrollView {
            VStack(spacing: 18) {
                ParentalStatusChip(enabled: control.rules.enabled)
                ParentalPINEntryPanel(
                    control: control,
                    title: settings.text("Введите PIN родителя", "Enter the parent PIN"),
                    subtitle: settings.text("Настройки защищены — ребёнок не сможет их изменить.", "Settings are protected — a child can't change them."),
                    symbol: "lock.shield",
                    onSubmit: { pin in control.verify(pin) }
                )
            }
            .padding(.horizontal, 18)
            .padding(.vertical, 24)
        }
        .background(HonorTheme.background.ignoresSafeArea())
        .accessibilityIdentifier("parental.page")
    }
}

/// Поле ввода PIN с точками, тряской при ошибке и обратным отсчётом при блокировке перебора.
private struct ParentalPINEntryPanel: View {
    @EnvironmentObject private var settings: AppSettings
    @ObservedObject var control: ParentalControl
    let title: String
    let subtitle: String
    let symbol: String
    let onSubmit: (String) -> Bool
    @State private var pin: String = ""
    @State private var failed: Bool = false
    @State private var shakes: CGFloat = 0

    var body: some View {
        VStack(spacing: 20) {
            ParentalHeroIcon(symbol: symbol, badge: nil)
            Text(title)
                .font(.system(size: 21, weight: .bold))
                .multilineTextAlignment(.center)
            ParentalPINDots(count: pin.count, error: failed)
                .modifier(ParentalShakeEffect(animatableData: shakes))
            TimelineView(.periodic(from: Date(), by: 1)) { context in
                padArea(now: context.date)
            }
        }
    }

    private func padArea(now: Date) -> some View {
        let locked = isLocked(at: now)
        return VStack(spacing: 18) {
            Text(statusMessage(now: now))
                .font(.system(size: 14))
                .foregroundStyle(statusColor(now: now))
                .multilineTextAlignment(.center)
                .frame(minHeight: 38)
                .accessibilityIdentifier("parental.pin.status")
            PINPadView(pin: $pin, enabled: !locked, onSubmit: { submit() })
        }
    }

    private func isLocked(at now: Date) -> Bool {
        guard let until = control.lockedUntil else { return false }
        return until > now
    }

    private func statusMessage(now: Date) -> String {
        if let until = control.lockedUntil, until > now {
            let seconds = Int(until.timeIntervalSince(now).rounded(.up))
            let time = ParentalMath.countdownString(seconds: seconds)
            return settings.text("Слишком много неверных попыток. Повторите через \(time).",
                                 "Too many wrong attempts. Try again in \(time).")
        }
        if failed {
            let left = control.remainingAttempts
            return settings.text("Неверный PIN. Осталось попыток: \(left).", "Wrong PIN. Attempts left: \(left).")
        }
        return subtitle
    }

    private func statusColor(now: Date) -> Color {
        if isLocked(at: now) { return .orange }
        return failed ? .red : .secondary
    }

    private func submit() {
        guard !pin.isEmpty else { return }
        let entered = pin
        pin = ""
        if onSubmit(entered) {
            failed = false
            UINotificationFeedbackGenerator().notificationOccurred(.success)
        } else {
            failed = true
            UINotificationFeedbackGenerator().notificationOccurred(.error)
            withAnimation(.default) { shakes += 1 }
        }
    }
}

/// Лист с вводом PIN для подтверждения действия (выключение, сброс, снятие блокировки на сегодня).
struct ParentalPINPromptSheet: View {
    @EnvironmentObject private var settings: AppSettings
    @Environment(\.dismiss) private var dismiss
    @ObservedObject var control: ParentalControl
    let title: String
    let subtitle: String
    let symbol: String
    let action: (String) -> Bool

    var body: some View {
        NavigationStack {
            ScrollView {
                ParentalPINEntryPanel(control: control, title: title, subtitle: subtitle, symbol: symbol,
                                      onSubmit: { pin in run(pin) })
                    .padding(.horizontal, 18)
                    .padding(.vertical, 20)
            }
            .background(HonorTheme.background.ignoresSafeArea())
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button(settings.text("Отмена", "Cancel")) { dismiss() }
                        .accessibilityIdentifier("parental.prompt.cancel")
                }
            }
        }
    }

    private func run(_ pin: String) -> Bool {
        let ok = action(pin)
        if ok { dismiss() }
        return ok
    }
}

private struct ParentalChangePINSheet: View {
    @EnvironmentObject private var settings: AppSettings
    @Environment(\.dismiss) private var dismiss
    @ObservedObject var control: ParentalControl
    @State private var failed: Bool = false

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(spacing: 14) {
                    ParentalPINSetupFlow(onComplete: finish)
                    if failed {
                        Text(settings.text("Сессия истекла. Закройте окно и снова введите PIN.", "Session expired. Close this and enter the PIN again."))
                            .font(.system(size: 14))
                            .foregroundStyle(Color.red)
                            .multilineTextAlignment(.center)
                    }
                }
                .padding(.horizontal, 18)
                .padding(.vertical, 20)
            }
            .background(HonorTheme.background.ignoresSafeArea())
            .navigationTitle(settings.text("Новый PIN", "New PIN"))
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button(settings.text("Отмена", "Cancel")) { dismiss() }
                }
            }
        }
    }

    private func finish(_ pin: String) {
        if control.setPIN(pin) {
            UINotificationFeedbackGenerator().notificationOccurred(.success)
            dismiss()
        } else {
            failed = true
        }
    }
}

// MARK: - Клавиатура PIN

/// Цифровая клавиатура для PIN (без Face ID).
struct PINPadView: View {
    @Binding var pin: String
    var maxLength: Int = 8
    var minLength: Int = 4
    var enabled: Bool = true
    var onSubmit: () -> Void

    static let rows: [[String]] = [["1", "2", "3"], ["4", "5", "6"], ["7", "8", "9"], ["ok", "0", "del"]]

    var body: some View {
        VStack(spacing: 14) {
            ForEach(0..<PINPadView.rows.count, id: \.self) { index in
                row(PINPadView.rows[index])
            }
        }
        .disabled(!enabled)
        .opacity(enabled ? 1 : 0.4)
        .animation(.easeInOut(duration: 0.2), value: enabled)
    }

    private func row(_ keys: [String]) -> some View {
        HStack(spacing: 24) {
            ForEach(keys, id: \.self) { key in
                PINPadKey(key: key, canSubmit: pin.count >= minLength, tap: { handle(key) })
            }
        }
    }

    private func handle(_ key: String) {
        switch key {
        case "del":
            if !pin.isEmpty { pin.removeLast() }
        case "ok":
            if pin.count >= minLength { onSubmit() }
        default:
            guard pin.count < maxLength else { return }
            UIImpactFeedbackGenerator(style: .light).impactOccurred()
            pin.append(key)
            if pin.count == maxLength { onSubmit() }
        }
    }
}

private struct PINPadKey: View {
    let key: String
    let canSubmit: Bool
    let tap: () -> Void

    var body: some View {
        Button(action: tap) { label }
            .buttonStyle(ParentalKeyButtonStyle(filled: key != "del" && key != "ok"))
            .disabled(key == "ok" && !canSubmit)
            .accessibilityIdentifier(identifier)
            .accessibilityLabel(accessibilityText)
    }

    @ViewBuilder
    private var label: some View {
        if key == "del" {
            Image(systemName: "delete.left")
                .font(.system(size: 22, weight: .medium))
        } else if key == "ok" {
            Image(systemName: "checkmark.circle.fill")
                .font(.system(size: 30, weight: .semibold))
                .foregroundStyle(canSubmit ? HonorTheme.accent : Color.secondary)
        } else {
            Text(key)
                .font(.system(size: 30, weight: .regular, design: .rounded))
        }
    }

    private var identifier: String {
        if key == "del" { return "parental.pin.delete" }
        if key == "ok" { return "parental.pin.ok" }
        return "parental.pin.key." + key
    }

    private var accessibilityText: String {
        if key == "del" { return "Delete" }
        if key == "ok" { return "OK" }
        return key
    }
}

private struct ParentalKeyButtonStyle: ButtonStyle {
    var filled: Bool

    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .foregroundStyle(HonorTheme.foreground)
            .frame(width: 74, height: 74)
            .background(Circle().fill(filled ? HonorTheme.raised.opacity(configuration.isPressed ? 1 : 0.65) : Color.clear))
            .contentShape(Circle())
            .scaleEffect(configuration.isPressed ? 0.9 : 1)
            .animation(.spring(response: 0.25, dampingFraction: 0.6), value: configuration.isPressed)
    }
}

struct ParentalPINDots: View {
    let count: Int
    let error: Bool

    var body: some View {
        HStack(spacing: 14) {
            ForEach(0..<slots, id: \.self) { index in
                dot(filled: index < count)
            }
        }
        .animation(.spring(response: 0.3, dampingFraction: 0.6), value: count)
        .accessibilityElement(children: .ignore)
        .accessibilityLabel("\(count)")
        .accessibilityIdentifier("parental.pin.dots")
    }

    private var slots: Int { max(4, min(8, count)) }

    private func dot(filled: Bool) -> some View {
        let color: Color = error ? Color.red : HonorTheme.accent
        return Circle()
            .fill(filled ? color : Color.clear)
            .overlay(Circle().stroke(filled ? color : HonorTheme.secondary, lineWidth: 1.5))
            .frame(width: 14, height: 14)
            .scaleEffect(filled ? 1.12 : 1)
    }
}

struct ParentalShakeEffect: GeometryEffect {
    var travel: CGFloat = 10
    var animatableData: CGFloat

    func effectValue(size: CGSize) -> ProjectionTransform {
        let offset: CGFloat = travel * sin(animatableData * .pi * 4)
        return ProjectionTransform(CGAffineTransform(translationX: offset, y: 0))
    }
}

// MARK: - Форма настроек

private struct ParentalSettingsForm: View {
    @ObservedObject var control: ParentalControl
    @Binding var sheet: ParentalSheet?

    var body: some View {
        Form {
            Group {
                ParentalMasterSection(control: control, sheet: $sheet)
                ParentalTodaySection(control: control)
                ParentalFiltersSection(control: control)
                ParentalFeaturesSection(control: control)
                ParentalGamesSection(control: control)
            }
            Group {
                ParentalSitesSection(control: control)
                ParentalAllowedSitesSection(control: control)
                ParentalWordsSection(control: control)
                ParentalTimeSection(control: control)
                ParentalAnswersSection(control: control)
            }
            ParentalPINSection(control: control, sheet: $sheet)
        }
        .accessibilityIdentifier("parental.page")
    }
}

private struct ParentalMasterSection: View {
    @EnvironmentObject private var settings: AppSettings
    @ObservedObject var control: ParentalControl
    @Binding var sheet: ParentalSheet?

    var body: some View {
        Section {
            ParentalStatusHeader(enabled: control.rules.enabled)
            Toggle(isOn: masterBinding) {
                Label(settings.text("Родительский контроль", "Parental control"), systemImage: "lock.shield")
            }
            .tint(HonorTheme.accent)
            .accessibilityIdentifier("parental.toggle.enabled")
        } footer: {
            Text(settings.text("Контроль никогда не включается автоматически. Выключить его можно только с PIN-кодом.",
                               "Control never turns on automatically. It can only be turned off with the PIN."))
        }
    }

    private var masterBinding: Binding<Bool> {
        Binding<Bool>(
            get: { control.rules.enabled },
            set: { newValue in
                if newValue {
                    _ = control.update { r in r.enabled = true }
                } else {
                    sheet = .disable
                }
            }
        )
    }
}

private struct ParentalStatusHeader: View {
    @EnvironmentObject private var settings: AppSettings
    let enabled: Bool

    var body: some View {
        HStack(spacing: 14) {
            ZStack {
                Circle()
                    .fill(enabled ? ParentalStyle.accentGradient : ParentalStyle.mutedGradient)
                    .frame(width: 52, height: 52)
                Image(systemName: enabled ? "checkmark.shield.fill" : "shield.slash")
                    .font(.system(size: 24, weight: .semibold))
                    .foregroundStyle(Color.white)
            }
            VStack(alignment: .leading, spacing: 3) {
                Text(enabled ? settings.text("Защита включена", "Protection is on") : settings.text("Защита выключена", "Protection is off"))
                    .font(.system(size: 17, weight: .semibold))
                Text(settings.text("Настройки защищены PIN-кодом", "Settings are protected by a PIN"))
                    .font(.system(size: 13))
                    .foregroundStyle(.secondary)
            }
        }
        .padding(.vertical, 6)
        .animation(.spring(response: 0.4, dampingFraction: 0.75), value: enabled)
    }
}

private struct ParentalStatusChip: View {
    @EnvironmentObject private var settings: AppSettings
    let enabled: Bool

    var body: some View {
        Label(enabled ? settings.text("Контроль включён", "Control is on") : settings.text("Контроль выключен", "Control is off"),
              systemImage: enabled ? "checkmark.shield.fill" : "shield.slash")
            .font(.system(size: 13, weight: .semibold))
            .foregroundStyle(enabled ? Color.green : Color.secondary)
            .padding(.horizontal, 12)
            .padding(.vertical, 7)
            .background(Capsule().fill((enabled ? Color.green : Color.gray).opacity(0.14)))
    }
}

private struct ParentalTodaySection: View {
    @EnvironmentObject private var settings: AppSettings
    @ObservedObject var control: ParentalControl

    var body: some View {
        Section {
            HStack {
                Label(settings.text("Сегодня в Honer AI", "Today in Honer AI"), systemImage: "clock")
                Spacer()
                Text(usageText)
                    .foregroundStyle(.secondary)
                    .monospacedDigit()
            }
            if limit > 0 {
                ProgressView(value: progress)
                    .tint(progress >= 1 ? Color.red : HonorTheme.accent)
                    .accessibilityIdentifier("parental.today.progress")
            }
            if let reason = control.blockReasonText(english: settings.language == .english) {
                Text(reason)
                    .font(.system(size: 13))
                    .foregroundStyle(Color.orange)
            }
        } header: {
            Text(settings.text("Сегодня", "Today"))
        }
    }

    private var limit: Int { control.rules.dailyLimitMinutes }
    private var used: Int { control.minutesUsedToday }

    private var progress: Double {
        guard limit > 0 else { return 0 }
        return min(1, Double(used) / Double(limit))
    }

    private var usageText: String {
        if limit > 0 {
            return settings.text("\(used) из \(limit) мин", "\(used) of \(limit) min")
        }
        return settings.text("\(used) мин", "\(used) min")
    }
}

/// Описание переключателя: иконка, подписи и поле правил.
struct ParentalToggleSpec: Identifiable {
    let field: String
    let symbol: String
    let tint: Color
    let ruTitle: String
    let enTitle: String
    let ruHint: String
    let enHint: String
    let keyPath: WritableKeyPath<ParentalRules, Bool>
    var id: String { field }
}

private struct ParentalToggleRow: View {
    @EnvironmentObject private var settings: AppSettings
    let spec: ParentalToggleSpec
    @Binding var isOn: Bool

    var body: some View {
        Toggle(isOn: $isOn) {
            HStack(alignment: .center, spacing: 12) {
                ParentalIconBadge(symbol: spec.symbol, tint: spec.tint)
                VStack(alignment: .leading, spacing: 2) {
                    Text(settings.text(spec.ruTitle, spec.enTitle))
                        .font(.system(size: 16))
                    Text(settings.text(spec.ruHint, spec.enHint))
                        .font(.system(size: 12))
                        .foregroundStyle(.secondary)
                        .fixedSize(horizontal: false, vertical: true)
                }
            }
        }
        .tint(HonorTheme.accent)
        .accessibilityIdentifier("parental.toggle." + spec.field)
    }
}

private struct ParentalFiltersSection: View {
    @EnvironmentObject private var settings: AppSettings
    @ObservedObject var control: ParentalControl

    static let specs: [ParentalToggleSpec] = [
        ParentalToggleSpec(field: "blockAdult", symbol: "eye.slash.fill", tint: .pink,
                           ruTitle: "Взрослый контент", enTitle: "Adult content",
                           ruHint: "Секс, эротика, откровенные сцены", enHint: "Sex, erotica, explicit scenes",
                           keyPath: \ParentalRules.blockAdult),
        ParentalToggleSpec(field: "blockViolence", symbol: "exclamationmark.shield.fill", tint: .orange,
                           ruTitle: "Насилие и оружие", enTitle: "Violence and weapons",
                           ruHint: "Жестокость, оружие, взрывчатка", enHint: "Cruelty, weapons, explosives",
                           keyPath: \ParentalRules.blockViolence),
        ParentalToggleSpec(field: "blockDrugs", symbol: "pills.fill", tint: .teal,
                           ruTitle: "Наркотики, алкоголь, табак", enTitle: "Drugs, alcohol, tobacco",
                           ruHint: "Включая вейпы и электронные сигареты", enHint: "Including vapes and e-cigarettes",
                           keyPath: \ParentalRules.blockDrugs),
        ParentalToggleSpec(field: "blockGambling", symbol: "dice.fill", tint: .green,
                           ruTitle: "Азартные игры", enTitle: "Gambling",
                           ruHint: "Казино, ставки, букмекеры, автоматы", enHint: "Casinos, betting, slot machines",
                           keyPath: \ParentalRules.blockGambling),
        ParentalToggleSpec(field: "blockProfanity", symbol: "exclamationmark.bubble.fill", tint: .purple,
                           ruTitle: "Мат и грубость", enTitle: "Profanity",
                           ruHint: "Бранные слова скрываются точками", enHint: "Swear words are hidden with dots",
                           keyPath: \ParentalRules.blockProfanity),
        ParentalToggleSpec(field: "blockSelfHarm", symbol: "heart.fill", tint: .red,
                           ruTitle: "Самоповреждение", enTitle: "Self-harm",
                           ruHint: "Бережный ответ и телефон доверия вместо опасных советов", enHint: "Caring reply and a helpline instead of harmful advice",
                           keyPath: \ParentalRules.blockSelfHarm),
        ParentalToggleSpec(field: "blockHate", symbol: "hand.thumbsdown.fill", tint: .brown,
                           ruTitle: "Ненависть и травля", enTitle: "Hate and bullying",
                           ruHint: "Оскорбления, дискриминация, буллинг", enHint: "Insults, discrimination, bullying",
                           keyPath: \ParentalRules.blockHate),
        ParentalToggleSpec(field: "blockScaryContent", symbol: "moon.stars.fill", tint: .indigo,
                           ruTitle: "Страшилки", enTitle: "Scary content",
                           ruHint: "Хорроры и жуткие истории", enHint: "Horror and creepy stories",
                           keyPath: \ParentalRules.blockScaryContent),
        ParentalToggleSpec(field: "blockDating", symbol: "heart.slash.fill", tint: .pink,
                           ruTitle: "Знакомства и флирт", enTitle: "Dating and flirting",
                           ruHint: "Романтические ролевые игры, сайты знакомств", enHint: "Romantic roleplay, dating apps",
                           keyPath: \ParentalRules.blockDating),
        ParentalToggleSpec(field: "blockPersonalDataSharing", symbol: "person.crop.circle.badge.xmark", tint: .blue,
                           ruTitle: "Личные данные", enTitle: "Personal data",
                           ruHint: "Не спрашивать адрес, телефон и школу; предупреждать ребёнка", enHint: "Never ask for address, phone or school; warn the child",
                           keyPath: \ParentalRules.blockPersonalDataSharing)
    ]

    var body: some View {
        Section {
            ForEach(ParentalFiltersSection.specs) { spec in
                ParentalToggleRow(spec: spec, isOn: control.binding(spec.keyPath))
            }
        } header: {
            Text(settings.text("Фильтры контента", "Content filters"))
        }
    }
}

private struct ParentalFeaturesSection: View {
    @EnvironmentObject private var settings: AppSettings
    @ObservedObject var control: ParentalControl

    static let specs: [ParentalToggleSpec] = [
        ParentalToggleSpec(field: "allowWebSearch", symbol: "magnifyingglass", tint: .blue,
                           ruTitle: "Поиск в интернете", enTitle: "Web search",
                           ruHint: "Honer AI может искать в сети", enHint: "Honer AI can search the web",
                           keyPath: \ParentalRules.allowWebSearch),
        ParentalToggleSpec(field: "allowOpenLinks", symbol: "link", tint: .teal,
                           ruTitle: "Открывать ссылки", enTitle: "Open links",
                           ruHint: "Переходить на сайты из ответов", enHint: "Open websites from answers",
                           keyPath: \ParentalRules.allowOpenLinks),
        ParentalToggleSpec(field: "allowImageGeneration", symbol: "paintbrush.fill", tint: .orange,
                           ruTitle: "Рисование", enTitle: "Drawing",
                           ruHint: "Создание картинок", enHint: "Image generation",
                           keyPath: \ParentalRules.allowImageGeneration),
        ParentalToggleSpec(field: "allowGames", symbol: "gamecontroller.fill", tint: .green,
                           ruTitle: "Игры", enTitle: "Games",
                           ruHint: "Шахматы, шашки и другие мини-игры", enHint: "Chess, checkers and other mini games",
                           keyPath: \ParentalRules.allowGames),
        ParentalToggleSpec(field: "allowVoiceCloning", symbol: "waveform", tint: .purple,
                           ruTitle: "Клонирование голоса", enTitle: "Voice cloning",
                           ruHint: "Запись и отправка образца голоса", enHint: "Recording and uploading a voice sample",
                           keyPath: \ParentalRules.allowVoiceCloning),
        ParentalToggleSpec(field: "allowContacts", symbol: "person.crop.circle", tint: .gray,
                           ruTitle: "Контакты", enTitle: "Contacts",
                           ruHint: "Доступ к телефонной книге", enHint: "Access to the address book",
                           keyPath: \ParentalRules.allowContacts),
        ParentalToggleSpec(field: "allowLocation", symbol: "location.fill", tint: .blue,
                           ruTitle: "Геолокация", enTitle: "Location",
                           ruHint: "Погода и места рядом", enHint: "Weather and nearby places",
                           keyPath: \ParentalRules.allowLocation)
    ]

    var body: some View {
        Section {
            ForEach(ParentalFeaturesSection.specs) { spec in
                ParentalToggleRow(spec: spec, isOn: control.binding(spec.keyPath))
            }
        } header: {
            Text(settings.text("Возможности", "Features"))
        }
    }
}

private struct ParentalGamesSection: View {
    @EnvironmentObject private var settings: AppSettings
    @ObservedObject var control: ParentalControl

    static let games: [String] = ["chess", "checkers", "durak", "slots"]

    var body: some View {
        if control.rules.allowGames {
            Section {
                ForEach(ParentalGamesSection.games, id: \.self) { raw in
                    gameRow(raw)
                }
            } header: {
                Text(settings.text("Разрешённые игры", "Allowed games"))
            } footer: {
                Text(settings.text("«Удача» — игровой автомат; он всегда недоступен, пока включён фильтр азартных игр.",
                                   "\"Luck\" is a slot machine; it stays unavailable while the gambling filter is on."))
            }
        }
    }

    private func gameRow(_ raw: String) -> some View {
        let lockedByGambling: Bool = raw == "slots" && control.rules.blockGambling
        return Toggle(isOn: gameBinding(raw)) {
            Label(gameTitle(raw), systemImage: gameSymbol(raw))
        }
        .tint(HonorTheme.accent)
        .disabled(lockedByGambling)
        .accessibilityIdentifier("parental.toggle.allowedGames." + raw)
    }

    private func gameBinding(_ raw: String) -> Binding<Bool> {
        Binding<Bool>(
            get: { ContentGuard.isGameAllowed(raw, rules: control.rules) },
            set: { on in
                _ = control.update { r in
                    if on {
                        if !r.allowedGames.contains(raw) { r.allowedGames.append(raw) }
                    } else {
                        r.allowedGames.removeAll { $0 == raw }
                    }
                }
            }
        )
    }

    private func gameTitle(_ raw: String) -> String {
        switch raw {
        case "chess": return settings.text("Шахматы", "Chess")
        case "checkers": return settings.text("Шашки", "Checkers")
        case "durak": return settings.text("Дурак", "Durak")
        default: return settings.text("Удача (автомат)", "Luck (slots)")
        }
    }

    private func gameSymbol(_ raw: String) -> String {
        switch raw {
        case "chess": return "crown.fill"
        case "checkers": return "circle.grid.2x2.fill"
        case "durak": return "suit.spade.fill"
        default: return "dice.fill"
        }
    }
}

private struct ParentalSitesSection: View {
    @EnvironmentObject private var settings: AppSettings
    @ObservedObject var control: ParentalControl
    @State private var newSite: String = ""

    var body: some View {
        Section {
            ForEach(control.rules.blockedSites, id: \.self) { site in
                Label(site, systemImage: "nosign")
                    .foregroundStyle(.primary)
            }
            .onDelete { offsets in
                _ = control.update { r in r.blockedSites.remove(atOffsets: offsets) }
            }
            ParentalAddField(placeholder: "tiktok.com", text: $newSite, identifier: "parental.site.add",
                             keyboard: .URL, onAdd: { add() })
        } header: {
            Text(settings.text("Сайты", "Sites"))
        } footer: {
            Text(settings.text("Заблокированные сайты не откроются и не попадут в поиск. Поддомены тоже блокируются. Сайты для взрослых и казино блокируются всегда.",
                               "Blocked sites won't open or appear in search. Subdomains are blocked too. Adult and casino sites are always blocked."))
        }
    }

    private func add() {
        let domain = ContentGuard.normalizeDomain(newSite)
        guard !domain.isEmpty else { return }
        _ = control.update { r in
            if !r.blockedSites.contains(domain) { r.blockedSites.append(domain) }
        }
        newSite = ""
    }
}

private struct ParentalAllowedSitesSection: View {
    @EnvironmentObject private var settings: AppSettings
    @ObservedObject var control: ParentalControl
    @State private var newSite: String = ""

    var body: some View {
        Section {
            Toggle(isOn: control.binding(\ParentalRules.allowedSitesOnly)) {
                Label(settings.text("Только разрешённые сайты", "Allowed sites only"), systemImage: "checkmark.seal")
            }
            .tint(HonorTheme.accent)
            .accessibilityIdentifier("parental.toggle.allowedSitesOnly")
            if control.rules.allowedSitesOnly {
                allowedList
            }
        } footer: {
            Text(settings.text("В этом режиме открываются только сайты из списка, например wikipedia.org.",
                               "In this mode only the listed sites open, e.g. wikipedia.org."))
        }
    }

    @ViewBuilder
    private var allowedList: some View {
        ForEach(control.rules.allowedSites, id: \.self) { site in
            Label(site, systemImage: "checkmark.circle")
        }
        .onDelete { offsets in
            _ = control.update { r in r.allowedSites.remove(atOffsets: offsets) }
        }
        ParentalAddField(placeholder: "wikipedia.org", text: $newSite, identifier: "parental.allowedSite.add",
                         keyboard: .URL, onAdd: { add() })
    }

    private func add() {
        let domain = ContentGuard.normalizeDomain(newSite)
        guard !domain.isEmpty else { return }
        _ = control.update { r in
            if !r.allowedSites.contains(domain) { r.allowedSites.append(domain) }
        }
        newSite = ""
    }
}

private struct ParentalWordsSection: View {
    @EnvironmentObject private var settings: AppSettings
    @ObservedObject var control: ParentalControl
    @State private var newWord: String = ""

    private let columns: [GridItem] = [GridItem(.adaptive(minimum: 96), spacing: 8)]

    var body: some View {
        Section {
            if !control.rules.blockedWords.isEmpty {
                LazyVGrid(columns: columns, alignment: .leading, spacing: 8) {
                    ForEach(control.rules.blockedWords, id: \.self) { word in
                        ParentalChip(text: word, onRemove: { remove(word) })
                    }
                }
                .padding(.vertical, 4)
            }
            ParentalAddField(placeholder: settings.text("Новое слово", "New word"), text: $newWord,
                             identifier: "parental.blockedWord.add", keyboard: .default, onAdd: { add() })
        } header: {
            Text(settings.text("Запрещённые слова", "Blocked words"))
        } footer: {
            Text(settings.text("Сообщения с этими словами не отправятся, а в ответах слова скрываются точками. Окончания учитываются.",
                               "Messages with these words won't be sent, and they are hidden with dots in answers. Word endings are included."))
        }
    }

    private func add() {
        let word = newWord.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !word.isEmpty else { return }
        _ = control.update { r in
            let exists = r.blockedWords.contains { $0.lowercased() == word.lowercased() }
            if !exists { r.blockedWords.append(word) }
        }
        newWord = ""
    }

    private func remove(_ word: String) {
        _ = control.update { r in r.blockedWords.removeAll { $0 == word } }
    }
}

private struct ParentalChip: View {
    let text: String
    let onRemove: () -> Void

    var body: some View {
        HStack(spacing: 6) {
            Text(text)
                .font(.system(size: 14, weight: .medium))
                .lineLimit(1)
            Button(action: onRemove) {
                Image(systemName: "xmark.circle.fill")
                    .font(.system(size: 14))
                    .foregroundStyle(.secondary)
            }
            .buttonStyle(.borderless)
            .accessibilityIdentifier("parental.blockedWord.remove." + text)
        }
        .padding(.horizontal, 10)
        .padding(.vertical, 6)
        .background(Capsule().fill(HonorTheme.accent.opacity(0.16)))
        .transition(.scale.combined(with: .opacity))
    }
}

private struct ParentalAddField: View {
    let placeholder: String
    @Binding var text: String
    let identifier: String
    let keyboard: UIKeyboardType
    let onAdd: () -> Void

    var body: some View {
        HStack(spacing: 10) {
            TextField(placeholder, text: $text)
                .keyboardType(keyboard)
                .textInputAutocapitalization(.never)
                .autocorrectionDisabled(true)
                .submitLabel(.done)
                .onSubmit { onAdd() }
                .accessibilityIdentifier(identifier + ".field")
            Button(action: onAdd) {
                Image(systemName: "plus.circle.fill")
                    .font(.system(size: 24))
                    .foregroundStyle(isEmpty ? Color.secondary : HonorTheme.accent)
            }
            .buttonStyle(.borderless)
            .disabled(isEmpty)
            .accessibilityIdentifier(identifier)
        }
    }

    private var isEmpty: Bool {
        text.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
    }
}

private struct ParentalTimeSection: View {
    @EnvironmentObject private var settings: AppSettings
    @ObservedObject var control: ParentalControl

    static let limitOptions: [Int] = [0, 15, 30, 45, 60, 90, 120, 180]

    var body: some View {
        Section {
            limitPicker
            Toggle(isOn: control.binding(\ParentalRules.quietHoursEnabled)) {
                Label(settings.text("Тихие часы", "Quiet hours"), systemImage: "moon.zzz")
            }
            .tint(HonorTheme.accent)
            .accessibilityIdentifier("parental.toggle.quietHoursEnabled")
            if control.rules.quietHoursEnabled {
                DatePicker(settings.text("Начало", "Start"),
                           selection: timeBinding(\ParentalRules.quietStart),
                           displayedComponents: .hourAndMinute)
                    .accessibilityIdentifier("parental.quietStart")
                DatePicker(settings.text("Конец", "End"),
                           selection: timeBinding(\ParentalRules.quietEnd),
                           displayedComponents: .hourAndMinute)
                    .accessibilityIdentifier("parental.quietEnd")
            }
        } header: {
            Text(settings.text("Время", "Time"))
        } footer: {
            Text(settings.text("Когда время закончилось или идут тихие часы, чат закрывается. Родитель может открыть его до конца дня по PIN-коду.",
                               "When time is up or during quiet hours the chat is locked. A parent can unlock it for the rest of the day with the PIN."))
        }
    }

    private var limitPicker: some View {
        Picker(selection: control.binding(\ParentalRules.dailyLimitMinutes)) {
            ForEach(ParentalTimeSection.limitOptions, id: \.self) { minutes in
                Text(limitTitle(minutes)).tag(minutes)
            }
        } label: {
            Label(settings.text("Лимит в день", "Daily limit"), systemImage: "hourglass")
        }
        .pickerStyle(.menu)
        .accessibilityIdentifier("parental.limit")
    }

    private func limitTitle(_ minutes: Int) -> String {
        if minutes == 0 { return settings.text("Нет", "None") }
        if minutes < 60 || minutes % 60 != 0 {
            return settings.text("\(minutes) мин", "\(minutes) min")
        }
        let hours = minutes / 60
        return settings.text("\(hours) ч", "\(hours) h")
    }

    private func timeBinding(_ keyPath: WritableKeyPath<ParentalRules, Int>) -> Binding<Date> {
        Binding<Date>(
            get: { ParentalStyle.date(fromMinutes: control.rules[keyPath: keyPath]) },
            set: { date in
                let minutes = ParentalMath.minutesOfDay(date)
                _ = control.update { r in r[keyPath: keyPath] = minutes }
            }
        )
    }
}

private struct ParentalAnswersSection: View {
    @EnvironmentObject private var settings: AppSettings
    @ObservedObject var control: ParentalControl

    var body: some View {
        Section {
            Stepper(value: control.binding(\ParentalRules.childAge), in: 6...17) {
                Label(ageTitle, systemImage: "figure.child")
            }
            .accessibilityIdentifier("parental.childAge")
            Picker(settings.text("Стиль ответов", "Answer style"), selection: control.binding(\ParentalRules.answerStyle)) {
                Text(settings.text("Проще", "Simpler")).tag("simple")
                Text(settings.text("Обычно", "Normal")).tag("normal")
            }
            .pickerStyle(.segmented)
            .accessibilityIdentifier("parental.answerStyle")
        } header: {
            Text(settings.text("Ответы для ребёнка", "Answers for the child"))
        } footer: {
            Text(settings.text("Возраст нужен только чтобы подобрать понятные слова. Он никогда не включает контроль сам.",
                               "Age is only used to choose clear wording. It never turns control on by itself."))
        }
    }

    private var ageTitle: String {
        let age = control.rules.childAge
        return settings.text("Возраст: \(age)", "Age: \(age)")
    }
}

private struct ParentalPINSection: View {
    @EnvironmentObject private var settings: AppSettings
    @ObservedObject var control: ParentalControl
    @Binding var sheet: ParentalSheet?

    var body: some View {
        Section {
            Button { sheet = .changePIN } label: {
                Label(settings.text("Сменить PIN", "Change PIN"), systemImage: "key")
            }
            .accessibilityIdentifier("parental.pin.change")
            Button { control.lock() } label: {
                Label(settings.text("Закрыть настройки", "Lock settings"), systemImage: "lock")
            }
            .accessibilityIdentifier("parental.lock")
            if control.rules.enabled {
                Button(role: .destructive) { sheet = .disable } label: {
                    Label(settings.text("Выключить родительский контроль", "Turn off parental control"), systemImage: "power")
                }
                .accessibilityIdentifier("parental.disable")
            }
            Button(role: .destructive) { sheet = .reset } label: {
                Label(settings.text("Удалить PIN и сбросить настройки", "Delete PIN and reset settings"), systemImage: "trash")
            }
            .accessibilityIdentifier("parental.reset")
        } header: {
            Text("PIN")
        } footer: {
            Text(settings.text("Настройки закрываются сами через 5 минут без действий и при выходе со страницы.",
                               "Settings lock automatically after 5 minutes of inactivity and when you leave this page."))
        }
    }
}

// MARK: - Экран блокировки чата

/// Полноэкранная заглушка поверх чата, когда закончился лимит или идут тихие часы.
struct ParentalLockScreen: View {
    @EnvironmentObject private var settings: AppSettings
    @ObservedObject var control: ParentalControl = ParentalControl.shared
    @State private var askingPIN: Bool = false
    @State private var appeared: Bool = false

    var body: some View {
        ZStack {
            backdrop.ignoresSafeArea()
            VStack(spacing: 22) {
                Spacer(minLength: 20)
                illustration
                Text(title)
                    .font(.system(size: 26, weight: .bold))
                    .foregroundStyle(Color.white)
                    .multilineTextAlignment(.center)
                Text(reason)
                    .font(.system(size: 16))
                    .foregroundStyle(Color.white.opacity(0.85))
                    .multilineTextAlignment(.center)
                    .fixedSize(horizontal: false, vertical: true)
                    .accessibilityIdentifier("parental.lockscreen.reason")
                Spacer(minLength: 20)
                unlockButton
            }
            .padding(.horizontal, 28)
            .padding(.bottom, 24)
        }
        .accessibilityIdentifier("parental.lockscreen")
        .onAppear {
            withAnimation(.spring(response: 0.8, dampingFraction: 0.6)) { appeared = true }
        }
        .sheet(isPresented: $askingPIN) {
            ParentalPINPromptSheet(
                control: control,
                title: settings.text("Открыть до конца дня", "Unlock for today"),
                subtitle: settings.text("Введите PIN родителя.", "Enter the parent PIN."),
                symbol: "lock.open",
                action: { pin in control.unlockForToday(pin: pin) }
            )
            .environmentObject(settings)
        }
    }

    private var isQuiet: Bool { control.blockKind == .quietHours }

    private var title: String {
        isQuiet
            ? settings.text("Время отдыхать", "Time to rest")
            : settings.text("На сегодня всё", "That's all for today")
    }

    private var reason: String {
        control.blockReasonText(english: settings.language == .english) ?? ""
    }

    private var backdrop: LinearGradient {
        let colors: [Color] = isQuiet
            ? [Color(red: 0.07, green: 0.09, blue: 0.24), Color(red: 0.2, green: 0.14, blue: 0.4)]
            : [Color(red: 0.95, green: 0.5, blue: 0.3), Color(red: 0.62, green: 0.25, blue: 0.55)]
        return LinearGradient(colors: colors, startPoint: .top, endPoint: .bottom)
    }

    private var illustration: some View {
        ZStack {
            Circle()
                .fill(Color.white.opacity(0.08))
                .frame(width: 190, height: 190)
                .scaleEffect(appeared ? 1 : 0.6)
            Circle()
                .fill(Color.white.opacity(0.12))
                .frame(width: 136, height: 136)
                .scaleEffect(appeared ? 1 : 0.7)
            Image(systemName: isQuiet ? "moon.zzz.fill" : "hourglass")
                .font(.system(size: 60, weight: .semibold))
                .foregroundStyle(Color.white)
                .rotationEffect(.degrees(appeared ? 0 : -25))
        }
        .opacity(appeared ? 1 : 0)
    }

    private var unlockButton: some View {
        Button { askingPIN = true } label: {
            Label(settings.text("Родитель: ввести PIN", "Parent: enter PIN"), systemImage: "lock.open.fill")
                .font(.system(size: 16, weight: .semibold))
                .foregroundStyle(Color.white)
                .frame(maxWidth: .infinity)
                .padding(.vertical, 15)
                .background(Color.white.opacity(0.18), in: Capsule())
                .overlay(Capsule().stroke(Color.white.opacity(0.35), lineWidth: 1))
        }
        .buttonStyle(.plain)
        .accessibilityIdentifier("parental.lockscreen.unlock")
    }
}

/// Накладывает `ParentalLockScreen` поверх контента и ведёт учёт времени, пока приложение активно.
struct ParentalGateModifier: ViewModifier {
    @ObservedObject var control: ParentalControl
    @Environment(\.scenePhase) private var scenePhase

    func body(content: Content) -> some View {
        ZStack {
            content
            if control.blockKind != nil {
                ParentalLockScreen(control: control)
                    .transition(.opacity.combined(with: .scale(scale: 1.04)))
                    .zIndex(10)
            }
        }
        .animation(.easeInOut(duration: 0.35), value: control.blockKind)
        .onAppear { control.setAppActive(scenePhase == .active) }
        .onChange(of: scenePhase) { newPhase in
            control.setAppActive(newPhase == .active)
        }
    }
}

extension View {
    /// Подключить родительский контроль к экрану чата: экран блокировки + учёт времени.
    @MainActor
    func parentalGate() -> some View {
        modifier(ParentalGateModifier(control: ParentalControl.shared))
    }
}

// MARK: - Общие элементы

enum ParentalStyle {
    static var accentGradient: LinearGradient {
        LinearGradient(colors: [Color(red: 0.36, green: 0.55, blue: 1), Color(red: 0.55, green: 0.38, blue: 0.98)],
                       startPoint: .topLeading, endPoint: .bottomTrailing)
    }

    static var mutedGradient: LinearGradient {
        LinearGradient(colors: [Color.gray.opacity(0.7), Color.gray.opacity(0.45)],
                       startPoint: .topLeading, endPoint: .bottomTrailing)
    }

    static func date(fromMinutes minutes: Int) -> Date {
        let m = ((minutes % 1440) + 1440) % 1440
        let start = Calendar.current.startOfDay(for: Date())
        return Calendar.current.date(byAdding: .minute, value: m, to: start) ?? start
    }
}

private struct ParentalIconBadge: View {
    let symbol: String
    let tint: Color

    var body: some View {
        Image(systemName: symbol)
            .font(.system(size: 14, weight: .semibold))
            .foregroundStyle(Color.white)
            .frame(width: 30, height: 30)
            .background(RoundedRectangle(cornerRadius: 8, style: .continuous).fill(tint.gradient))
    }
}

private struct ParentalHeroIcon: View {
    let symbol: String
    let badge: String?
    @State private var appeared: Bool = false

    var body: some View {
        ZStack(alignment: .bottomTrailing) {
            ZStack {
                Circle()
                    .fill(ParentalStyle.accentGradient)
                    .frame(width: 96, height: 96)
                    .shadow(color: HonorTheme.accent.opacity(0.35), radius: 16, y: 8)
                Image(systemName: symbol)
                    .font(.system(size: 42, weight: .semibold))
                    .foregroundStyle(Color.white)
            }
            if let badge = badge {
                Image(systemName: badge)
                    .font(.system(size: 18, weight: .bold))
                    .foregroundStyle(Color.white)
                    .frame(width: 36, height: 36)
                    .background(Circle().fill(Color.green))
                    .overlay(Circle().stroke(HonorTheme.background, lineWidth: 3))
            }
        }
        .scaleEffect(appeared ? 1 : 0.7)
        .opacity(appeared ? 1 : 0)
        .onAppear {
            withAnimation(.spring(response: 0.55, dampingFraction: 0.65)) { appeared = true }
        }
        .accessibilityHidden(true)
    }
}
