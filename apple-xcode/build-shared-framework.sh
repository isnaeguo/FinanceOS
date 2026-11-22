#!/bin/sh
# Xcode App 和 Widget 都链接此静态框架，Widget 在 App 之前构建。
set -eu

if [ -z "${JAVA_HOME:-}" ]; then
    JAVA_HOME=$(/usr/libexec/java_home -v 21)
    export JAVA_HOME
fi

cd "$SRCROOT/.."
configuration_lower=$(printf '%s' "$CONFIGURATION" | tr '[:upper:]' '[:lower:]')
./gradlew ":shared:link${CONFIGURATION}FrameworkMacosArm64" -q
framework="shared/build/bin/macosArm64/${configuration_lower}Framework/FinanceOSShared.framework"
destination="$SRCROOT/build/shared-framework"
mkdir -p "$destination"
/usr/bin/ditto "$framework" "$destination/FinanceOSShared.framework"
