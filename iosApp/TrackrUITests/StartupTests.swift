import XCTest

final class StartupTests: XCTestCase {
    func testInitializationFailureCanRetryAndReopenStores() {
        let app = XCUIApplication()
        app.launchArguments = ["--offline-ui-test", "--fail-startup-once"]
        app.launch()
        XCTAssertTrue(app.staticTexts["暂时无法打开本地数据，请重试"].waitForExistence(timeout: 30))
        app.buttons["重试"].tap()
        XCTAssertTrue(app.buttons["我的"].firstMatch.waitForExistence(timeout: 30))
    }

    func testOfflineStartupAndRestartRemainUsable() {
        let app = XCUIApplication()
        app.launchArguments = ["--offline-ui-test"]
        app.launch()
        XCTAssertTrue(app.wait(for: .runningForeground, timeout: 30))
        XCTAssertTrue(app.buttons["我的"].firstMatch.waitForExistence(timeout: 30))
        app.buttons["我的"].firstMatch.tap()
        XCTAssertTrue(app.switches.containing(NSPredicate(format: "label CONTAINS %@", "长辈版")).firstMatch.waitForExistence(timeout: 10))
        app.terminate()
        app.launch()
        XCTAssertTrue(app.buttons["我的"].firstMatch.waitForExistence(timeout: 30))
    }
}
