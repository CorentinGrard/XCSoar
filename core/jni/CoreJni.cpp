// SPDX-License-Identifier: GPL-2.0-or-later
// Copyright The XCSoar Project

/*
 * JNI binding of the C API (core/api/xcsoar_core.h) for the Android app
 * (mobile/app, class org.xcsoar.mobile.NativeCore).
 *
 * Also does, for the non-UI part of XCSoar, what src/Android/Main.cpp
 * does for the old NativeView UI: cache the Java classes (JNI_OnLoad)
 * and create the Android globals (nativeInit).  Only classes whose Java
 * code the app ships are initialised; see mobile/docs/DECISIONS.md D13.
 */

#include "xcsoar_core.h"
#include "Android/Main.hpp"
#include "Android/Context.hpp"
#include "Android/Environment.hpp"
#include "Android/InternalSensors.hpp"
#include "Android/NativeSensorListener.hpp"
#include "java/Global.hxx"
#include "java/Object.hxx"
#include "java/File.hxx"
#include "java/InputStream.hxx"
#include "java/Closeable.hxx"
#include "java/String.hxx"
#include "Android/Bitmap.hpp"
#include "Android/TextUtil.hpp"
#include "AndroidGraphics.hpp"
#include "LogFile.hpp"

#include <android/native_window_jni.h>

#include <jni.h>

#include <cassert>
#include <string>

namespace {

/** org.xcsoar.mobile.NativeCore and its static callbacks */
struct CoreClass {
  jclass cls = nullptr;
  jmethodID on_snapshot = nullptr, on_event = nullptr;

  bool Initialise(JNIEnv *env) noexcept {
    jclass local = env->FindClass("org/xcsoar/mobile/NativeCore");
    if (local == nullptr)
      return false;

    cls = (jclass)env->NewGlobalRef(local);
    env->DeleteLocalRef(local);

    on_snapshot = env->GetStaticMethodID(cls, "onSnapshot",
                                         "(Ljava/nio/ByteBuffer;)V");
    on_event = env->GetStaticMethodID(cls, "onEvent",
                                      "(IILjava/lang/String;Ljava/lang/String;)V");
    return on_snapshot != nullptr && on_event != nullptr;
  }
} core_class;

/* callbacks run on the core main thread; Java::GetEnv() attaches it */

void
OnSnapshot([[maybe_unused]] void *ctx, const xcs_flight_snapshot *s) noexcept
{
  JNIEnv *env = Java::GetEnv();

  /* no copy: Kotlin decodes the buffer before the callback returns */
  jobject buffer = env->NewDirectByteBuffer(const_cast<xcs_flight_snapshot *>(s),
                                            s->struct_size);
  env->CallStaticVoidMethod(core_class.cls, core_class.on_snapshot, buffer);
  env->DeleteLocalRef(buffer);

  if (env->ExceptionCheck()) {
    env->ExceptionDescribe();
    env->ExceptionClear();
  }
}

jstring
NewStringOrNull(JNIEnv *env, const char *s) noexcept
{
  return s != nullptr ? env->NewStringUTF(s) : nullptr;
}

void
OnEvent([[maybe_unused]] void *ctx, const xcs_event *e) noexcept
{
  JNIEnv *env = Java::GetEnv();

  jstring text = NewStringOrNull(env, e->text);
  jstring detail = NewStringOrNull(env, e->detail);
  env->CallStaticVoidMethod(core_class.cls, core_class.on_event,
                            (jint)e->type, (jint)e->code, text, detail);
  if (text != nullptr)
    env->DeleteLocalRef(text);
  if (detail != nullptr)
    env->DeleteLocalRef(detail);

  if (env->ExceptionCheck()) {
    env->ExceptionDescribe();
    env->ExceptionClear();
  }
}

xcs_core *
ToCore(jlong handle) noexcept
{
  return reinterpret_cast<xcs_core *>(handle);
}

} // namespace

/**
 * Call an xcs_* function that writes JSON into a buffer, with a larger
 * buffer if needed.
 *
 * @return the JSON, or null on error
 */
template<typename F>
static jstring
GetJson(JNIEnv *env, F &&get) noexcept
{
  std::string buffer(8192, '\0');
  size_t length;
  xcs_status status = get(buffer.data(), buffer.size(), &length);
  if (status == XCS_ERROR_INVALID_ARGUMENT && length >= buffer.size()) {
    buffer.resize(length + 1);
    status = get(buffer.data(), buffer.size(), &length);
  }

  return status == XCS_OK ? env->NewStringUTF(buffer.c_str()) : nullptr;
}

extern "C" {

JNIEXPORT jint JNICALL
JNI_OnLoad(JavaVM *vm, [[maybe_unused]] void *reserved)
{
  JNIEnv *env;
  if (vm->GetEnv((void **)&env, JNI_VERSION_1_6) != JNI_OK)
    return JNI_ERR;

  Java::Init(env);
  Java::Object::Initialise(env);
  Java::File::Initialise(env);
  Java::InputStream::Initialise(env);
  Java::InitialiseCloseable(env);

  Context::Initialise(env);
  Environment::Initialise(env);
  NativeSensorListener::Initialise(env);
  InternalSensors::Initialise(env);

  /* text and images of the map */
  AndroidBitmap::Initialise(env);
  TextUtil::Initialise(env);
  CoreGraphics::Initialise(env);

  if (!core_class.Initialise(env))
    return JNI_ERR;

  return JNI_VERSION_1_6;
}

/** Create the Android globals the core needs; call once, before create. */
JNIEXPORT void JNICALL
Java_org_xcsoar_mobile_NativeCore_nativeInit(JNIEnv *env, jclass,
                                             jobject _context,
                                             jobject _permission_manager)
{
  assert(context == nullptr);

  context = new Context(env, _context);
  permission_manager = env->NewGlobalRef(_permission_manager);
}

JNIEXPORT jlong JNICALL
Java_org_xcsoar_mobile_NativeCore_nativeCreate(JNIEnv *env, jclass,
                                               jstring data_path)
{
  const auto path = Java::String::GetUTFChars(env, data_path);

  xcs_config config{};
  config.struct_size = sizeof(config);
  config.api_version = XCS_API_VERSION;
  config.data_path = path.c_str();
  config.on_snapshot = OnSnapshot;
  config.on_event = OnEvent;

  xcs_core *core = nullptr;
  if (xcs_create(&config, &core) != XCS_OK)
    return 0;

  return reinterpret_cast<jlong>(core);
}

JNIEXPORT jint JNICALL
Java_org_xcsoar_mobile_NativeCore_nativeStart(JNIEnv *, jclass, jlong core)
{
  return xcs_start(ToCore(core));
}

JNIEXPORT jint JNICALL
Java_org_xcsoar_mobile_NativeCore_nativeStop(JNIEnv *, jclass, jlong core)
{
  return xcs_stop(ToCore(core));
}

JNIEXPORT void JNICALL
Java_org_xcsoar_mobile_NativeCore_nativeDestroy(JNIEnv *, jclass, jlong core)
{
  xcs_destroy(ToCore(core));
}

JNIEXPORT jint JNICALL
Java_org_xcsoar_mobile_NativeCore_nativeSetMacCready(JNIEnv *, jclass,
                                                     jlong core, jdouble mc)
{
  return xcs_set_mac_cready(ToCore(core), mc);
}

JNIEXPORT jint JNICALL
Java_org_xcsoar_mobile_NativeCore_nativeSetBallast(JNIEnv *, jclass,
                                                   jlong core, jdouble litres)
{
  return xcs_set_ballast(ToCore(core), litres);
}

JNIEXPORT jint JNICALL
Java_org_xcsoar_mobile_NativeCore_nativeSetBugs(JNIEnv *, jclass,
                                                jlong core, jdouble bugs)
{
  return xcs_set_bugs(ToCore(core), bugs);
}

JNIEXPORT jint JNICALL
Java_org_xcsoar_mobile_NativeCore_nativeSetQnh(JNIEnv *, jclass,
                                               jlong core, jdouble hpa)
{
  return xcs_set_qnh(ToCore(core), hpa);
}

JNIEXPORT jint JNICALL
Java_org_xcsoar_mobile_NativeCore_nativeReplayStart(JNIEnv *env, jclass,
                                                    jlong core, jstring path,
                                                    jdouble time_scale)
{
  const auto p = Java::String::GetUTFChars(env, path);
  return xcs_replay_start(ToCore(core), p.c_str(), time_scale);
}

JNIEXPORT jint JNICALL
Java_org_xcsoar_mobile_NativeCore_nativeReplayStop(JNIEnv *, jclass,
                                                   jlong core)
{
  return xcs_replay_stop(ToCore(core));
}

JNIEXPORT jint JNICALL
Java_org_xcsoar_mobile_NativeCore_nativeSetDataFile(JNIEnv *env, jclass,
                                                    jlong core, jint kind,
                                                    jstring path)
{
  if (path == nullptr)
    return xcs_set_data_file(ToCore(core), kind, nullptr);

  const auto p = Java::String::GetUTFChars(env, path);
  return xcs_set_data_file(ToCore(core), kind, p.c_str());
}

JNIEXPORT jstring JNICALL
Java_org_xcsoar_mobile_NativeCore_nativeGetDataStatus(JNIEnv *env, jclass,
                                                      jlong core)
{
  std::string buffer(4096, '\0');
  size_t length;
  xcs_status status = xcs_get_data_status(ToCore(core), buffer.data(),
                                          buffer.size(), &length);
  if (status == XCS_ERROR_INVALID_ARGUMENT && length >= buffer.size()) {
    buffer.resize(length + 1);
    status = xcs_get_data_status(ToCore(core), buffer.data(),
                                 buffer.size(), &length);
  }

  if (status != XCS_OK)
    return nullptr;

  return env->NewStringUTF(buffer.c_str());
}

JNIEXPORT jint JNICALL
Java_org_xcsoar_mobile_NativeCore_nativeMapAttach(JNIEnv *env, jclass,
                                                  jlong core, jobject surface,
                                                  jint width, jint height,
                                                  jint dpi)
{
  ANativeWindow *window = ANativeWindow_fromSurface(env, surface);
  if (window == nullptr)
    return XCS_ERROR_INVALID_ARGUMENT;

  /* xcs_map_attach() takes its own reference */
  const auto status = xcs_map_attach(ToCore(core), window, width, height, dpi);
  ANativeWindow_release(window);
  return status;
}

JNIEXPORT jint JNICALL
Java_org_xcsoar_mobile_NativeCore_nativeMapDetach(JNIEnv *, jclass, jlong core)
{
  return xcs_map_detach(ToCore(core));
}

JNIEXPORT jint JNICALL
Java_org_xcsoar_mobile_NativeCore_nativeMapSetAircraftPosition(JNIEnv *, jclass,
                                                               jlong core,
                                                               jint x, jint y)
{
  return xcs_map_set_aircraft_position(ToCore(core), x, y);
}

JNIEXPORT jint JNICALL
Java_org_xcsoar_mobile_NativeCore_nativeMapZoom(JNIEnv *, jclass, jlong core,
                                                jint steps)
{
  return xcs_map_zoom(ToCore(core), steps);
}

JNIEXPORT jint JNICALL
Java_org_xcsoar_mobile_NativeCore_nativeMapPan(JNIEnv *, jclass, jlong core,
                                               jfloat dx, jfloat dy)
{
  return xcs_map_pan(ToCore(core), dx, dy);
}

JNIEXPORT jint JNICALL
Java_org_xcsoar_mobile_NativeCore_nativeMapScale(JNIEnv *, jclass, jlong core,
                                                 jfloat factor)
{
  return xcs_map_scale(ToCore(core), factor);
}

JNIEXPORT jint JNICALL
Java_org_xcsoar_mobile_NativeCore_nativeMapFollow(JNIEnv *, jclass, jlong core)
{
  return xcs_map_follow(ToCore(core));
}

JNIEXPORT jint JNICALL
Java_org_xcsoar_mobile_NativeCore_nativeMapSetOrientation(JNIEnv *, jclass,
                                                          jlong core,
                                                          jint orientation)
{
  return xcs_map_set_orientation(ToCore(core), orientation);
}

/** @return the orientation, or -1 on error */
JNIEXPORT jint JNICALL
Java_org_xcsoar_mobile_NativeCore_nativeMapGetOrientation(JNIEnv *, jclass,
                                                          jlong core)
{
  uint32_t orientation;
  return xcs_map_get_orientation(ToCore(core), &orientation) == XCS_OK
    ? jint(orientation)
    : -1;
}

JNIEXPORT jstring JNICALL
Java_org_xcsoar_mobile_NativeCore_nativeMapItemsAt(JNIEnv *env, jclass,
                                                   jlong core, jint x, jint y)
{
  std::string buffer(8192, '\0');
  size_t length;
  xcs_status status = xcs_map_items_at(ToCore(core), x, y, buffer.data(),
                                       buffer.size(), &length);
  if (status == XCS_ERROR_INVALID_ARGUMENT && length >= buffer.size()) {
    buffer.resize(length + 1);
    status = xcs_map_items_at(ToCore(core), x, y, buffer.data(),
                              buffer.size(), &length);
  }

  return status == XCS_OK ? env->NewStringUTF(buffer.c_str()) : nullptr;
}

JNIEXPORT jint JNICALL
Java_org_xcsoar_mobile_NativeCore_nativeMapSetOption(JNIEnv *, jclass,
                                                     jlong core, jint option,
                                                     jint value)
{
  return xcs_map_set_option(ToCore(core), option, value);
}

/** @return the value, or Integer.MIN_VALUE on error */
JNIEXPORT jint JNICALL
Java_org_xcsoar_mobile_NativeCore_nativeMapGetOption(JNIEnv *, jclass,
                                                     jlong core, jint option)
{
  int32_t value;
  return xcs_map_get_option(ToCore(core), option, &value) == XCS_OK
    ? value
    : INT32_MIN;
}

JNIEXPORT jint JNICALL
Java_org_xcsoar_mobile_NativeCore_nativeSoundSetOption(JNIEnv *, jclass,
                                                       jlong core, jint option,
                                                       jint value)
{
  return xcs_sound_set_option(ToCore(core), option, value);
}

/** @return the value, or Integer.MIN_VALUE on error */
JNIEXPORT jint JNICALL
Java_org_xcsoar_mobile_NativeCore_nativeSoundGetOption(JNIEnv *, jclass,
                                                       jlong core, jint option)
{
  int32_t value;
  return xcs_sound_get_option(ToCore(core), option, &value) == XCS_OK
    ? value
    : INT32_MIN;
}

JNIEXPORT jint JNICALL
Java_org_xcsoar_mobile_NativeCore_nativeGotoWaypoint(JNIEnv *, jclass,
                                                     jlong core, jint id)
{
  return xcs_goto_waypoint(ToCore(core), id);
}

JNIEXPORT jstring JNICALL
Java_org_xcsoar_mobile_NativeCore_nativeRepositoryList(JNIEnv *env, jclass,
                                                       jstring path)
{
  const auto p = Java::String::GetUTFChars(env, path);
  std::string buffer(1 << 20, '\0');
  size_t length;
  xcs_status status = xcs_repository_list(p.c_str(), buffer.data(),
                                          buffer.size(), &length);
  if (status == XCS_ERROR_INVALID_ARGUMENT && length >= buffer.size()) {
    buffer.resize(length + 1);
    status = xcs_repository_list(p.c_str(), buffer.data(), buffer.size(),
                                 &length);
  }

  return status == XCS_OK ? env->NewStringUTF(buffer.c_str()) : nullptr;
}

JNIEXPORT jstring JNICALL
Java_org_xcsoar_mobile_NativeCore_nativeGetAirspaceWarnings(JNIEnv *env, jclass,
                                                            jlong core)
{
  std::string buffer(8192, '\0');
  size_t length;
  xcs_status status = xcs_get_airspace_warnings(ToCore(core), buffer.data(),
                                                buffer.size(), &length);
  if (status == XCS_ERROR_INVALID_ARGUMENT && length >= buffer.size()) {
    buffer.resize(length + 1);
    status = xcs_get_airspace_warnings(ToCore(core), buffer.data(),
                                       buffer.size(), &length);
  }

  return status == XCS_OK ? env->NewStringUTF(buffer.c_str()) : nullptr;
}

JNIEXPORT jint JNICALL
Java_org_xcsoar_mobile_NativeCore_nativeAirspaceAcknowledge(JNIEnv *env, jclass,
                                                            jlong core,
                                                            jstring id,
                                                            jint mode)
{
  const auto i = Java::String::GetUTFChars(env, id);
  return xcs_airspace_acknowledge(ToCore(core), i.c_str(), mode);
}

JNIEXPORT jstring JNICALL
Java_org_xcsoar_mobile_NativeCore_nativeWaypointsSearch(JNIEnv *env, jclass,
                                                        jlong core,
                                                        jstring name,
                                                        jint filter, jint max)
{
  const auto n = Java::String::GetUTFChars(env, name);
  std::string buffer(32768, '\0');
  size_t length;
  xcs_status status = xcs_waypoints_search(ToCore(core), n.c_str(), filter,
                                           max, buffer.data(), buffer.size(),
                                           &length);
  if (status == XCS_ERROR_INVALID_ARGUMENT && length >= buffer.size()) {
    buffer.resize(length + 1);
    status = xcs_waypoints_search(ToCore(core), n.c_str(), filter, max,
                                  buffer.data(), buffer.size(), &length);
  }

  return status == XCS_OK ? env->NewStringUTF(buffer.c_str()) : nullptr;
}

JNIEXPORT jstring JNICALL
Java_org_xcsoar_mobile_NativeCore_nativeTaskGet(JNIEnv *env, jclass,
                                                jlong core, jint which)
{
  return GetJson(env, [core, which](char *buffer, size_t size,
                                    size_t *length){
    return xcs_task_get(ToCore(core), which, buffer, size, length);
  });
}

JNIEXPORT jstring JNICALL
Java_org_xcsoar_mobile_NativeCore_nativeGetAnalysis(JNIEnv *env, jclass,
                                                    jlong core)
{
  return GetJson(env, [core](char *buffer, size_t size, size_t *length){
    return xcs_get_analysis(ToCore(core), buffer, size, length);
  });
}

JNIEXPORT jint JNICALL
Java_org_xcsoar_mobile_NativeCore_nativeTaskEdit(JNIEnv *, jclass,
                                                 jlong core, jint op,
                                                 jint index, jdouble value)
{
  return xcs_task_edit(ToCore(core), op, index, value);
}

JNIEXPORT jstring JNICALL
Java_org_xcsoar_mobile_NativeCore_nativeTaskListFiles(JNIEnv *env, jclass,
                                                      jlong core)
{
  return GetJson(env, [core](char *buffer, size_t size, size_t *length){
    return xcs_task_list_files(ToCore(core), buffer, size, length);
  });
}

JNIEXPORT jint JNICALL
Java_org_xcsoar_mobile_NativeCore_nativeTaskLoad(JNIEnv *env, jclass,
                                                 jlong core, jstring path,
                                                 jint index)
{
  const auto p = Java::String::GetUTFChars(env, path);
  return xcs_task_load(ToCore(core), p.c_str(), index);
}

JNIEXPORT jint JNICALL
Java_org_xcsoar_mobile_NativeCore_nativeTaskSave(JNIEnv *env, jclass,
                                                 jlong core, jstring name)
{
  const auto n = Java::String::GetUTFChars(env, name);
  return xcs_task_save(ToCore(core), n.c_str());
}

JNIEXPORT jstring JNICALL
Java_org_xcsoar_mobile_NativeCore_nativeUnitsGet(JNIEnv *env, jclass,
                                                 jlong core)
{
  return GetJson(env, [core](char *buffer, size_t size, size_t *length){
    return xcs_units_get(ToCore(core), buffer, size, length);
  });
}

JNIEXPORT jint JNICALL
Java_org_xcsoar_mobile_NativeCore_nativeUnitsSet(JNIEnv *, jclass,
                                                 jlong core, jint group,
                                                 jint unit)
{
  return xcs_units_set(ToCore(core), group, unit);
}

JNIEXPORT jint JNICALL
Java_org_xcsoar_mobile_NativeCore_nativeUnitsPreset(JNIEnv *, jclass,
                                                    jlong core, jint index)
{
  return xcs_units_preset(ToCore(core), index);
}

} // extern "C"
