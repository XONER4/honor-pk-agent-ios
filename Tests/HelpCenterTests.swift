import UIKit
import XCTest
@testable import HonorPKAgent

/// Проверки встроенного руководства: полнота текстов на двух языках, связи между статьями,
/// наличие скриншотов в бандле и работа поиска.
final class HelpCenterTests: XCTestCase {
    private func isBlank(_ text: String) -> Bool {
        text.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
    }

    func testEveryArticleHasRussianAndEnglishText() {
        let articles = HelpLibrary.allArticles
        XCTAssertGreaterThanOrEqual(articles.count, 30)
        for article in articles {
            XCTAssertFalse(isBlank(article.titleRU), "titleRU: \(article.id)")
            XCTAssertFalse(isBlank(article.titleEN), "titleEN: \(article.id)")
            XCTAssertFalse(isBlank(article.summaryRU), "summaryRU: \(article.id)")
            XCTAssertFalse(isBlank(article.summaryEN), "summaryEN: \(article.id)")
            XCTAssertFalse(article.bodyRU.isEmpty, "bodyRU: \(article.id)")
            XCTAssertFalse(article.bodyEN.isEmpty, "bodyEN: \(article.id)")
            XCTAssertFalse(article.bodyRU.contains(where: isBlank), "blank RU paragraph: \(article.id)")
            XCTAssertFalse(article.bodyEN.contains(where: isBlank), "blank EN paragraph: \(article.id)")
            XCTAssertEqual(article.bodyRU.count, article.bodyEN.count, "paragraph count: \(article.id)")
            XCTAssertEqual(article.stepsRU.count, article.stepsEN.count, "steps count: \(article.id)")
            XCTAssertEqual(article.tipsRU.count, article.tipsEN.count, "tips count: \(article.id)")
            XCTAssertFalse(isBlank(article.symbol), "symbol: \(article.id)")
        }
    }

    func testSectionsAreCompleteAndUnique() {
        let sections = HelpLibrary.sections
        XCTAssertGreaterThanOrEqual(sections.count, 14)
        let sectionIDs = sections.map { $0.id }
        XCTAssertEqual(Set(sectionIDs).count, sectionIDs.count, "duplicate section id")
        for section in sections {
            XCTAssertFalse(isBlank(section.titleRU), section.id)
            XCTAssertFalse(isBlank(section.titleEN), section.id)
            XCTAssertFalse(section.articles.isEmpty, section.id)
        }
    }

    func testArticleIDsAreUnique() {
        let ids = HelpLibrary.allArticles.map { $0.id }
        XCTAssertEqual(Set(ids).count, ids.count, "duplicate article id")
        let hackIDs = HelpLibrary.lifehacks.map { $0.id }
        XCTAssertEqual(Set(hackIDs).count, hackIDs.count, "duplicate lifehack id")
        let faqIDs = HelpLibrary.faq.map { $0.id }
        XCTAssertEqual(Set(faqIDs).count, faqIDs.count, "duplicate FAQ question")
    }

    func testRelatedArticlesExist() {
        let ids = Set(HelpLibrary.allArticles.map { $0.id })
        for article in HelpLibrary.allArticles {
            for related in article.related {
                XCTAssertTrue(ids.contains(related), "\(article.id) → missing related \(related)")
                XCTAssertNotEqual(related, article.id, "\(article.id) links to itself")
            }
            XCTAssertNotNil(HelpLibrary.article(id: article.id))
            let section = HelpLibrary.section(containing: article.id)
            XCTAssertNotNil(section, "no section for \(article.id)")
        }
    }

    @MainActor
    func testEveryScreenshotExistsInAppBundle() {
        let appBundle = Bundle(for: ChatStore.self)
        var names: Set<String> = []
        for article in HelpLibrary.allArticles {
            if let name = article.screenshot { names.insert(name) }
        }
        for item in HelpLibrary.faq {
            if let name = item.screenshot { names.insert(name) }
        }
        XCTAssertFalse(names.isEmpty)
        for name in names {
            let url = HelpImageStore.url(for: name, in: appBundle) ?? HelpImageStore.url(for: name, in: Bundle.main)
            XCTAssertNotNil(url, "screenshot missing from bundle: \(name)")
            if let url {
                XCTAssertNotNil(UIImage(contentsOfFile: url.path), "screenshot is not a readable image: \(name)")
            }
        }
    }

    func testFAQHasEnoughQuestionsInBothLanguages() {
        XCTAssertGreaterThanOrEqual(HelpLibrary.faq.count, 25)
        for item in HelpLibrary.faq {
            XCTAssertFalse(isBlank(item.questionRU))
            XCTAssertFalse(isBlank(item.questionEN), item.questionRU)
            XCTAssertFalse(isBlank(item.answerRU), item.questionRU)
            XCTAssertFalse(isBlank(item.answerEN), item.questionRU)
        }
        let indices = HelpLibrary.faqEntries(matching: "").map { $0.index }
        XCTAssertEqual(indices, Array(0..<HelpLibrary.faq.count))
    }

    func testLifehacksSection() {
        XCTAssertGreaterThanOrEqual(HelpLibrary.lifehacks.count, 12)
        for hack in HelpLibrary.lifehacks {
            XCTAssertFalse(isBlank(hack.titleRU), hack.id)
            XCTAssertFalse(isBlank(hack.titleEN), hack.id)
            XCTAssertFalse(isBlank(hack.textRU), hack.id)
            XCTAssertFalse(isBlank(hack.textEN), hack.id)
        }
    }

    func testSearchFindsTables() {
        let results = HelpLibrary.search("таблиц")
        XCTAssertFalse(results.isEmpty)
        XCTAssertTrue(results.contains { $0.id == "tables" })
        XCTAssertTrue(HelpLibrary.search("ТАБЛИЦ").contains { $0.id == "tables" }, "search must ignore case")
        XCTAssertTrue(HelpLibrary.search("tables").contains { $0.id == "tables" })
    }

    func testSearchFindsParentalControls() {
        let english = HelpLibrary.search("parental")
        XCTAssertTrue(english.contains { $0.id == "parental-setup" })
        let russian = HelpLibrary.search("родител")
        XCTAssertTrue(russian.contains { $0.id == "parental-setup" })
        XCTAssertTrue(russian.contains { $0.id == "parental-filters" })
    }

    func testSearchEdgeCases() {
        XCTAssertTrue(HelpLibrary.search("").isEmpty)
        XCTAssertTrue(HelpLibrary.search("   ").isEmpty)
        XCTAssertTrue(HelpLibrary.search("zzqqxxнесуществующееслово").isEmpty)
        // «ё» и «е» считаются одной буквой.
        XCTAssertFalse(HelpLibrary.search("еще").isEmpty)
        // Несколько слов — должны совпасть все.
        XCTAssertTrue(HelpLibrary.search("удалить фон").contains { $0.id == "photo-editor" })
        XCTAssertFalse(HelpLibrary.searchFAQ("архив").isEmpty)
        XCTAssertFalse(HelpLibrary.searchLifehacks("YouTube").isEmpty)
    }

    func testDemosAreUsedAndHaveSaneTiming() {
        let usedInArticles = Set(HelpLibrary.allArticles.compactMap { $0.demo })
        for demo in HelpDemo.allCases {
            XCTAssertTrue(usedInArticles.contains(demo), "demo not used in any article: \(demo.rawValue)")
            XCTAssertGreaterThanOrEqual(demo.duration, 6, demo.rawValue)
            XCTAssertLessThanOrEqual(demo.duration, 13, demo.rawValue)
            XCTAssertGreaterThanOrEqual(demo.staticTime, 0, demo.rawValue)
            XCTAssertLessThan(demo.staticTime, demo.duration, demo.rawValue)
            XCTAssertFalse(isBlank(demo.title(false)), demo.rawValue)
            XCTAssertFalse(isBlank(demo.title(true)), demo.rawValue)
        }
    }

    func testCategoryChipsCoverAllSections() {
        let chipIDs = Set(HelpLibrary.chips.map { $0.id })
        XCTAssertTrue(chipIDs.contains(HelpLibrary.allCategory))
        XCTAssertTrue(chipIDs.contains(HelpLibrary.faqCategory))
        XCTAssertTrue(chipIDs.contains(HelpLibrary.lifehacksCategory))
        for section in HelpLibrary.sections {
            XCTAssertTrue(chipIDs.contains(section.id), section.id)
        }
    }
}
