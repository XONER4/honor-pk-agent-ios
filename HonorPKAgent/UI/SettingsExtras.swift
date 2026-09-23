import AVKit
import SwiftUI

struct ArchivedChatsPage: View {
    @EnvironmentObject private var store: ChatStore
    @EnvironmentObject private var settings: AppSettings
    @State private var pendingDelete: UUID?

    var body: some View {
        List {
            if store.archivedConversations.isEmpty {
                VStack(spacing: 15) {
                    Image(systemName: "archivebox").font(.system(size: 38)).foregroundStyle(.secondary)
                    Text(settings.text("Архив пуст", "No archived chats")).font(.headline)
                    Text(settings.text("Архивированные чаты скрыты из боковой панели. Здесь их можно восстановить.",
                                       "Archived conversations are hidden from the sidebar. Restore them here."))
                        .foregroundStyle(.secondary).multilineTextAlignment(.center)
                }.frame(maxWidth: .infinity).padding(.vertical, 30)
                    .listRowBackground(Color.clear).accessibilityIdentifier("archive.empty")
            }
            ForEach(store.archivedConversations) { chat in
                VStack(alignment: .leading, spacing: 12) {
                    Text(chat.title).font(.headline).lineLimit(3)
                        .accessibilityIdentifier("archive.row.\(chat.id)")
                    HStack {
                        Text(chat.archivedAt ?? chat.updatedAt, style: .date).font(.caption).foregroundStyle(.secondary)
                        Spacer()
                        Button { withAnimation { store.restoreChat(id: chat.id) } } label: {
                            Label(settings.text("Восстановить", "Restore"), systemImage: "arrow.uturn.backward")
                        }.buttonStyle(.borderless).accessibilityIdentifier("archive.restore.\(chat.id)")
                        Button(role: .destructive) { pendingDelete = chat.id } label: {
                            Image(systemName: "trash").frame(width: 38, height: 36)
                        }.buttonStyle(.borderless).accessibilityLabel(settings.text("Удалить", "Delete"))
                            .accessibilityIdentifier("archive.delete.\(chat.id)")
                    }
                }.padding(.vertical, 6)
            }
        }
        .navigationTitle(settings.text("Архив чатов", "Archived chats"))
        .navigationBarTitleDisplayMode(.inline)
        .accessibilityIdentifier("settings.page.archive")
        .alert(settings.text("Удалить чат?", "Delete chat?"), isPresented: Binding(get: { pendingDelete != nil }, set: { if !$0 { pendingDelete = nil } })) {
            Button(settings.text("Отмена", "Cancel"), role: .cancel) { pendingDelete = nil }
            Button(settings.text("Удалить", "Delete"), role: .destructive) {
                if let pendingDelete { store.deleteChats(ids: [pendingDelete]) }
                pendingDelete = nil
            }.accessibilityIdentifier("archive.delete.confirm")
        }
    }
}

struct PersonalizationVideoPage: View {
    @EnvironmentObject private var settings: AppSettings
    @Environment(\.dismiss) private var dismiss
    @State private var player: AVPlayer?
    @State private var loadFailed = false

    var body: some View {
        NavigationStack {
            Group {
                if let player {
                    VideoPlayer(player: player).background(.black)
                        .accessibilityIdentifier("personalization.video.player")
                } else if loadFailed {
                    Label(settings.text("Не удалось открыть видео в этой сборке.", "The video could not be opened in this build."), systemImage: "exclamationmark.circle")
                        .foregroundStyle(.secondary).padding()
                } else {
                    ProgressView().accessibilityIdentifier("personalization.video.loading")
                }
            }
            .navigationTitle(settings.text("Персонализация в деле", "Personalization in action"))
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .confirmationAction) {
                    Button(settings.text("Готово", "Done")) { dismiss() }
                        .accessibilityIdentifier("personalization.video.close")
                }
            }
        }
        .onAppear {
            if let url = Bundle.main.url(forResource: "PersonalizationDemo", withExtension: "mp4") {
                player = AVPlayer(url: url)
                player?.play()
            } else { loadFailed = true }
        }
        .onDisappear { player?.pause(); player = nil }
    }
}
