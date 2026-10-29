// swift-tools-version:5.9
// The Mimizan Lab core for iOS: `mimizan-core` behind UniFFI, as a static
// library in an xcframework plus the generated Swift. Both are produced by
// `scripts/build_ios.sh`; neither is committed.
import PackageDescription

let package = Package(
    name: "MimizanCore",
    platforms: [.iOS(.v17)],
    products: [
        .library(name: "MimizanCore", targets: ["MimizanCore"]),
    ],
    targets: [
        .binaryTarget(name: "MimizanMobileFFI", path: "MimizanMobileFFI.xcframework"),
        .target(
            name: "MimizanCore",
            dependencies: ["MimizanMobileFFI"],
            path: "Sources/MimizanCore",
            linkerSettings: [
                // What the Rust standard library and its crates pull in.
                .linkedLibrary("iconv"),
                .linkedLibrary("resolv"),
                .linkedFramework("CoreFoundation"),
                .linkedFramework("Security"),
            ]
        ),
    ]
)
