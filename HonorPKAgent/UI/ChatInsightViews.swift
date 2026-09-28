import SwiftUI

// MARK: - Данные о чате

/// Что было в чате: медиа, файлы, ссылки и шаги работы — для «Информации о чате».
enum ChatInsight {
    enum Author: String, CaseIterable, Identifiable {
        case all, user, assistant
        var id: String { rawValue }
    }

    enum MediaKind: String { case photo, video, audio, file, link }

    struct MediaItem: Identifiable {
        let id: String
        let kind: MediaKind
        let author: MessageRole
        let date: Date
        let title: String
        let subtitle: String
        let attachment: MessageAttachment?
        let url: URL?
        let messageID: UUID
    }

    struct LinkItem: Identifiable {
        var id: String { url.absoluteString }
        let url: URL
        let title: String
        let snippet: String
        let date: Date
        let origin: String
        let messageID: UUID
    }

    struct TimelineItem: Identifiable {
        let id: String
        let date: Date
        let symbol: String
        let title: String
        let detail: String
        let author: MessageRole
        let messageID: UUID
    }

    static let imagePattern = try? NSRegularExpression(pattern: "!\\[([^\\]]*)\\]\\((https?://[^\\s)]+)\\)")
    static let linkPattern = try? NSRegularExpression(pattern: "(?<!!)\\[([^\\]]+)\\]\\((https?://[^\\s)]+)\\)")

    static func matches(_ expression: NSRegularExpression?, in text: String) -> [(String, URL)] {
        guard let expression, !text.isEmpty else { return [] }
        let range = NSRange(text.startIndex..., in: text)
        return expression.matches(in: text, range: range).compactMap { match in
            guard match.numberOfRanges >= 3,
                  let titleRange = Range(match.range(at: 1), in: text),
                  let urlRange = Range(match.range(at: 2), in: text),
                  let url = URL(string: String(text[urlRange])) else { return nil }
            return (String(text[titleRange]), url)
        }
    }

    static func media(in messages: [ChatMessage]) -> [MediaItem] {
        var items: [MediaItem] = []
        for message in messages {
            for attachment in message.attachments where attachment.kind != .sticker {
                let kind: MediaKind
                switch attachment.kind {
                case .image: kind = .photo
                case .video: kind = .video
                case .audio: kind = .audio
                default: kind = .file
                }
                items.append(MediaItem(id: attachment.id.uuidString, kind: kind, author: message.role, date: message.createdAt,
                                       title: attachment.name, subtitle: attachment.summary ?? "", attachment: attachment,
                                       url: nil, messageID: message.id))
            }
            guard message.role == .assistant else { continue }
            for (title, url) in matches(imagePattern, in: message.content) {
                let isVideo = MediaLinks.isVideo(url)
                items.append(MediaItem(id: message.id.uuidString + url.absoluteString, kind: isVideo ? .video : .photo,
                                       author: .assistant, date: message.createdAt,
                                       title: title.isEmpty ? (url.host ?? "") : title, subtitle: url.host ?? "",
                                       attachment: nil, url: url, messageID: message.id))
            }
            for (title, url) in matches(linkPattern, in: message.content) where MediaLinks.isVideo(url) {
                items.append(MediaItem(id: message.id.uuidString + url.absoluteString, kind: .video, author: .assistant,
                                       date: message.createdAt, title: title, subtitle: url.host ?? "",
                                       attachment: nil, url: url, messageID: message.id))
            }
        }
        return items
    }

    static func links(in messages: [ChatMessage]) -> [LinkItem] {
        var items: [LinkItem] = []
        var seen = Set<String>()
        func add(_ item: LinkItem) {
            guard seen.insert(item.url.absoluteString).inserted else { return }
            items.append(item)
        }
        for message in messages {
            for source in message.sources {
                add(LinkItem(url: source.url, title: source.title, snippet: source.snippet, date: message.createdAt,
                             origin: "source", messageID: message.id))
            }
            for step in message.activity ?? [] {
                for site in step.sites {
                    guard let url = URL(string: site.hasPrefix("http") ? site : "https://" + site) else { continue }
                    add(LinkItem(url: url, title: site, snippet: step.title, date: step.startedAt ?? message.createdAt,
                                 origin: "visit", messageID: message.id))
                }
            }
            for (title, url) in matches(linkPattern, in: message.content) {
                add(LinkItem(url: url, title: title, snippet: "", date: message.createdAt,
                             origin: message.role == .user ? "user" : "answer", messageID: message.id))
            }
        }
        return items
    }

    static func timeline(of messages: [ChatMessage], english: Bool) -> [TimelineItem] {
        var items: [TimelineItem] = []
        for message in messages {
            let author = message.role
            let preview = String(message.content.replacingOccurrences(of: "\n", with: " ").prefix(120))
            if message.role == .user {
                let title = english ? "You wrote" : "Вы написали"
                items.append(TimelineItem(id: message.id.uuidString, date: message.createdAt,
                                          symbol: message.inputKind == .voice ? "mic.fill" : "person.fill",
                                          title: title, detail: preview, author: author, messageID: message.id))
                for attachment in message.attachments {
                    items.append(TimelineItem(id: attachment.id.uuidString, date: message.createdAt, symbol: attachmentSymbol(attachment),
                                              title: english ? "You attached" : "Вы прикрепили", detail: attachment.name,
                                              author: author, messageID: message.id))
                }
            } else if message.role == .assistant {
                for step in message.activity ?? [] {
                    let detail = ([step.detail] + step.sites.prefix(4)).filter { !$0.isEmpty }.joined(separator: " · ")
                    items.append(TimelineItem(id: step.id.uuidString, date: step.startedAt ?? message.createdAt,
                                              symbol: ActivityTimeline.symbol(step.kind), title: step.title, detail: detail,
                                              author: author, messageID: message.id))
                }
                if message.reasoningSeconds > 0 {
                    items.append(TimelineItem(id: message.id.uuidString + "-thinking", date: message.createdAt,
                                              symbol: "brain", title: english ? "Thinking" : "Размышление",
                                              detail: english ? "\(message.reasoningSeconds) s" : "\(message.reasoningSeconds) с",
                                              author: author, messageID: message.id))
                }
                if !message.content.isEmpty {
                    items.append(TimelineItem(id: message.id.uuidString, date: message.createdAt, symbol: "sparkles",
                                              title: english ? "Honer AI answered" : "Honer AI ответил", detail: preview,
                                              author: author, messageID: message.id))
                }
            }
        }
        return items
    }
}

// MARK: - Окно «Информация о чате»

struct ChatInsightSheet: View {
    @ObservedObject var store: ChatStore
    @ObservedObject var settings: AppSettings
    @Environment(\.dismiss) private var dismiss
    @State private var tab: Tab = .overview
    @State private var author: ChatInsight.Author = .all
    @State private var query = ""
    @State private var preview: MessageAttachment?
    @Environment(\.openURL) private var openURL

    enum Tab: String, CaseIterable, Identifiable {
        case overview, media, files, links, timeline
        var id: String { rawValue }
    }

    private func text(_ ru: String, _ en: String) -> String { settings.text(ru, en) }
    private var english: Bool { settings.language == .english }
    private var messages: [ChatMessage] { store.selectedConversation?.messages ?? [] }

    private func tabTitle(_ tab: Tab) -> String {
        switch tab {
        case .overview: return text("Обзор", "Overview")
        case .media: return text("Медиа", "Media")
        case .files: return text("Файлы", "Files")
        case .links: return text("Ссылки", "Links")
        case .timeline: return text("Ход", "Timeline")
        }
    }

    var body: some View {
        NavigationStack {
            VStack(spacing: 10) {
                Picker("", selection: $tab) {
                    ForEach(Tab.allCases) { item in Text(tabTitle(item)).tag(item) }
                }
                .pickerStyle(.segmented)
                .padding(.horizontal, 16)
                .accessibilityIdentifier("chat.info.tabs")
                if tab != .overview { filters }
                content
                    .animation(.easeInOut(duration: 0.2), value: tab)
            }
            .padding(.top, 8)
            .background(HonorTheme.background.ignoresSafeArea())
            .navigationTitle(text("Информация о чате", "Chat information"))
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .navigationBarTrailing) {
                    Button(text("Готово", "Done")) { dismiss() }
                        .accessibilityIdentifier("chat.info.close")
                }
            }
        }
        .sheet(item: $preview) { attachment in
            InsightAttachmentPreview(attachment: attachment, settings: settings)
        }
        .accessibilityIdentifier("chat.info.sheet")
    }

    private var filters: some View {
        VStack(spacing: 8) {
            HStack(spacing: 8) {
                Image(systemName: "magnifyingglass").foregroundStyle(HonorTheme.secondary)
                TextField(text("Поиск", "Search"), text: $query)
                    .textFieldStyle(.plain)
                    .accessibilityIdentifier("chat.info.search")
                if !query.isEmpty {
                    Button { query = "" } label: { Image(systemName: "xmark.circle.fill") }
                        .foregroundStyle(HonorTheme.secondary)
                }
            }
            .padding(.horizontal, 12)
            .frame(height: 40)
            .background(HonorTheme.surface, in: RoundedRectangle(cornerRadius: 12, style: .continuous))
            Picker("", selection: $author) {
                Text(text("Все", "All")).tag(ChatInsight.Author.all)
                Text(text("Вы", "You")).tag(ChatInsight.Author.user)
                Text("Honer AI").tag(ChatInsight.Author.assistant)
            }
            .pickerStyle(.segmented)
            .accessibilityIdentifier("chat.info.author")
        }
        .padding(.horizontal, 16)
    }

    private func passes(_ role: MessageRole, _ values: [String]) -> Bool {
        switch author {
        case .user where role != .user: return false
        case .assistant where role != .assistant: return false
        default: break
        }
        let needle = query.trimmingCharacters(in: .whitespaces).lowercased()
        guard !needle.isEmpty else { return true }
        return values.contains { $0.lowercased().contains(needle) }
    }

    @ViewBuilder
    private var content: some View {
        switch tab {
        case .overview: overview
        case .media: mediaGrid
        case .files: filesList
        case .links: linksList
        case .timeline: timelineList
        }
    }

    // MARK: Обзор

    private var overview: some View {
        let chat = store.selectedConversation
        let media = ChatInsight.media(in: messages)
        let links = ChatInsight.links(in: messages)
        return List {
            Section(text("Чат", "Chat")) {
                LabeledContent(text("Название", "Title"), value: chat?.title ?? "—")
                if let created = chat?.createdAt {
                    LabeledContent(text("Создан", "Created"), value: DateFormatter.localizedString(from: created, dateStyle: .medium, timeStyle: .short))
                }
                LabeledContent(text("Сообщений", "Messages"), value: "\(messages.count)")
                LabeledContent(text("Ваших / Honer AI", "Yours / Honer AI"),
                               value: "\(messages.filter { $0.role == .user }.count) / \(messages.filter { $0.role == .assistant }.count)")
                LabeledContent(text("Голосовых сообщений", "Voice messages"), value: "\(messages.filter { $0.inputKind == .voice }.count)")
                LabeledContent(text("Таблиц", "Tables"), value: "\(chat?.tables?.count ?? 0)")
                LabeledContent(text("Закреплённых инструкций", "Pinned instructions"), value: "\(chat?.instructions?.count ?? 0)")
            }
            Section(text("Содержимое", "Content")) {
                countRow("photo.on.rectangle", text("Фото", "Photos"), media.filter { $0.kind == .photo }.count, .media)
                countRow("play.rectangle", text("Видео", "Videos"), media.filter { $0.kind == .video }.count, .media)
                countRow("waveform", text("Голосовые и музыка", "Voice and music"), media.filter { $0.kind == .audio }.count, .files)
                countRow("doc", text("Файлы", "Files"), media.filter { $0.kind == .file }.count, .files)
                countRow("link", text("Ссылки и сайты", "Links and sites"), links.count, .links)
            }
            Section(text("Устройство", "Device")) {
                LabeledContent(text("Модель", "Model"), value: DeviceModel.name)
                LabeledContent(text("Система", "System"), value: DeviceModel.osDescription)
                LabeledContent(text("Приложение", "App"), value: "Honer AI " + DeviceModel.appVersion)
            }
        }
        .listStyle(.insetGrouped)
        .scrollContentBackground(.hidden)
        .accessibilityIdentifier("chat.info.overview")
    }

    private func countRow(_ symbol: String, _ title: String, _ count: Int, _ target: Tab) -> some View {
        Button { withAnimation { tab = target } } label: {
            HStack {
                Label(title, systemImage: symbol)
                Spacer()
                Text("\(count)").foregroundStyle(HonorTheme.secondary)
                Image(systemName: "chevron.right").font(.system(size: 12, weight: .semibold)).foregroundStyle(HonorTheme.secondary)
            }
        }
        .foregroundStyle(HonorTheme.foreground)
    }

    // MARK: Медиа

    private var visualMedia: [ChatInsight.MediaItem] {
        ChatInsight.media(in: messages).filter { ($0.kind == .photo || $0.kind == .video) && passes($0.author, [$0.title, $0.subtitle]) }
    }

    private var mediaGrid: some View {
        let items = visualMedia
        return ScrollView {
            if items.isEmpty { emptyState(text("Фото и видео пока нет", "No photos or videos yet"), "photo.on.rectangle") }
            LazyVGrid(columns: [GridItem(.adaptive(minimum: 104), spacing: 6)], spacing: 6) {
                ForEach(items) { item in
                    Button { open(item) } label: { InsightMediaTile(item: item) }
                        .buttonStyle(.plain)
                }
            }
            .padding(.horizontal, 12)
        }
        .accessibilityIdentifier("chat.info.media")
    }

    private func open(_ item: ChatInsight.MediaItem) {
        if let attachment = item.attachment { preview = attachment } else if let url = item.url { openURL(url) }
    }

    // MARK: Файлы и аудио

    private var filesList: some View {
        let items = ChatInsight.media(in: messages).filter { ($0.kind == .audio || $0.kind == .file) && passes($0.author, [$0.title, $0.subtitle]) }
        return List {
            if items.isEmpty { emptyState(text("Файлов и голосовых пока нет", "No files or voice messages yet"), "doc") }
            ForEach(items) { item in
                Button { open(item) } label: {
                    HStack(spacing: 12) {
                        if let attachment = item.attachment {
                            AttachmentFileCard(attachment: attachment, scale: 0.95)
                        }
                        authorBadge(item.author)
                    }
                }
                .buttonStyle(.plain)
                .listRowBackground(Color.clear)
            }
        }
        .listStyle(.plain)
        .accessibilityIdentifier("chat.info.files")
    }

    // MARK: Ссылки

    private var linksList: some View {
        let items = ChatInsight.links(in: messages).filter { item in
            let role: MessageRole = item.origin == "user" ? .user : .assistant
            return passes(role, [item.title, item.url.absoluteString, item.snippet])
        }
        return List {
            if items.isEmpty { emptyState(text("Ссылок пока нет", "No links yet"), "link") }
            ForEach(items) { item in
                Button { openURL(item.url) } label: { linkRow(item) }
                    .buttonStyle(.plain)
                    .contextMenu {
                        Button { UIPasteboard.general.string = item.url.absoluteString } label: {
                            Label(text("Копировать ссылку", "Copy link"), systemImage: "doc.on.doc")
                        }
                    }
            }
        }
        .listStyle(.plain)
        .accessibilityIdentifier("chat.info.links")
    }

    private func linkRow(_ item: ChatInsight.LinkItem) -> some View {
        HStack(alignment: .top, spacing: 10) {
            favicon(for: item.url)
                .frame(width: 22, height: 22)
                .clipShape(RoundedRectangle(cornerRadius: 5))
            VStack(alignment: .leading, spacing: 3) {
                Text(item.title.isEmpty ? (item.url.host ?? "") : item.title)
                    .font(.system(size: 15, weight: .semibold)).lineLimit(2)
                Text(item.url.host ?? item.url.absoluteString)
                    .font(.system(size: 12)).foregroundStyle(HonorTheme.accent).lineLimit(1)
                if !item.snippet.isEmpty {
                    Text(item.snippet).font(.system(size: 12)).foregroundStyle(HonorTheme.secondary).lineLimit(2)
                }
                Text(originTitle(item.origin) + " · " + DateFormatter.localizedString(from: item.date, dateStyle: .short, timeStyle: .short))
                    .font(.system(size: 11)).foregroundStyle(HonorTheme.secondary)
            }
        }
        .padding(.vertical, 4)
    }

    @ViewBuilder
    private func favicon(for url: URL) -> some View {
        if let icon = URL(string: "https://www.google.com/s2/favicons?sz=64&domain=\(url.host ?? "")") {
            CachedPreviewImage(url: icon)
        } else {
            Image(systemName: "globe")
        }
    }

    private func originTitle(_ origin: String) -> String {
        switch origin {
        case "source": return text("прочитано Honer AI", "read by Honer AI")
        case "visit": return text("открывал Honer AI", "visited by Honer AI")
        case "user": return text("ваша ссылка", "your link")
        default: return text("в ответе", "in the answer")
        }
    }

    // MARK: Ход работы

    private var timelineList: some View {
        let items = ChatInsight.timeline(of: messages, english: english).filter { passes($0.author, [$0.title, $0.detail]) }
        return List {
            if items.isEmpty { emptyState(text("Пока пусто", "Nothing yet"), "clock") }
            ForEach(items) { item in
                HStack(alignment: .top, spacing: 12) {
                    Image(systemName: item.symbol)
                        .font(.system(size: 13, weight: .semibold))
                        .foregroundStyle(item.author == .user ? HonorTheme.secondary : HonorTheme.accent)
                        .frame(width: 28, height: 28)
                        .background(HonorTheme.surface, in: Circle())
                    VStack(alignment: .leading, spacing: 2) {
                        HStack {
                            Text(item.title).font(.system(size: 14, weight: .semibold))
                            Spacer()
                            Text(DateFormatter.localizedString(from: item.date, dateStyle: .none, timeStyle: .short))
                                .font(.system(size: 11)).foregroundStyle(HonorTheme.secondary)
                        }
                        if !item.detail.isEmpty {
                            Text(item.detail).font(.system(size: 13)).foregroundStyle(HonorTheme.secondary).lineLimit(3)
                        }
                    }
                }
                .padding(.vertical, 2)
            }
        }
        .listStyle(.plain)
        .accessibilityIdentifier("chat.info.timeline")
    }

    // MARK: Общее

    private func authorBadge(_ role: MessageRole) -> some View {
        Text(role == .user ? text("Вы", "You") : "AI")
            .font(.system(size: 11, weight: .bold))
            .foregroundStyle(role == .user ? HonorTheme.secondary : HonorTheme.accent)
            .padding(.horizontal, 7).padding(.vertical, 3)
            .background(HonorTheme.raised, in: Capsule())
    }

    private func emptyState(_ title: String, _ symbol: String) -> some View {
        VStack(spacing: 10) {
            Image(systemName: symbol).font(.system(size: 34))
            Text(title).font(.system(size: 15))
        }
        .foregroundStyle(HonorTheme.secondary)
        .frame(maxWidth: .infinity)
        .padding(.vertical, 40)
        .listRowBackground(Color.clear)
    }
}

/// Плитка фото или видео в сетке медиа.
private struct InsightMediaTile: View {
    let item: ChatInsight.MediaItem

    var body: some View {
        ZStack(alignment: .bottomLeading) {
            thumbnail
                .frame(minWidth: 0, maxWidth: .infinity)
                .frame(height: 110)
                .clipped()
            if item.kind == .video {
                Image(systemName: "play.circle.fill")
                    .font(.system(size: 26))
                    .foregroundStyle(.white.opacity(0.92))
                    .shadow(radius: 3)
                    .frame(maxWidth: .infinity, maxHeight: .infinity)
            }
            Text(item.author == .user ? "Вы" : "AI")
                .font(.system(size: 10, weight: .bold))
                .foregroundStyle(.white)
                .padding(.horizontal, 6).padding(.vertical, 2)
                .background(.black.opacity(0.45), in: Capsule())
                .padding(5)
        }
        .frame(height: 110)
        .background(HonorTheme.surface)
        .clipShape(RoundedRectangle(cornerRadius: 10, style: .continuous))
    }

    @ViewBuilder
    private var thumbnail: some View {
        if let attachment = item.attachment {
            AttachmentThumbnail(attachment: attachment, height: 110,
                                frameOverride: attachment.kind == .video ? attachment.resolvedFrameURLs.first : nil)
        } else if let url = item.url {
            CachedPreviewImage(url: item.kind == .video ? (MediaLinks.videoThumbnail(url) ?? url) : url)
                .scaledToFill()
        }
    }
}

/// Предпросмотр вложения из «Информации о чате».
private struct InsightAttachmentPreview: View {
    let attachment: MessageAttachment
    @ObservedObject var settings: AppSettings
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        NavigationStack {
            Group {
                if let url = attachment.resolvedURL, attachment.kind == .image, let image = UIImage(contentsOfFile: url.path) {
                    Image(uiImage: image).resizable().scaledToFit().padding(12)
                } else if let url = attachment.resolvedURL {
                    OriginalFilePreview(url: url)
                } else {
                    ScrollView { Text(attachment.extractedText).textSelection(.enabled).padding(20) }
                }
            }
            .navigationTitle(attachment.name)
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .navigationBarLeading) {
                    Button(settings.text("Готово", "Done")) { dismiss() }
                }
                ToolbarItem(placement: .navigationBarTrailing) {
                    if let url = attachment.resolvedURL {
                        ShareLink(item: url) { Image(systemName: "square.and.arrow.up") }
                    }
                }
            }
        }
    }
}
