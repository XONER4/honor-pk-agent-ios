import AVFoundation
import Contacts
import CoreLocation
import Photos
import Speech
import SwiftUI
import UserNotifications

/// Разрешения приложения: что уже открыто и как открыть остальное.
@MainActor
final class PermissionsCenter: NSObject, ObservableObject, CLLocationManagerDelegate {
    static let shared = PermissionsCenter()

    enum Kind: String, CaseIterable, Identifiable {
        case notifications, microphone, speech, camera, photos, contacts, location
        var id: String { rawValue }
    }

    enum State: Equatable {
        case granted, denied, notAsked, limited
    }

    @Published private(set) var states: [Kind: State] = [:]
    private let locationManager = CLLocationManager()
    private var locationContinuation: CheckedContinuation<Void, Never>?

    override init() {
        super.init()
        locationManager.delegate = self
        locationManager.desiredAccuracy = kCLLocationAccuracyKilometer
    }

    func refresh() async {
        var result: [Kind: State] = [:]
        let notifications = await UNUserNotificationCenter.current().notificationSettings().authorizationStatus
        result[.notifications] = notifications == .authorized || notifications == .provisional || notifications == .ephemeral
            ? .granted : (notifications == .notDetermined ? .notAsked : .denied)
        switch AVAudioSession.sharedInstance().recordPermission {
        case .granted: result[.microphone] = .granted
        case .denied: result[.microphone] = .denied
        default: result[.microphone] = .notAsked
        }
        switch SFSpeechRecognizer.authorizationStatus() {
        case .authorized: result[.speech] = .granted
        case .notDetermined: result[.speech] = .notAsked
        default: result[.speech] = .denied
        }
        switch AVCaptureDevice.authorizationStatus(for: .video) {
        case .authorized: result[.camera] = .granted
        case .notDetermined: result[.camera] = .notAsked
        default: result[.camera] = .denied
        }
        switch PHPhotoLibrary.authorizationStatus(for: .readWrite) {
        case .authorized: result[.photos] = .granted
        case .limited: result[.photos] = .limited
        case .notDetermined: result[.photos] = .notAsked
        default: result[.photos] = .denied
        }
        switch CNContactStore.authorizationStatus(for: .contacts) {
        case .authorized: result[.contacts] = .granted
        case .notDetermined: result[.contacts] = .notAsked
        case .restricted, .denied: result[.contacts] = .denied
        default: result[.contacts] = .limited
        }
        switch locationManager.authorizationStatus {
        case .authorizedAlways, .authorizedWhenInUse: result[.location] = .granted
        case .notDetermined: result[.location] = .notAsked
        default: result[.location] = .denied
        }
        states = result
    }

    /// Запросить разрешение. Если оно уже отклонено, открываются настройки iPhone.
    func request(_ kind: Kind) async {
        if states[kind] == .denied {
            openSettings()
            return
        }
        switch kind {
        case .notifications:
            _ = await NotificationCenterService.shared.ensureNotifications()
        case .microphone:
            _ = await withCheckedContinuation { continuation in
                AVAudioSession.sharedInstance().requestRecordPermission { continuation.resume(returning: $0) }
            }
        case .speech:
            _ = await withCheckedContinuation { continuation in
                SFSpeechRecognizer.requestAuthorization { continuation.resume(returning: $0) }
            }
        case .camera:
            _ = await AVCaptureDevice.requestAccess(for: .video)
        case .photos:
            _ = await PHPhotoLibrary.requestAuthorization(for: .readWrite)
        case .contacts:
            _ = try? await CNContactStore().requestAccess(for: .contacts)
        case .location:
            guard locationManager.authorizationStatus == .notDetermined else {
                updateLocation(force: true)
                break
            }
            await withCheckedContinuation { (continuation: CheckedContinuation<Void, Never>) in
                locationContinuation = continuation
                locationManager.requestWhenInUseAuthorization()
            }
            updateLocation()
        }
        await refresh()
    }

    func openSettings() {
        if let url = URL(string: UIApplication.openSettingsURLString) { UIApplication.shared.open(url) }
    }

    /// Обновить город пользователя, если разрешена геопозиция (не чаще раза в час).
    func updateLocation(force: Bool = false) {
        guard ParentalControl.shared.rules.canUseLocation else {
            UserDefaults.standard.removeObject(forKey: DeviceContext.cityKey)
            return
        }
        let status = locationManager.authorizationStatus
        guard status == .authorizedWhenInUse || status == .authorizedAlways else { return }
        if !force, let updated = UserDefaults.standard.object(forKey: DeviceContext.cityUpdatedKey) as? Date,
           Date().timeIntervalSince(updated) < 3600 { return }
        locationManager.requestLocation()
    }

    nonisolated func locationManagerDidChangeAuthorization(_ manager: CLLocationManager) {
        Task { @MainActor in
            self.locationContinuation?.resume()
            self.locationContinuation = nil
            await self.refresh()
        }
    }

    nonisolated func locationManager(_ manager: CLLocationManager, didUpdateLocations locations: [CLLocation]) {
        guard let location = locations.last else { return }
        CLGeocoder().reverseGeocodeLocation(location, preferredLocale: Locale(identifier: "ru_RU")) { placemarks, _ in
            guard let place = placemarks?.first else { return }
            let parts = [place.locality ?? place.subAdministrativeArea, place.administrativeArea, place.country].compactMap { $0 }
            var unique: [String] = []
            for part in parts where !unique.contains(part) { unique.append(part) }
            guard !unique.isEmpty else { return }
            UserDefaults.standard.set(unique.joined(separator: ", "), forKey: DeviceContext.cityKey)
            UserDefaults.standard.set(Date(), forKey: DeviceContext.cityUpdatedKey)
        }
    }

    nonisolated func locationManager(_ manager: CLLocationManager, didFailWithError error: Error) {}
}

/// Сведения об устройстве и месте пользователя для модели: город (если разрешена
/// геопозиция), регион, часовой пояс и язык системы.
enum DeviceContext {
    static let cityKey = "honor.deviceCity"
    static let cityUpdatedKey = "honor.deviceCityUpdated"

    static func summary(now: Date = Date()) -> String {
        var lines: [String] = []
        lines.append("Устройство: \(DeviceModel.name), \(DeviceModel.osDescription)")
        if let city = UserDefaults.standard.string(forKey: cityKey), !city.isEmpty {
            lines.append("Местоположение по геопозиции: \(city)")
        }
        let locale = Locale.current
        if let region = locale.region?.identifier {
            let name = Locale(identifier: "ru_RU").localizedString(forRegionCode: region) ?? region
            lines.append("Регион в настройках iPhone: \(name)")
        }
        let zone = TimeZone.current
        let offset = zone.secondsFromGMT(for: now) / 3600
        lines.append("Часовой пояс: \(zone.identifier) (UTC\(offset >= 0 ? "+" : "")\(offset))")
        if let language = Locale.preferredLanguages.first {
            lines.append("Язык системы: \(Locale(identifier: "ru_RU").localizedString(forIdentifier: language) ?? language)")
        }
        return "\nСведения об устройстве пользователя (используй, когда это помогает ответу — погода, время, местные цены, расписания; не пересказывай без повода):\n" + lines.map { "• " + $0 }.joined(separator: "\n")
    }
}

/// Поиск контакта по имени — для инструмента find_contact.
enum ContactLookup {
    static func execute(_ call: ToolCallRequest) async -> ToolCallResult {
        let query = (ToolArgument.string(call.parsedArguments["name"]) ?? "").trimmingCharacters(in: .whitespacesAndNewlines)
        guard !query.isEmpty else {
            return ToolCallResult(callID: call.id, name: call.name, content: "Не передано имя контакта.")
        }
        guard CNContactStore.authorizationStatus(for: .contacts) == .authorized else {
            return ToolCallResult(callID: call.id, name: call.name,
                                  content: "Доступ к контактам не разрешён. Предложи пользователю открыть Настройки → Разрешения в приложении и разрешить контакты.")
        }
        let found: [String] = await Task.detached(priority: .userInitiated) {
            let store = CNContactStore()
            let keys: [CNKeyDescriptor] = [CNContactGivenNameKey, CNContactFamilyNameKey, CNContactOrganizationNameKey,
                                           CNContactPhoneNumbersKey, CNContactEmailAddressesKey, CNContactBirthdayKey] as [CNKeyDescriptor]
            let predicate = CNContact.predicateForContacts(matchingName: query)
            let contacts = (try? store.unifiedContacts(matching: predicate, keysToFetch: keys)) ?? []
            return contacts.prefix(5).map { contact in
                var parts = [[contact.givenName, contact.familyName].filter { !$0.isEmpty }.joined(separator: " ")]
                if !contact.organizationName.isEmpty { parts.append("организация: \(contact.organizationName)") }
                let phones = contact.phoneNumbers.map { $0.value.stringValue }
                if !phones.isEmpty { parts.append("телефоны: " + phones.joined(separator: ", ")) }
                let emails = contact.emailAddresses.map { String($0.value) }
                if !emails.isEmpty { parts.append("почта: " + emails.joined(separator: ", ")) }
                if let birthday = contact.birthday, let day = birthday.day, let month = birthday.month {
                    parts.append("день рождения: \(day).\(month)" + (birthday.year.map { ".\($0)" } ?? ""))
                }
                return parts.joined(separator: "; ")
            }
        }.value
        guard !found.isEmpty else {
            return ToolCallResult(callID: call.id, name: call.name, content: "Контакт «\(query)» не найден.")
        }
        return ToolCallResult(callID: call.id, name: call.name, content: "Найденные контакты:\n" + found.map { "• " + $0 }.joined(separator: "\n"))
    }
}

/// Страница «Разрешения» в настройках.
struct PermissionsPage: View {
    @EnvironmentObject private var settings: AppSettings
    @ObservedObject private var center = PermissionsCenter.shared

    var body: some View {
        List {
            Section {
                ForEach(PermissionsCenter.Kind.allCases) { kind in
                    row(kind)
                }
            } footer: {
                Text(settings.text("Разрешения нужны только для функций, которыми вы пользуетесь. Геопозиция помогает Honer AI знать ваш город и местное время, контакты — находить людей по имени. Отключить доступ можно в настройках iPhone.",
                                   "Permissions are used only for features you use. You can revoke them in iPhone Settings."))
            }
            Section {
                Button(settings.text("Открыть настройки iPhone", "Open iPhone Settings")) { center.openSettings() }
                    .accessibilityIdentifier("permissions.openSettings")
            }
        }
        .navigationTitle(settings.text("Разрешения", "Permissions"))
        .navigationBarTitleDisplayMode(.inline)
        .accessibilityIdentifier("settings.page.permissions")
        .task { await center.refresh() }
        .onReceive(NotificationCenter.default.publisher(for: UIApplication.didBecomeActiveNotification)) { _ in
            Task { await center.refresh() }
        }
    }

    private func row(_ kind: PermissionsCenter.Kind) -> some View {
        let state = center.states[kind] ?? .notAsked
        return HStack(spacing: 12) {
            Image(systemName: symbol(kind))
                .font(.system(size: 17))
                .foregroundStyle(HonorTheme.accent)
                .frame(width: 28)
            VStack(alignment: .leading, spacing: 2) {
                Text(title(kind))
                Text(detail(kind)).font(.caption).foregroundStyle(.secondary)
            }
            Spacer(minLength: 8)
            switch state {
            case .granted:
                Label(settings.text("Разрешено", "Allowed"), systemImage: "checkmark.circle.fill")
                    .labelStyle(.titleAndIcon).font(.caption.weight(.semibold)).foregroundStyle(.green)
            case .limited:
                Label(settings.text("Частично", "Limited"), systemImage: "circle.lefthalf.filled")
                    .font(.caption.weight(.semibold)).foregroundStyle(.orange)
            case .denied, .notAsked:
                Button(state == .denied ? settings.text("Открыть", "Open") : settings.text("Разрешить", "Allow")) {
                    Task { await center.request(kind) }
                }
                .font(.caption.weight(.semibold))
                .buttonStyle(.borderedProminent)
                .accessibilityIdentifier("permissions.request." + kind.rawValue)
            }
        }
        .accessibilityElement(children: .contain)
        .accessibilityIdentifier("permissions.row." + kind.rawValue)
    }

    private func symbol(_ kind: PermissionsCenter.Kind) -> String {
        switch kind {
        case .notifications: return "bell.badge"
        case .microphone: return "mic"
        case .speech: return "waveform"
        case .camera: return "camera"
        case .photos: return "photo.on.rectangle"
        case .contacts: return "person.crop.circle"
        case .location: return "location"
        }
    }

    private func title(_ kind: PermissionsCenter.Kind) -> String {
        switch kind {
        case .notifications: return settings.text("Уведомления", "Notifications")
        case .microphone: return settings.text("Микрофон", "Microphone")
        case .speech: return settings.text("Распознавание речи", "Speech recognition")
        case .camera: return settings.text("Камера", "Camera")
        case .photos: return settings.text("Фото", "Photos")
        case .contacts: return settings.text("Контакты", "Contacts")
        case .location: return settings.text("Геопозиция", "Location")
        }
    }

    private func detail(_ kind: PermissionsCenter.Kind) -> String {
        switch kind {
        case .notifications: return settings.text("Сообщить, что ответ готов", "Tell you when an answer is ready")
        case .microphone: return settings.text("Голосовой ввод", "Voice input")
        case .speech: return settings.text("Превращает голос в текст", "Turns speech into text")
        case .camera: return settings.text("Снимок для вопроса", "Take a photo for a question")
        case .photos: return settings.text("Выбор фото и видео", "Pick photos and videos")
        case .contacts: return settings.text("Найти человека по имени", "Find a person by name")
        case .location: return settings.text("Ваш город и местное время", "Your city and local time")
        }
    }
}
