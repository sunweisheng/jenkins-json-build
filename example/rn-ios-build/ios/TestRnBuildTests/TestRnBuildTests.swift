import XCTest

final class TestRnBuildTests: XCTestCase {
  func testAcceptanceFixture() {
    XCTAssertEqual([1, 2, 3].reduce(0, +), 6)
  }
}
