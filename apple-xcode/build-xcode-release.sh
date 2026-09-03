#!/bin/zsh
# FinanceOS macOS Release 打包：Archive -> 导出 .app -> 生成 DMG
# 前置：Xcode 里三个 target 已选好 Team；在终端执行：
#   cd /Users/insaneguo/FinanceOS/apple-xcode && ./build-xcode-release.sh
set -euo pipefail
cd "$(dirname "$0")"

OUT=build
ARCHIVE="$OUT/FinanceOS.xcarchive"
APP_OUT="$OUT/FinanceOS.app"
DMG="$OUT/FinanceOS-1.0.0.dmg"

echo "▶ Archive …"
xcodebuild -project FinanceOS.xcodeproj \
  -scheme FinanceOS \
  -configuration Release \
  -destination 'generic/platform=macOS' \
  -archivePath "$ARCHIVE" \
  archive

echo "▶ 导出 .app …"
rm -rf "$APP_OUT"
ditto "$ARCHIVE/Products/Applications/FinanceOS.app" "$APP_OUT"

echo "▶ 生成 DMG（拖拽安装）…"
STAGE="$OUT/dmg-staging"
rm -rf "$STAGE"; mkdir -p "$STAGE"
cp -R "$APP_OUT" "$STAGE/FinanceOS.app"
ln -s /Applications "$STAGE/Applications"
rm -f "$DMG"
hdiutil create -volname FinanceOS -srcfolder "$STAGE" -ov -format UDZO "$DMG"
rm -rf "$STAGE"

echo "✅ 完成："
echo "   App: $APP_OUT"
echo "   DMG: $DMG"
