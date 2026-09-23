import SwiftUI

/// Набор стикеров для отправки в чат (пункт 39).
///
/// Стикеры — это крупные эмодзи, сгруппированные по смыслу. Они уходят обычным
/// сообщением и остаются в истории. Если позже понадобятся картинки-стикеры,
/// достаточно добавить их в `imageStickerNames` — код уже умеет рисовать
/// вложения-стикеры как крупное изображение.
enum StickerCatalog {
    struct Group: Identifiable {
        let id: String
        let title: String
        let stickers: [String]
    }

    /// Имена PNG в ассетах, если пользователь добавит свои картинки-стикеры.
    static let imageStickerNames: [String] = []

    static let groups: [Group] = [
        Group(id: "reactions", title: "Реакции", stickers: [
            "👍", "👎", "🔥", "❤️", "😂", "🤣", "😮", "😱", "🤔", "🙄", "😴", "🥱",
            "👀", "🙈", "🤯", "😎", "🤝", "🙏", "👏", "💪", "🫡", "🤌"
        ]),
        Group(id: "emotions", title: "Эмоции", stickers: [
            "😀", "😃", "😄", "😁", "😊", "🙂", "😉", "😍", "🥰", "😘", "😜", "🤪",
            "😇", "🥳", "😏", "😢", "😭", "😤", "😡", "🤬", "😰", "😳", "🤗", "🫠"
        ]),
        Group(id: "status", title: "Статус", stickers: [
            "✅", "❌", "⚠️", "💡", "📌", "🎯", "🚀", "⭐", "🏆", "🥇", "💯", "🔥",
            "⏳", "⌛", "🔄", "🛑", "❗", "❓", "📈", "📉", "🧠", "💾", "🧩", "🛠"
        ]),
        Group(id: "fun", title: "Разное", stickers: [
            "🎉", "🎊", "🍕", "☕", "🍺", "🎮", "🎧", "🎬", "📚", "✈️", "🏠", "💤",
            "🐱", "🐶", "🦊", "🐼", "🤖", "👻", "🎃", "🌈", "☀️", "🌙", "⚡", "💎"
        ])
    ]

    static var allStickers: [String] { groups.flatMap(\.stickers) }
}

/// Панель выбора стикера: категории и крупные кнопки.
struct StickerPickerView: View {
    let onSelect: (String) -> Void
    @Environment(\.dismiss) private var dismiss
    @State private var groupIndex = 0

    private let columns = [GridItem(.adaptive(minimum: 56), spacing: 10)]

    var body: some View {
        NavigationStack {
            VStack(spacing: 0) {
                Picker("Категория", selection: $groupIndex) {
                    ForEach(Array(StickerCatalog.groups.enumerated()), id: \.offset) { index, group in
                        Text(group.title).tag(index)
                    }
                }
                .pickerStyle(.segmented)
                .padding(.horizontal, 14)
                .padding(.vertical, 10)

                ScrollView {
                    LazyVGrid(columns: columns, spacing: 10) {
                        ForEach(StickerCatalog.groups[groupIndex].stickers, id: \.self) { sticker in
                            Button {
                                onSelect(sticker)
                                dismiss()
                            } label: {
                                Text(sticker)
                                    .font(.system(size: 36))
                                    .frame(width: 56, height: 56)
                                    .background(HonorTheme.surface, in: RoundedRectangle(cornerRadius: 12))
                                    .overlay(RoundedRectangle(cornerRadius: 12)
                                        .stroke(HonorTheme.divider, lineWidth: 0.6))
                            }
                            .buttonStyle(.plain)
                            .accessibilityLabel("Стикер \(sticker)")
                            .accessibilityIdentifier("sticker." + sticker)
                        }
                    }
                    .padding(.horizontal, 14)
                    .padding(.bottom, 18)
                }
            }
            .background(HonorTheme.background)
            .navigationTitle("Стикеры")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .navigationBarTrailing) {
                    Button("Готово") { dismiss() }
                        .accessibilityIdentifier("stickers.close")
                }
            }
            .accessibilityIdentifier("stickers.sheet")
        }
    }
}
