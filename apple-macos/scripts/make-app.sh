#!/bin/zsh
# 生成 FinanceOS.app（可重复执行：release 构建 + 打包 + 图标 + 签名）
set -euo pipefail
cd "$(dirname "$0")/.."

APP_NAME="FinanceOS"
BUILD_DIR=".build/arm64-apple-macosx/release"
BIN="$BUILD_DIR/FinanceOSMac"
APP="dist/$APP_NAME.app"

# 1) Release 构建
swift build -c release

# 2) 组装 .app 结构
rm -rf "$APP"
mkdir -p "$APP/Contents/MacOS" "$APP/Contents/Resources"

cp "$BIN" "$APP/Contents/MacOS/FinanceOSMac"

# 3) Info.plist
cat > "$APP/Contents/Info.plist" <<'PLIST'
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0">
<dict>
	<key>CFBundleDevelopmentRegion</key>
	<string>zh_CN</string>
	<key>CFBundleExecutable</key>
	<string>FinanceOSMac</string>
	<key>CFBundleIdentifier</key>
	<string>com.financeos.mac</string>
	<key>CFBundleInfoDictionaryVersion</key>
	<string>6.0</string>
	<key>CFBundleName</key>
	<string>FinanceOS</string>
	<key>CFBundleDisplayName</key>
	<string>FinanceOS</string>
	<key>CFBundlePackageType</key>
	<string>APPL</string>
	<key>CFBundleShortVersionString</key>
	<string>0.4.0</string>
	<key>CFBundleVersion</key>
	<string>1</string>
	<key>CFBundleIconFile</key>
	<string>AppIcon</string>
	<key>LSMinimumSystemVersion</key>
	<string>26.0</string>
	<key>NSHighResolutionCapable</key>
	<true/>
	<key>NSPrincipalClass</key>
	<string>NSApplication</string>
	<key>LSApplicationCategoryType</key>
	<string>public.app-category.finance</string>
	<key>NSSupportsAutomaticGraphicsSwitching</key>
	<true/>
</dict>
</plist>
PLIST

# 4) 图标（若 scripts/build-icon.swift 可生成）
if [ ! -f "$APP/Contents/Resources/AppIcon.icns" ]; then
  swift scripts/build-icon.swift
fi
cp Resources/AppIcon.icns "$APP/Contents/Resources/AppIcon.icns"

# 5) Ad-hoc 签名（Apple Silicon 上 arm64 可执行文件需要签名）
codesign --force --sign - "$APP"

echo "✅ $APP 已生成"
