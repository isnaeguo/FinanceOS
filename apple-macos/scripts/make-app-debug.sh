#!/bin/zsh
# 生成 debug 版 FinanceOS.app（dist-debug/，含 WidgetKit 小组件扩展）。
# 主工程在 ../apple-xcode；小组件作为 app-extension 由 Xcode 嵌入 PlugIns。
set -euo pipefail
cd "$(dirname "$0")/.."

APP="dist-debug/FinanceOS.app"
PROJECT="../apple-xcode/FinanceOS.xcodeproj"

xcodebuild -project "$PROJECT" \
  -scheme FinanceOS \
  -configuration Debug \
  -destination 'platform=macOS' \
  CODE_SIGNING_ALLOWED=NO \
  -derivedDataPath .build/xcode-derived-debug \
  build >/dev/null

BIN=".build/xcode-derived-debug/Build/Products/Debug/FinanceOS.app"

rm -rf "$APP"
ditto "$BIN" "$APP"

if [ -f "$APP/Contents/Resources/AppIcon.icns" ]; then
  :
else
  [ -f Resources/AppIcon.icns ] && cp Resources/AppIcon.icns "$APP/Contents/Resources/AppIcon.icns"
fi

codesign --force --sign - "$APP"

echo "✅ $APP 已生成（debug，含小组件插件）"
