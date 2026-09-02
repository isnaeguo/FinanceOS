import AppKit
import Foundation

// 绘制 FinanceOS 应用图标（液态渐变圆角方块 + ¥ 符号）并打包 .icns。
// 以脚本方式运行：swift scripts/build-icon.swift

enum IconError: Error { case bitmap, context, png }

func run(_ path: String, _ arguments: String...) throws {
    let process = Process()
    process.executableURL = URL(fileURLWithPath: path)
    process.arguments = arguments
    try process.run()
    process.waitUntilExit()
}

func drawIcon(canvas: NSRect) {
    let rect = canvas.insetBy(dx: canvas.width * 0.08, dy: canvas.height * 0.08)
    let corner = rect.width * 0.225
    let path = NSBezierPath(roundedRect: rect, xRadius: corner, yRadius: corner)

    // 液态渐变背景（左上青 → 右下紫）
    let gradient = NSGradient(colors: [
        NSColor(calibratedRed: 0.30, green: 0.85, blue: 0.85, alpha: 1),
        NSColor(calibratedRed: 0.35, green: 0.62, blue: 0.95, alpha: 1),
        NSColor(calibratedRed: 0.55, green: 0.42, blue: 0.92, alpha: 1),
    ])!
    gradient.draw(in: path, angle: -60)

    // 上部高光
    path.addClip()
    let highlight = NSGradient(colors: [
        NSColor.white.withAlphaComponent(0.35),
        NSColor.white.withAlphaComponent(0.0),
    ])!
    highlight.draw(
        in: NSRect(x: rect.minX, y: rect.midY, width: rect.width, height: rect.height / 2),
        angle: -90
    )

    // 中央 ¥ 符号
    let symbol = "¥" as NSString
    let fontSize = rect.width * 0.52
    let attributes: [NSAttributedString.Key: Any] = [
        .font: NSFont.systemFont(ofSize: fontSize, weight: .bold),
        .foregroundColor: NSColor.white,
    ]
    let symbolSize = symbol.size(withAttributes: attributes)
    symbol.draw(
        at: NSPoint(x: rect.midX - symbolSize.width / 2, y: rect.midY - symbolSize.height / 2),
        withAttributes: attributes
    )
}

do {
    let size = 1024
    guard let rep = NSBitmapImageRep(
        bitmapDataPlanes: nil,
        pixelsWide: size,
        pixelsHigh: size,
        bitsPerSample: 8,
        samplesPerPixel: 4,
        hasAlpha: true,
        isPlanar: false,
        colorSpaceName: .deviceRGB,
        bytesPerRow: 0,
        bitsPerPixel: 0
    ) else { throw IconError.bitmap }

    rep.size = NSSize(width: size, height: size)
    guard let context = NSGraphicsContext(bitmapImageRep: rep) else { throw IconError.context }
    NSGraphicsContext.saveGraphicsState()
    NSGraphicsContext.current = context
    drawIcon(canvas: NSRect(x: 0, y: 0, width: size, height: size))
    context.flushGraphics()
    NSGraphicsContext.restoreGraphicsState()

    guard let png = rep.representation(using: .png, properties: [:]) else { throw IconError.png }
    let outputDir = URL(fileURLWithPath: FileManager.default.currentDirectoryPath)
        .appendingPathComponent("Resources", isDirectory: true)
    try FileManager.default.createDirectory(at: outputDir, withIntermediateDirectories: true)
    let master = outputDir.appendingPathComponent("icon-master.png")
    try png.write(to: master)

    let iconset = outputDir.appendingPathComponent("AppIcon.iconset", isDirectory: true)
    try? FileManager.default.removeItem(at: iconset)
    try FileManager.default.createDirectory(at: iconset, withIntermediateDirectories: true)
    let spec: [(name: String, size: Int)] = [
        ("icon_16x16", 16), ("icon_16x16@2x", 32),
        ("icon_32x32", 32), ("icon_32x32@2x", 64),
        ("icon_128x128", 128), ("icon_128x128@2x", 256),
        ("icon_256x256", 256), ("icon_256x256@2x", 512),
        ("icon_512x512", 512), ("icon_512x512@2x", 1024),
    ]
    for item in spec {
        let destination = iconset.appendingPathComponent("\(item.name).png")
        try run("/usr/bin/sips", "-z", "\(item.size)", "\(item.size)", master.path, "--out", destination.path)
    }
    let icns = outputDir.appendingPathComponent("AppIcon.icns")
    try? FileManager.default.removeItem(at: icns)
    try run("/usr/bin/iconutil", "-c", "icns", iconset.path, "-o", icns.path)
    print("✅ 图标已生成：\(icns.path)")
} catch {
    fputs("图标生成失败：\(error)\n", stderr)
    exit(1)
}
