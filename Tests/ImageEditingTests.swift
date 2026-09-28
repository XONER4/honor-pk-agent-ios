import UIKit
import XCTest
@testable import HonorPKAgent

/// Движок фоторедактора: фильтры, геометрия, надписи и разбор операций для ИИ.
final class ImageEditingTests: XCTestCase {
    private func makeImage(width: CGFloat = 200, height: CGFloat = 100) -> UIImage {
        let format = UIGraphicsImageRendererFormat.preferred()
        format.scale = 1
        format.opaque = true
        let renderer = UIGraphicsImageRenderer(size: CGSize(width: width, height: height), format: format)
        return renderer.image { context in
            UIColor.white.setFill()
            context.fill(CGRect(x: 0, y: 0, width: width, height: height))
            UIColor.systemBlue.setFill()
            context.fill(CGRect(x: 20, y: 20, width: 60, height: 60))
            UIColor.systemOrange.setFill()
            context.fill(CGRect(x: 120, y: 10, width: 50, height: 80))
        }
    }

    func testEveryFilterKeepsSize() {
        let image = makeImage()
        for filter in EditFilter.allCases {
            let result = ImageEditing.applyFilter(image, filter)
            XCTAssertEqual(result.size, image.size, "Фильтр \(filter.rawValue) изменил размер")
            XCTAssertFalse(filter.title.isEmpty)
            XCTAssertFalse(filter.englishTitle.isEmpty)
        }
    }

    func testAdjustKeepsSize() {
        let image = makeImage()
        let result = ImageEditing.adjust(image, brightness: 0.3, contrast: 0.2, saturation: -0.5, warmth: 0.4)
        XCTAssertEqual(result.size, image.size)
    }

    func testRotateSwapsDimensions() {
        let image = makeImage()
        let clockwise = ImageEditing.rotate(image, clockwise: true)
        XCTAssertEqual(clockwise.size.width, 100, accuracy: 0.5)
        XCTAssertEqual(clockwise.size.height, 200, accuracy: 0.5)
        let back = ImageEditing.rotate(clockwise, clockwise: false)
        XCTAssertEqual(back.size.width, 200, accuracy: 0.5)
        XCTAssertEqual(back.size.height, 100, accuracy: 0.5)
    }

    func testFlipKeepsSize() {
        let image = makeImage()
        XCTAssertEqual(ImageEditing.flipHorizontally(image).size, image.size)
        XCTAssertEqual(ImageEditing.flipVertically(image).size, image.size)
    }

    func testCropAspects() {
        let image = makeImage()
        let square = ImageEditing.crop(image, aspect: .square)
        XCTAssertEqual(square.size.width, square.size.height, accuracy: 1)
        XCTAssertEqual(square.size.height, 100, accuracy: 1)
        let story = ImageEditing.crop(image, aspect: .story9x16)
        XCTAssertEqual(story.size.width / story.size.height, 9.0 / 16.0, accuracy: 0.03)
        XCTAssertEqual(ImageEditing.crop(image, aspect: .original).size, image.size)
        let half = ImageEditing.crop(image, to: CGRect(x: 0, y: 0, width: 0.5, height: 1))
        XCTAssertEqual(half.size.width, 100, accuracy: 1)
        XCTAssertEqual(half.size.height, 100, accuracy: 1)
    }

    func testResizeLimitsLongSide() {
        let image = makeImage()
        let small = ImageEditing.resize(image, maxSide: 50)
        XCTAssertEqual(small.size.width * small.scale, 50, accuracy: 1)
        XCTAssertEqual(small.size.height * small.scale, 25, accuracy: 1)
    }

    func testAddTextKeepsSizeAndChangesPixels() throws {
        let image = makeImage()
        for style in TextStyle.allCases {
            let result = ImageEditing.addText(image, text: "Привет", position: CGPoint(x: 0.5, y: 0.5),
                                              color: .red, fontSize: 0.15, style: style)
            XCTAssertEqual(result.size, image.size)
            let before = try XCTUnwrap(image.pngData())
            let after = try XCTUnwrap(result.pngData())
            XCTAssertNotEqual(before, after, "Надпись в стиле \(style.rawValue) не изменила пиксели")
        }
    }

    func testAddStickerKeepsSize() {
        let image = makeImage()
        let result = ImageEditing.addSticker(image, emoji: "🔥", position: CGPoint(x: 0.3, y: 0.3), scale: 1)
        XCTAssertEqual(result.size, image.size)
    }

    func testParseColors() {
        XCTAssertNotNil(ImageEditing.parseColor("красный"))
        XCTAssertNotNil(ImageEditing.parseColor("Синий"))
        XCTAssertNotNil(ImageEditing.parseColor("чёрный"))
        XCTAssertNotNil(ImageEditing.parseColor("зелёный"))
        XCTAssertNotNil(ImageEditing.parseColor("white"))
        XCTAssertNotNil(ImageEditing.parseColor("#FF0000"))
        XCTAssertNotNil(ImageEditing.parseColor("#0f0"))
        XCTAssertEqual(ImageEditing.parseColor("прозрачный")?.cgColor.alpha ?? 1, 0, accuracy: 0.01)
        XCTAssertNil(ImageEditing.parseColor("непонятный"))

        var red: CGFloat = 0, green: CGFloat = 0, blue: CGFloat = 0, alpha: CGFloat = 0
        XCTAssertTrue(ImageEditing.parseColor("#FF0000")?.getRed(&red, green: &green, blue: &blue, alpha: &alpha) ?? false)
        XCTAssertEqual(red, 1, accuracy: 0.01)
        XCTAssertEqual(green, 0, accuracy: 0.01)
    }

    func testParseOperationsFromArraySingleDictAndStrings() {
        let array: [[String: Any]] = [
            ["type": "rotate"],
            ["type": "filter", "value": "mono"],
            ["type": "background_color", "value": "красный"],
            ["type": "text", "text": "Привет", "x": 0.5, "y": 0.9, "color": "белый", "size": 0.1]
        ]
        let parsed = ImageEditing.parseOperations(array)
        XCTAssertEqual(parsed.count, 4)
        XCTAssertEqual(parsed[0].normalizedType, "rotate")
        XCTAssertEqual(parsed[1].value, "mono")
        XCTAssertEqual(parsed[2].value, "красный")
        XCTAssertNotNil(ImageEditing.parseColor(parsed[2].value))
        XCTAssertEqual(parsed[3].text, "Привет")
        XCTAssertEqual(parsed[3].y ?? 0, 0.9, accuracy: 0.0001)
        XCTAssertEqual(parsed[3].color, "белый")

        let single = ImageEditing.parseOperations(["type": "crop", "value": "square"] as [String: Any])
        XCTAssertEqual(single, [ImageEditOperation(type: "crop", value: "square")])

        let wrapped = ImageEditing.parseOperations(["operations": [["op": "flip"], ["action": "resize", "size": 512]]] as [String: Any])
        XCTAssertEqual(wrapped.count, 2)
        XCTAssertEqual(wrapped[1].size ?? 0, 512, accuracy: 0.0001)

        let json = ImageEditing.parseOperations("[{\"type\":\"filter\",\"value\":\"sepia\"}]")
        XCTAssertEqual(json, [ImageEditOperation(type: "filter", value: "sepia")])

        let shorthand = ImageEditing.parseOperations(["rotate", "filter mono"])
        XCTAssertEqual(shorthand.count, 2)
        XCTAssertEqual(shorthand[1].normalizedType, "filter")
        XCTAssertEqual(shorthand[1].value, "mono")

        XCTAssertTrue(ImageEditing.parseOperations(nil).isEmpty)
        XCTAssertEqual(ImageEditOperation(type: "убрать фон").normalizedType, "remove_background")
        XCTAssertEqual(EditFilter.named("чёрно-белый"), .mono)
        XCTAssertEqual(EditFilter.named("Тёплый"), .warm)
    }

    func testApplyRotateFilterAndText() async throws {
        let image = makeImage()
        let operations = [
            ImageEditOperation(type: "rotate"),
            ImageEditOperation(type: "filter", value: "mono"),
            ImageEditOperation(type: "text", text: "Honer", color: "жёлтый", x: 0.5, y: 0.5, size: 0.12)
        ]
        let result = try await ImageEditing.apply(operations, to: image)
        XCTAssertEqual(result.size.width, 100, accuracy: 0.5)
        XCTAssertEqual(result.size.height, 200, accuracy: 0.5)
    }

    func testApplyCropAndAdjust() async throws {
        let image = makeImage()
        let operations = ImageEditing.parseOperations([
            ["type": "crop", "value": "1:1"],
            ["type": "brightness", "amount": 20],
            ["type": "resize", "size": 50]
        ] as [[String: Any]])
        let result = try await ImageEditing.apply(operations, to: image)
        XCTAssertEqual(result.size.width * result.scale, 50, accuracy: 1)
        XCTAssertEqual(result.size.height * result.scale, 50, accuracy: 1)
    }

    func testUnknownOperationThrows() async {
        let image = makeImage()
        do {
            _ = try await ImageEditing.apply([ImageEditOperation(type: "teleport")], to: image)
            XCTFail("Неизвестная операция должна вызывать ошибку")
        } catch {
            let message = error.localizedDescription
            XCTAssertTrue(message.contains("teleport"))
            XCTAssertTrue(message.contains("remove_background"))
        }
    }

    func testRemoveBackgroundOnBlankImageDoesNotCrash() async {
        let format = UIGraphicsImageRendererFormat.preferred()
        format.scale = 1
        let blank = UIGraphicsImageRenderer(size: CGSize(width: 64, height: 64), format: format).image { context in
            UIColor.white.setFill()
            context.fill(CGRect(x: 0, y: 0, width: 64, height: 64))
        }
        do {
            let result = try await ImageEditing.removeBackground(blank)
            XCTAssertGreaterThan(result.size.width, 0)
        } catch {
            XCTAssertFalse(error.localizedDescription.isEmpty)
        }
    }

    func testSaveWritesPNGAndJPEG() throws {
        let image = makeImage()
        let jpeg = try ImageEditing.save(image, preferPNG: false)
        let png = try ImageEditing.save(image, preferPNG: true)
        defer {
            try? FileManager.default.removeItem(at: jpeg)
            try? FileManager.default.removeItem(at: png)
        }
        XCTAssertEqual(jpeg.pathExtension, "jpg")
        XCTAssertEqual(png.pathExtension, "png")
        XCTAssertNotNil(UIImage(contentsOfFile: jpeg.path))
        XCTAssertNotNil(UIImage(contentsOfFile: png.path))
    }
}
