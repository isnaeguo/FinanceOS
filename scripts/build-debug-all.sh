#!/bin/zsh
# 一键生成三端 debug 构建。
# 用法：./scripts/build-debug-all.sh
# 任一端失败立即中止，并打印“哪一端、哪条命令”失败；成功后输出产物绝对路径清单。
set -euo pipefail
cd "$(dirname "$0")/.."
ROOT="$(pwd)"
export JAVA_HOME="${JAVA_HOME:-/opt/homebrew/opt/openjdk@21}"

fail() {
  echo "❌ 构建失败：$1"
  echo "   失败命令：$2"
  exit 1
}

echo "▶ [1/3] Android :app:assembleDebug"
./gradlew :app:assembleDebug \
  || fail "Android" "./gradlew :app:assembleDebug"
APK="$ROOT/app/build/outputs/apk/debug/app-debug.apk"
[ -f "$APK" ] || fail "Android 产物缺失" "检查 $APK"

echo "▶ [2/3] macOS make-app-debug"
( cd "$ROOT/apple-macos" && ./scripts/make-app-debug.sh ) \
  || fail "macOS" "cd apple-macos && ./scripts/make-app-debug.sh"
MAC_APP="$ROOT/apple-macos/dist-debug/FinanceOS.app"
[ -d "$MAC_APP" ] || fail "macOS 产物缺失" "检查 $MAC_APP"

echo "▶ [3/3] iOS 模拟器（先产出 KMP 框架）"
./gradlew :shared:linkDebugFrameworkIosSimulatorArm64 \
  || fail "iOS (KMP 框架)" "./gradlew :shared:linkDebugFrameworkIosSimulatorArm64"
IOS_DERIVED="$ROOT/ios/build"
xcodebuild -project ios/FinanceOSiOS/FinanceOSiOS.xcodeproj \
  -scheme FinanceOSiOS \
  -configuration Debug \
  -sdk iphonesimulator \
  -destination 'generic/platform=iOS Simulator' \
  ARCHS=arm64 ONLY_ACTIVE_ARCH=YES \
  CODE_SIGNING_ALLOWED=NO \
  -derivedDataPath "$IOS_DERIVED" \
  build \
  || fail "iOS" "xcodebuild -project ios/FinanceOSiOS/FinanceOSiOS.xcodeproj -scheme FinanceOSiOS -configuration Debug -sdk iphonesimulator -derivedDataPath ios/build build"
IOS_APP="$IOS_DERIVED/Build/Products/Debug-iphonesimulator/FinanceOSiOS.app"
[ -d "$IOS_APP" ] || fail "iOS 产物缺失" "检查 $IOS_APP"

echo ""
echo "✅ 三端 debug 构建完成，产物："
echo "  Android APK : $APK"
echo "  macOS App   : $MAC_APP"
echo "  iOS App     : $IOS_APP"
