import SwiftUI
import UIKit

enum HonorTheme {
    static let accent = Color(red: 0.43, green: 0.60, blue: 0.98)
    static let background = adaptive(0x101010, light: 0xFFFFFF)
    static let sidebar = adaptive(0x0C0C0C, light: 0xF4F4F6)
    static let surface = adaptive(0x191919, light: 0xF7F7F8)
    static let raised = adaptive(0x292929, light: 0xECECEE)
    static let bubble = adaptive(0x313131, light: 0xEDEDEF)
    static let foreground = adaptive(0xF5F5F5, light: 0x171719)
    static let secondary = adaptive(0x919596, light: 0x747478)
    static let divider = adaptive(0x303030, light: 0xDADADD)

    private static func adaptive(_ dark: UInt32, light: UInt32) -> Color {
        Color(uiColor: UIColor { traits in
            let value = traits.userInterfaceStyle == .dark ? dark : light
            return UIColor(red: CGFloat((value >> 16) & 255) / 255,
                           green: CGFloat((value >> 8) & 255) / 255,
                           blue: CGFloat(value & 255) / 255, alpha: 1)
        })
    }
}

/// Honor's split-ribbon X remains crisp at every Dynamic Type and screen size.
struct HonorMark: View {
    var size: CGFloat = 50

    var body: some View {
        ZStack {
            HonorRibbon()
                .fill(LinearGradient(colors: [Color(red: 0.86, green: 0.94, blue: 1), Color(red: 0.48, green: 0.59, blue: 0.94)],
                                     startPoint: .top, endPoint: .bottom))
                .scaleEffect(x: -1, y: 1)
            HonorRibbon()
                .fill(LinearGradient(colors: [Color(red: 0.28, green: 0.69, blue: 1), Color(red: 0.22, green: 0.43, blue: 0.98)],
                                     startPoint: .topLeading, endPoint: .bottomTrailing))
        }
        .frame(width: size, height: size)
        .accessibilityLabel("Honer AI")
    }
}

/// Round open speech balloon with a lower-left tail, matching the reference control.
struct NewConversationSymbol: View {
    var body: some View {
        ZStack {
            NewConversationOutline().stroke(style: StrokeStyle(lineWidth: 1.65, lineCap: .round, lineJoin: .round))
            Image(systemName: "plus").font(.system(size: 12, weight: .semibold)).offset(x: 0.5, y: -1)
        }
    }
}

private struct NewConversationOutline: Shape {
    func path(in r: CGRect) -> Path {
        var p = Path()
        p.addArc(center: CGPoint(x: r.midX, y: r.height * 0.46), radius: r.width * 0.405,
                 startAngle: .degrees(139), endAngle: .degrees(112), clockwise: false)
        p.addQuadCurve(to: CGPoint(x: r.width * 0.12, y: r.height * 0.89),
                       control: CGPoint(x: r.width * 0.22, y: r.height * 0.77))
        return p
    }
}

private struct HonorRibbon: Shape {
    func path(in rect: CGRect) -> Path {
        let w = rect.width, h = rect.height
        var p = Path()
        p.move(to: CGPoint(x: w * 0.06, y: h * 0.04))
        p.addLine(to: CGPoint(x: w * 0.33, y: h * 0.04))
        p.addLine(to: CGPoint(x: w * 0.94, y: h * 0.96))
        p.addLine(to: CGPoint(x: w * 0.67, y: h * 0.96))
        p.closeSubpath()
        return p
    }
}

struct HonorCircleButton: View {
    let symbol: String
    let label: String
    var diameter: CGFloat = 42
    var action: () -> Void

    var body: some View {
        Button(action: action) {
            Image(systemName: symbol)
                .font(.system(size: 20, weight: .regular))
                .foregroundStyle(HonorTheme.foreground)
                .frame(width: diameter, height: diameter)
                .background(HonorTheme.surface.opacity(0.65), in: Circle())
                .overlay(Circle().stroke(HonorTheme.divider, lineWidth: 0.7))
                .frame(minWidth: 44, minHeight: 44)
                .contentShape(Circle())
        }
        .buttonStyle(.plain)
        .accessibilityLabel(label)
    }
}

struct HonorActionButton: View {
    let symbol: String
    let label: String
    var selected = false
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            Image(systemName: symbol)
                .font(.system(size: 17))
                .foregroundStyle(selected ? HonorTheme.accent : HonorTheme.secondary)
                .frame(width: 44, height: 44)
                .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .accessibilityLabel(label)
        .accessibilityAddTraits(selected ? .isSelected : [])
    }
}
