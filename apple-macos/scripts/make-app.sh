#!/bin/zsh
# 生成 FinanceOS.app（Release 构建：xcodebuild + 打包 + 图标 + 签名）
# 说明：macOS 主工程在 ../apple-xcode（XcodeGen 生成），业务内核为 shared 静态框架；
# SwiftPM 工程仅保留 FinanceOSChecks 冒烟校验。
set -euo pipefail
cd "$(dirname "$0")/.."

APP_NAME="FinanceOS"
APP="dist/$APP_NAME.app"
PROJECT="../apple-xcode/FinanceOS.xcodeproj"

# 1) Release 构建（preBuild 脚本会自动产出 KMP shared 框架）
xcodebuild -project "$PROJECT" \
  -scheme FinanceOS \
  -configuration Release \
  -destination 'platform=macOS' \
  CODE_SIGNING_ALLOWED=NO \
  -derivedDataPath .build/xcode-derived \
  build >/dev/null

BIN=".build/xcode-derived/Build/Products/Release/FinanceOS.app"

# 2) 组装 .app
rm -rf "$APP"
ditto "$BIN" "$APP"

# 3) 图标（若未生成）
if [ ! -f "$APP/Contents/Resources/AppIcon.icns" ] && [ -f Resources/AppIcon.icns ]; then
  cp Resources/AppIcon.icns "$APP/Contents/Resources/AppIcon.icns"
fi

# 4) Ad-hoc 签名（Apple Silicon 上 arm64 可执行文件需要签名）
codesign --force --sign - "$APP"

echo "✅ $APP 已生成"
