#!/bin/zsh
# 产出 SwiftPM 冒烟工程使用的 FinanceOSShared.xcframework（macOS slice）。
set -euo pipefail
cd "$(dirname "$0")/../.."

GRADLE="./gradlew"
FRAMEWORK="shared/build/bin/macosArm64/debugFramework/FinanceOSShared.framework"
OUT="apple-macos/.build/FinanceOSShared.xcframework"

"$GRADLE" :shared:linkDebugFrameworkMacosArm64 -q

rm -rf "$OUT"
mkdir -p "$(dirname "$OUT")"
xcodebuild -create-xcframework \
  -framework "$FRAMEWORK" \
  -output "$OUT"

echo "✅ $OUT"
