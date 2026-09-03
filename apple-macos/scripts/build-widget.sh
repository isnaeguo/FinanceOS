#!/bin/zsh
# 编译 FinanceOS 小组件扩展（FinanceOSWidgetExtension.appex）。
# 不使用 Xcode：把 WidgetKit 源码与 Sources/FinanceOSCore/Domain 下源码一起用 swiftc 编译，
# 保证与 App 内 FinanceOSCore 的计算逻辑完全一致；再手工组装 .appex 并 ad-hoc 签名。
# 产物：.build/widget/FinanceOSWidgetExtension.appex（供 make-app-debug.sh 嵌入 App）。
set -euo pipefail
cd "$(dirname "$0")/.."

WIDGET_NAME="FinanceOSWidgetExtension"
WIDGET_SOURCES="Sources/FinanceOSWidgetWidgets"
DOMAIN_SOURCES="Sources/FinanceOSCore/Domain"
BUILD_DIR=".build/widget"
APPEX="$BUILD_DIR/$WIDGET_NAME.appex"
VERSION="1.0.0"

# 1) 编译为可执行文件（-parse-as-library：让 @main WidgetBundle 生效）
rm -rf "$BUILD_DIR"
mkdir -p "$APPEX/Contents/MacOS" "$APPEX/Contents/Resources"
xcrun swiftc -parse-as-library -O \
  -module-name "$WIDGET_NAME" \
  "$WIDGET_SOURCES"/*.swift \
  "$DOMAIN_SOURCES"/*.swift \
  -o "$BUILD_DIR/$WIDGET_NAME" \
  -framework SwiftUI \
  -framework WidgetKit -framework AppKit

cp "$BUILD_DIR/$WIDGET_NAME" "$APPEX/Contents/MacOS/$WIDGET_NAME"

# 2) 图标（复用 App 资源）与最小 Info.plist
cp Resources/AppIcon.icns "$APPEX/Contents/Resources/AppIcon.icns"

cat > "$APPEX/Contents/Info.plist" <<PLIST
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0">
<dict>
	<key>CFBundleDevelopmentRegion</key>
	<string>zh_CN</string>
	<key>CFBundleExecutable</key>
	<string>$WIDGET_NAME</string>
	<key>CFBundleIdentifier</key>
	<string>com.financeos.mac.widget</string>
	<key>CFBundleInfoDictionaryVersion</key>
	<string>6.0</string>
	<key>CFBundleName</key>
	<string>FinanceOSWidget</string>
	<key>CFBundleDisplayName</key>
	<string>FinanceOS 本月概览</string>
	<key>CFBundlePackageType</key>
	<string>XPC!</string>
	<key>CFBundleShortVersionString</key>
	<string>$VERSION</string>
	<key>CFBundleSupportedPlatforms</key>
	<array>
		<string>macOS</string>
	</array>
	<key>CFBundleVersion</key>
	<string>1</string>
	<key>LSMinimumSystemVersion</key>
	<string>26.0</string>
	<key>NSExtension</key>
	<dict>
		<key>NSExtensionAttributes</key>
		<dict>
			<key>NSExtensionPointVersion</key>
			<string>3.0</string>
		</dict>
		<key>NSExtensionPointIdentifier</key>
		<string>com.apple.widgetkit-extension</string>
	</dict>
</dict>
</plist>
PLIST

# 3) 先签 appex（插件必须在被 App 嵌入前签名）
# 小组件在扩展进程内运行，需沙盒 + 数据文件读取例外 + 网络客户端。
ENTITLEMENTS=".build/widget/widget.entitlements"
mkdir -p "$(dirname "$ENTITLEMENTS")"
cat > "$ENTITLEMENTS" <<EOF
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0">
<dict>
    <key>com.apple.security.app-sandbox</key>
    <true/>
    <key>com.apple.security.network.client</key>
    <true/>
    <key>com.apple.security.temporary-exception.files.absolute-path.read-only</key>
    <array>
        <string>$HOME/Library/Application Support/FinanceOS/</string>
    </array>
</dict>
</plist>
EOF
codesign --force --sign - --entitlements "$ENTITLEMENTS" "$APPEX"

echo "✅ $APPEX 已生成（WidgetKit 小组件：小/中/大）"
