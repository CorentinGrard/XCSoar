// SPDX-License-Identifier: GPL-2.0-or-later
// Copyright The XCSoar Project

/*
 * Bitmap loading for the core's map: replaces
 * src/ui/canvas/android/Bitmap.cpp, which asks the old UI's NativeView
 * (not in the core) to decode images.  Here the app's
 * org.xcsoar.CoreGraphics does it (mobile/docs/DECISIONS.md D5, D13).
 */

#include "AndroidGraphics.hpp"
#include "ui/canvas/Bitmap.hpp"
#include "ui/canvas/opengl/Texture.hpp"
#include "Android/Bitmap.hpp"
#include "java/Class.hxx"
#include "java/Global.hxx"
#include "java/Object.hxx"
#include "java/String.hxx"
#include "system/Path.hpp"
#include "ResourceId.hpp"

#include <android/bitmap.h>

#include <cassert>
#include <cstdint>
#include <cstring>

namespace {

Java::TrivialClass graphics_class;
jmethodID load_resource_method, load_file_method, to_texture_method;

Java::LocalObject
CallLoader(jmethodID method, const char *name) noexcept
{
  JNIEnv *env = Java::GetEnv();
  const Java::String j_name{env, name};
  return {env, env->CallStaticObjectMethod(graphics_class, method,
                                           j_name.Get())};
}

/** Does the bitmap have non-grey pixels?  (Same as upstream.) */
bool
ScanBitmapForColors(JNIEnv *env, jobject bmp) noexcept
{
  AndroidBitmapInfo info;
  if (AndroidBitmap_getInfo(env, bmp, &info) != ANDROID_BITMAP_RESULT_SUCCESS ||
      info.format != ANDROID_BITMAP_FORMAT_RGBA_8888)
    return false;

  void *pixels;
  if (AndroidBitmap_lockPixels(env, bmp, &pixels) != ANDROID_BITMAP_RESULT_SUCCESS)
    return false;

  bool found = false;
  const auto *data = static_cast<const uint8_t *>(pixels);
  for (unsigned y = 0; y < info.height && !found; y++) {
    const auto *row = data + y * info.stride;
    for (unsigned x = 0; x < info.width && !found; x++) {
      const auto *p = row + x * 4;
      found = p[0] != p[1] || p[0] != p[2];
    }
  }

  AndroidBitmap_unlockPixels(env, bmp);
  return found;
}

} // namespace

void
CoreGraphics::Initialise(JNIEnv *env) noexcept
{
  graphics_class.Find(env, "org/xcsoar/CoreGraphics");
  load_resource_method =
    env->GetStaticMethodID(graphics_class, "loadResourceBitmap",
                           "(Ljava/lang/String;)Landroid/graphics/Bitmap;");
  load_file_method =
    env->GetStaticMethodID(graphics_class, "loadFileBitmap",
                           "(Ljava/lang/String;)Landroid/graphics/Bitmap;");
  to_texture_method =
    env->GetStaticMethodID(graphics_class, "bitmapToTexture",
                           "(Landroid/graphics/Bitmap;Z[I)Z");
}

void
CoreGraphics::SetTextureNonPowerOfTwo(bool value) noexcept
{
  JNIEnv *env = Java::GetEnv();
  const jmethodID method =
    env->GetStaticMethodID(graphics_class, "setTextureNonPowerOfTwo", "(Z)V");
  env->CallStaticVoidMethod(graphics_class, method, value);
}

Bitmap::Bitmap(ResourceId id)
{
  Load(id);
}

bool
Bitmap::Set(JNIEnv *env, jobject bmp, Type type, bool flipped) noexcept
{
  assert(bmp != nullptr);

  size.width = AndroidBitmap::GetWidth(env, bmp);
  size.height = AndroidBitmap::GetHeight(env, bmp);
  has_colors = ScanBitmapForColors(env, bmp);

  if (!MakeTexture(bmp, type, flipped)) {
    Reset();
    return false;
  }

  return true;
}

bool
Bitmap::MakeTexture(jobject bmp, Type type, bool flipped) noexcept
{
  assert(bmp != nullptr);

  JNIEnv *env = Java::GetEnv();
  jintArray result = env->NewIntArray(5);
  const bool ok = env->CallStaticBooleanMethod(graphics_class,
                                               to_texture_method, bmp,
                                               type == Type::MONO, result);
  jint r[5];
  env->GetIntArrayRegion(result, 0, 5, r);
  env->DeleteLocalRef(result);
  if (!ok)
    return false;

  texture = new GLTexture(r[0], PixelSize(r[1], r[2]),
                          PixelSize(r[3], r[4]), flipped);
  return true;
}

bool
Bitmap::Load(ResourceId id, Type type)
{
  assert(id.IsDefined());

  Reset();

  auto bmp = CallLoader(load_resource_method, static_cast<const char *>(id));
  if (bmp == nullptr)
    return false;

  return Set(bmp.GetEnv(), bmp, type);
}

bool
Bitmap::LoadFile(Path path)
{
  assert(path != nullptr && !path.empty());

  Reset();

  /* TIFF overlays (GeoTIFF weather maps) are not supported yet */
  auto bmp = CallLoader(load_file_method, path.c_str());
  if (bmp == nullptr)
    return false;

  return Set(bmp.GetEnv(), bmp, Type::STANDARD);
}
