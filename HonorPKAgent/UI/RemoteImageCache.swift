import SwiftUI
import UIKit

/// Кэш картинок из ответов и превью ссылок.
///
/// `AsyncImage` перезагружает картинку каждый раз, когда представление пересобирается.
/// Пока ответ печатается, это происходит десятки раз в секунду: картинка мигала, а
/// сеть получала повторные запросы. Здесь результат складывается в память (и на диск
/// в системный кэш), поэтому картинка появляется один раз и больше не пропадает.
@MainActor
enum RemoteImageCache {
    private static let images = NSCache<NSString, UIImage>()
    private static var tasks: [String: Task<UIImage?, Never>] = [:]

    /// Уже загруженная картинка, если она есть в памяти.
    static func cached(_ url: URL) -> UIImage? {
        images.object(forKey: url.absoluteString as NSString)
    }

    /// Загрузить картинку один раз и запомнить. Повторные вызовы ждут тот же запрос.
    static func load(_ url: URL) async -> UIImage? {
        let key = url.absoluteString
        if let ready = images.object(forKey: key as NSString) { return ready }
        if let running = tasks[key] { return await running.value }
        let task = Task<UIImage?, Never> { () -> UIImage? in
            var request = URLRequest(url: url)
            request.timeoutInterval = 20
            request.setValue("HonorPKAgent/1.0", forHTTPHeaderField: "User-Agent")
            guard let (data, response) = try? await URLSession.shared.data(for: request),
                  data.count <= 8 * 1024 * 1024 else { return nil }
            if let http = response as? HTTPURLResponse, !(200...299).contains(http.statusCode) { return nil }
            guard let image = UIImage(data: data) else { return nil }
            // Уменьшаем большие картинки: держать в памяти оригинал на 12 Мп нельзя.
            let side: CGFloat = 1400
            let scale = min(side / max(image.size.width, 1), side / max(image.size.height, 1), 1)
            guard scale < 1 else { return image }
            let target = CGSize(width: image.size.width * scale, height: image.size.height * scale)
            let renderer = UIGraphicsImageRenderer(size: target)
            return renderer.image { _ in image.draw(in: CGRect(origin: .zero, size: target)) }
        }
        tasks[key] = task
        let image = await task.value
        tasks[key] = nil
        if let image { images.setObject(image, forKey: key as NSString) }
        return image
    }
}

/// Картинка превью ссылки: полоса высотой 120 pt, из кэша.
struct CachedPreviewImage: View {
    let url: URL

    @State private var image: UIImage?

    var body: some View {
        Group {
            if let image {
                Image(uiImage: image)
                    .resizable()
                    .scaledToFill()
                    .frame(height: 120)
                    .clipped()
            } else {
                Color.clear.frame(height: 0)
            }
        }
        .task(id: url.absoluteString) {
            if let ready = RemoteImageCache.cached(url) { image = ready; return }
            if let loaded = await RemoteImageCache.load(url) { image = loaded }
        }
    }
}

/// Картинка из ответа: из кэша, в аккуратной рамке, с увеличением по нажатию.
struct CachedRemoteImage: View {
    let url: URL
    let caption: String
    let fontSize: Double
    /// Показать картинку на весь экран по нажатию.
    var allowsFullScreen: Bool = true

    @State private var image: UIImage?
    @State private var failed = false
    @State private var showsFullScreen = false

    var body: some View {
        VStack(alignment: .leading, spacing: 5) {
            Group {
                if let image {
                    Image(uiImage: image)
                        .resizable()
                        .scaledToFit()
                        .frame(maxHeight: 320)
                } else if failed {
                    HStack(spacing: 7) {
                        Image(systemName: "photo.badge.exclamationmark")
                        Text("Не удалось загрузить изображение")
                    }
                    .font(.system(size: fontSize * 0.85))
                    .foregroundStyle(HonorTheme.secondary)
                    .padding(.vertical, 10)
                } else {
                    RoundedRectangle(cornerRadius: 10)
                        .fill(HonorTheme.surface)
                        .frame(height: 140)
                        .overlay(ProgressView())
                }
            }
            .clipShape(RoundedRectangle(cornerRadius: 12, style: .continuous))
            .overlay(RoundedRectangle(cornerRadius: 12, style: .continuous)
                .stroke(HonorTheme.divider, lineWidth: 0.6))
            .frame(maxWidth: .infinity, alignment: .leading)
            .contentShape(RoundedRectangle(cornerRadius: 12, style: .continuous))
            .onTapGesture { if image != nil && allowsFullScreen { showsFullScreen = true } }

            if !caption.isEmpty {
                Text(caption)
                    .font(.system(size: fontSize * 0.78))
                    .foregroundStyle(HonorTheme.secondary)
                    .fixedSize(horizontal: false, vertical: true)
            }
        }
        .accessibilityIdentifier("message.image")
        .accessibilityLabel(caption.isEmpty ? "Изображение в ответе" : "Изображение: \(caption)")
        .task(id: url.absoluteString) {
            if let ready = RemoteImageCache.cached(url) {
                image = ready
                return
            }
            let loaded = await RemoteImageCache.load(url)
            if let loaded { image = loaded } else { failed = true }
        }
        .fullScreenCover(isPresented: $showsFullScreen) {
            if let image {
                ZStack {
                    Color.black.ignoresSafeArea()
                    Image(uiImage: image)
                        .resizable()
                        .scaledToFit()
                        .padding(12)
                    VStack {
                        HStack {
                            Spacer()
                            Button { showsFullScreen = false } label: {
                                Image(systemName: "xmark")
                                    .font(.system(size: 15, weight: .bold))
                                    .frame(width: 40, height: 40)
                                    .background(.ultraThinMaterial, in: Circle())
                                    .foregroundStyle(.white)
                            }
                            .accessibilityLabel("Закрыть изображение")
                            .accessibilityIdentifier("message.image.close")
                        }
                        Spacer()
                    }
                    .padding(16)
                }
            }
        }
    }
}
