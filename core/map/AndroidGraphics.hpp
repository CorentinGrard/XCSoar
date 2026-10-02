// SPDX-License-Identifier: GPL-2.0-or-later
// Copyright The XCSoar Project

#pragma once

#include <jni.h>

/** The app's org.xcsoar.CoreGraphics: image decoding for the map. */
namespace CoreGraphics {

/** From JNI_OnLoad. */
void
Initialise(JNIEnv *env) noexcept;

/** Tell the Java side what OpenGL::SetupContext() detected. */
void
SetTextureNonPowerOfTwo(bool value) noexcept;

} // namespace CoreGraphics
