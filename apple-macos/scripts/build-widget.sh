#!/bin/zsh
# 构建单独的小组件扩展。配对签名与嵌入由主工程处理。
set -euo pipefail
cd "$(dirname "$0")/.."

output="$PWD/.build/widget"
appex="$output/FinanceOSWidgetExtension.appex"
xcodebuild -project ../apple-xcode/FinanceOS.xcodeproj \
  -target FinanceOSWidget \
  -configuration Debug \
  -sdk macosx \
  CODE_SIGNING_ALLOWED=NO \
  CONFIGURATION_BUILD_DIR="$output/products" \
  build

/usr/bin/ditto "$output/products/FinanceOSWidget.appex" "$appex"
codesign --force --sign - "$appex"
printf '%s\n' "$appex"
