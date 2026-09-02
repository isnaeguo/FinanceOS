// swift-tools-version: 6.2
import PackageDescription

let package = Package(
    name: "FinanceOSMac",
    platforms: [
        .macOS(.v26),
    ],
    targets: [
        .target(
            name: "FinanceOSCore",
            path: "Sources/FinanceOSCore"
        ),
        .executableTarget(
            name: "FinanceOSMac",
            dependencies: ["FinanceOSCore"],
            path: "Sources/FinanceOSMac"
        ),
        .executableTarget(
            name: "FinanceOSChecks",
            dependencies: ["FinanceOSCore"],
            path: "Tests/FinanceOSChecks"
        ),
    ]
)
