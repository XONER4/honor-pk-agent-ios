import AVKit
import SwiftUI
import WebKit

/// Видео в ответе: обложка с кнопкой воспроизведения. По нажатию ролик играет
/// прямо в приложении — YouTube во встроенном плеере, файл видео системным плеером.
struct VideoCardView: View {
    let url: URL
    let caption: String
    let fontSize: Double

    @State private var playing = false

    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            Button { playing = true } label: {
                ZStack {
                    RoundedRectangle(cornerRadius: 12, style: .continuous).fill(HonorTheme.surface)
                    if let thumbnail = MediaLinks.videoThumbnail(url) {
                        VideoThumbnail(url: thumbnail)
                    }
                    Image(systemName: "play.circle.fill")
                        .font(.system(size: 54))
                        .symbolRenderingMode(.palette)
                        .foregroundStyle(.white, .black.opacity(0.45))
                        .shadow(radius: 4)
                }
                .frame(maxWidth: .infinity)
                .aspectRatio(16 / 9, contentMode: .fit)
                .clipShape(RoundedRectangle(cornerRadius: 12, style: .continuous))
                .overlay(RoundedRectangle(cornerRadius: 12, style: .continuous).stroke(HonorTheme.divider, lineWidth: 0.6))
                .contentShape(RoundedRectangle(cornerRadius: 12, style: .continuous))
            }
            .buttonStyle(.plain)
            .accessibilityLabel(caption.isEmpty ? "Видео" : "Видео: \(caption)")
            .accessibilityHint("Нажмите, чтобы посмотреть")
            .accessibilityIdentifier("message.video")
            if !caption.isEmpty {
                Label(caption, systemImage: "play.rectangle")
                    .font(.system(size: fontSize * 0.78))
                    .foregroundStyle(HonorTheme.secondary)
                    .fixedSize(horizontal: false, vertical: true)
            }
        }
        .sheet(isPresented: $playing) { VideoPlayerSheet(url: url, title: caption) }
    }
}

/// Обложка ролика из общего кэша картинок.
private struct VideoThumbnail: View {
    let url: URL
    @State private var image: UIImage?

    var body: some View {
        Group {
            if let image {
                Image(uiImage: image).resizable().scaledToFill()
            } else {
                ProgressView()
            }
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .clipped()
        .task(id: url.absoluteString) {
            if let ready = RemoteImageCache.cached(url) { image = ready; return }
            image = await RemoteImageCache.load(url)
        }
    }
}

/// Плеер видео в отдельном окне.
struct VideoPlayerSheet: View {
    let url: URL
    let title: String

    @Environment(\.dismiss) private var dismiss
    @Environment(\.openURL) private var openURL
    @State private var player: AVPlayer?

    private var isFile: Bool { ["mp4", "mov", "m4v", "m3u8"].contains(url.pathExtension.lowercased()) }

    var body: some View {
        NavigationStack {
            ZStack {
                Color.black.ignoresSafeArea()
                if let id = MediaLinks.youTubeID(url) {
                    YouTubePlayerView(videoID: id)
                        .aspectRatio(16 / 9, contentMode: .fit)
                        .accessibilityIdentifier("video.player")
                } else if isFile {
                    VideoPlayer(player: player)
                        .accessibilityIdentifier("video.player")
                } else {
                    WebVideoView(url: url)
                        .accessibilityIdentifier("video.player")
                }
            }
            .navigationTitle(title.isEmpty ? "Видео" : title)
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Закрыть") { player?.pause(); dismiss() }
                        .accessibilityIdentifier("video.close")
                }
                ToolbarItem(placement: .primaryAction) {
                    Button { openURL(url) } label: { Image(systemName: "safari") }
                        .accessibilityLabel("Открыть в браузере")
                        .accessibilityIdentifier("video.open")
                }
            }
        }
        .onAppear {
            if isFile, player == nil {
                let created = AVPlayer(url: url)
                player = created
                created.play()
            }
        }
        .onDisappear { player?.pause() }
    }
}

/// Встроенный плеер YouTube. Страница загружается с адресом youtube.com как
/// источником: без него YouTube отказывается воспроизводить встроенный ролик.
struct YouTubePlayerView: UIViewRepresentable {
    let videoID: String

    func makeUIView(context: Context) -> WKWebView {
        let configuration = WKWebViewConfiguration()
        configuration.allowsInlineMediaPlayback = true
        configuration.mediaTypesRequiringUserActionForPlayback = []
        let view = WKWebView(frame: .zero, configuration: configuration)
        view.isOpaque = false
        view.backgroundColor = .black
        view.scrollView.isScrollEnabled = false
        let html = """
        <!doctype html><html><head>
        <meta name="viewport" content="width=device-width,initial-scale=1,maximum-scale=1">
        <style>html,body{margin:0;padding:0;background:#000;height:100%;overflow:hidden}
        iframe{position:absolute;top:0;left:0;width:100%;height:100%;border:0}</style></head>
        <body><iframe src="https://www.youtube.com/embed/\(videoID)?playsinline=1&autoplay=1&rel=0&modestbranding=1"
        allow="autoplay; encrypted-media; picture-in-picture; fullscreen" allowfullscreen></iframe></body></html>
        """
        view.loadHTMLString(html, baseURL: URL(string: "https://www.youtube.com"))
        return view
    }

    func updateUIView(_ view: WKWebView, context: Context) {}

    static func dismantleUIView(_ view: WKWebView, coordinator: ()) {
        view.loadHTMLString("", baseURL: nil)
    }
}

/// Страница с видео (RuTube, VK Видео) во встроенном браузере.
struct WebVideoView: UIViewRepresentable {
    let url: URL

    func makeUIView(context: Context) -> WKWebView {
        let configuration = WKWebViewConfiguration()
        configuration.allowsInlineMediaPlayback = true
        let view = WKWebView(frame: .zero, configuration: configuration)
        view.load(URLRequest(url: url))
        return view
    }

    func updateUIView(_ view: WKWebView, context: Context) {}
}
