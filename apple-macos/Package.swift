// swift-tools-version: 6.2
import PackageDescription

// macOS 主应用已迁移到 apple-xcode（xcodebuild），业务内核为 KMP shared 框架。
// 本 SwiftPM 工程只保留 FinanceOSChecks：对 shared 框架做 macOS 冒烟复验。
let package = Package(
    name: "FinanceOSChecks",
    platforms: [
        .macOS(.v26),
    ],
    targets: [
        .executableTarget(
            name: "FinanceOSChecks",
            dependencies: ["FinanceOSShared"],
            path: "Tests/FinanceOSChecks",
            linkerSettings: [
                .linkedLibrary("sqlite3"),
            ]
        ),
        .binaryTarget(
            name: "FinanceOSShared",
            path: ".build/FinanceOSShared.xcframework"
        ),
    ]
)
