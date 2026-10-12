// swift-tools-version: 6.0
import PackageDescription

let package = Package(
    name: "LibrePodsMyMac",
    platforms: [.macOS(.v14)],
    products: [
        .executable(name: "LibrePodsMyMac", targets: ["LibrePodsMyMac"])
    ],
    targets: [
        .executableTarget(
            name: "LibrePodsMyMac",
            swiftSettings: [.swiftLanguageMode(.v5)],
            linkerSettings: [
                .linkedFramework("CoreAudio"),
                .linkedFramework("CoreBluetooth"),
                .linkedFramework("CryptoKit"),
                .linkedFramework("IOBluetooth"),
                .linkedFramework("Security")
            ]
        )
    ]
)
