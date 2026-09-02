#!/bin/zsh
# 生成 debug 版 FinanceOS.app（dist-debug/）并嵌入 WidgetKit 小组件扩展。
# 顺序：debug 构建 → 编译小组件 appex → 组装 .app → 先签 appex 再签 App。
set -euo pipefail
cd "$(dirname "$0")/.."

APP_NAME="FinanceOS"
CONFIG="debug"
BUILD_DIR=".build/arm64-apple-macosx/$CONFIG"
BIN="$BUILD_DIR/FinanceOSMac"
APP="dist-debug/$APP_NAME.app"
PLUGINS="$APP/Contents/PlugIns"
WIDGET_NAME="FinanceOSWidgetExtension"

# 1) Debug 构建
swift build -c $CONFIG

# 2) 编译小组件扩展
bash scripts/build-widget.sh
APPEX=".build/widget/$WIDGET_NAME.appex"

# 3) 组装 .app 结构
rm -rf "$APP"
mkdir -p "$APP/Contents/MacOS" "$APP/Contents/Resources" "$PLUGINS"

cp "$BIN" "$APP/Contents/MacOS/FinanceOSMac"
cp -R "$APPEX" "$PLUGINS/$WIDGET_NAME.appex"

# 4) Info.plist（与 make-app.sh 一致；小组件不需要额外的宿主键）
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
	<string>0.4.2</string>
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

# 5) 图标
cp Resources/AppIcon.icns "$APP/Contents/Resources/AppIcon.icns"

# 6) Ad-hoc 签名：先插件（带沙盒 entitlements，同 build-widget.sh）后 App（无沙盒）
ENTITLEMENTS=".build/widget/widget.entitlements"
if [ ! -f "$ENTITLEMENTS" ]; then bash scripts/build-widget.sh; fi
codesign --force --sign - --entitlements "$ENTITLEMENTS" "$PLUGINS/$WIDGET_NAME.appex"
codesign --force --sign - "$APP"

echo "✅ $APP 已生成（debug，含小组件插件）"
