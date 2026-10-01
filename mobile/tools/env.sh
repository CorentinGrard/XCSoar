# SPDX-License-Identifier: GPL-2.0-or-later
# Build environment for XCSoar Mobile (macOS and Linux).
#
# Usage:  source mobile/tools/env.sh
#         xmake check
#         xmake TARGET=ANDROIDAARCH64 ./output/ANDROID/arm64-v8a/dbg/bin/libxcsoar.so
#
# Why each variable exists: see mobile/docs/PLAN.md (M0).

if [ "$(uname -s)" = Darwin ]; then
  # Command Line Tools may ship a newer macOS SDK than Xcode's linker
  # understands; always build host tools against Xcode's own SDK.
  export SDKROOT="$(xcode-select -p)/Platforms/MacOSX.platform/Developer/SDKs/MacOSX.sdk"

  # Android tools need a HotSpot JDK (OpenJ9 crashes in d8).  Prefer the
  # one bundled with Android Studio.
  _as_jbr="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
  if [ -d "$_as_jbr" ]; then
    export JAVA_HOME="$_as_jbr"
    export PATH="$JAVA_HOME/bin:$PATH"
  fi
  unset _as_jbr

  export ANDROID_SDK="${ANDROID_SDK:-$HOME/Library/Android/sdk}"
  XCS_MAKE=gmake
  XCS_JOBS="$(sysctl -n hw.ncpu)"
else
  # where ide/provisioning/install-android-tools.sh installs it
  export ANDROID_SDK="${ANDROID_SDK:-$HOME/opt/android-sdk-linux}"
  XCS_MAKE=make
  XCS_JOBS="$(getconf _NPROCESSORS_ONLN)"
fi

# Upstream pins build-tools 36.0.0; use it if installed, else the newest 36.x.
# build/android.mk assigns this with "=", so it must be passed on the make
# command line (see xmake below), not just exported.
XCS_BUILD_TOOLS_DIR=""
if [ ! -d "$ANDROID_SDK/build-tools/36.0.0" ]; then
  XCS_BUILD_TOOLS_DIR="$(ls -d "$ANDROID_SDK"/build-tools/36.* 2>/dev/null \
    | sort -V | tail -n 1)"
fi

# make with the flags every build here needs.
xmake() {
  "$XCS_MAKE" -j"$XCS_JOBS" USE_CCACHE=y \
    ${XCS_BUILD_TOOLS_DIR:+ANDROID_BUILD_TOOLS_DIR="$XCS_BUILD_TOOLS_DIR"} \
    "$@"
}

echo "XCSoar Mobile env: ${SDKROOT:+SDKROOT=$SDKROOT}"
echo "  JAVA_HOME=${JAVA_HOME:-<unset>}"
echo "  ANDROID_SDK=$ANDROID_SDK"
echo "  build-tools=${XCS_BUILD_TOOLS_DIR:-<default 36.0.0>}"
