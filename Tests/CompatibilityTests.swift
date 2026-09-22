import XCTest
@testable import HonorPKAgent

final class CompatibilityTests: XCTestCase {
    func testSupportedIPhoneFamilies() {
        for identifier in ["iPhone14,2", "iPhone14,3", "iPhone14,4", "iPhone14,5", "iPhone14,7", "iPhone15,2", "iPhone16,1", "iPhone17,1"] {
            XCTAssertTrue(DeviceCompatibility.supports(identifier: identifier), identifier)
        }
    }

    func testUnsupportedDevicesAreExcluded() {
        for identifier in ["iPhone13,4", "iPhone12,1", "iPhone14,6", "iPad14,1", "Mac14,1", "unknown"] {
            XCTAssertFalse(DeviceCompatibility.supports(identifier: identifier), identifier)
        }
    }
}
