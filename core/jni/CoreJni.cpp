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
#include "Android/CertificateUtil.hpp"
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
#include <limits>
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

/**
 * A blocking network call's JSON: the answer, or {"error": ...} when
 * it failed; never called twice (an upload would happen again).
 */
template<typename F>
static jstring
GetNetworkJson(JNIEnv *env, F &&get) noexcept
{
  std::string buffer(16384, '\0');
  size_t length = 0;
  const xcs_status status = get(buffer.data(), buffer.size(), &length);
  if ((status == XCS_OK || status == XCS_ERROR_FAILED) &&
      length < buffer.size())
    return env->NewStringUTF(buffer.c_str());
  return nullptr;
}

/** A string argument that may be null. */
static Java::StringUTFChars
OptionalUTFChars(JNIEnv *env, jstring s) noexcept
{
  return s != nullptr ? Java::String::GetUTFChars(env, s)
                      : Java::StringUTFChars{nullptr};
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

  /* the system CA certificates for curl (Curl::Setup()), e.g. WeGlide */
  CertificateUtil::Initialise(env);

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
Java_org_xcsoar_mobile_NativeCore_nativeAirspaceSetOption(JNIEnv *, jclass,
                                                          jlong core,
                                                          jint option,
                                                          jint value)
{
  return xcs_airspace_set_option(ToCore(core), option, value);
}

/** @return the value, or Integer.MIN_VALUE on error */
JNIEXPORT jint JNICALL
Java_org_xcsoar_mobile_NativeCore_nativeAirspaceGetOption(JNIEnv *, jclass,
                                                          jlong core,
                                                          jint option)
{
  int32_t value;
  return xcs_airspace_get_option(ToCore(core), option, &value) == XCS_OK
    ? value
    : INT32_MIN;
}

/** @return the JSON of xcs_airspace_classes(), or null */
JNIEXPORT jstring JNICALL
Java_org_xcsoar_mobile_NativeCore_nativeAirspaceClasses(JNIEnv *env, jclass,
                                                        jlong core)
{
  std::string buffer(8192, '\0');
  size_t length;
  xcs_status status = xcs_airspace_classes(ToCore(core), buffer.data(),
                                           buffer.size(), &length);
  if (status == XCS_ERROR_INVALID_ARGUMENT && length >= buffer.size()) {
    buffer.resize(length + 1);
    status = xcs_airspace_classes(ToCore(core), buffer.data(),
                                  buffer.size(), &length);
  }

  return status == XCS_OK ? env->NewStringUTF(buffer.c_str()) : nullptr;
}

JNIEXPORT jint JNICALL
Java_org_xcsoar_mobile_NativeCore_nativeAirspaceSetClass(JNIEnv *, jclass,
                                                         jlong core,
                                                         jint airspace_class,
                                                         jint display,
                                                         jint warning)
{
  return xcs_airspace_set_class(ToCore(core), airspace_class, display,
                                warning);
}

JNIEXPORT jint JNICALL
Java_org_xcsoar_mobile_NativeCore_nativeSafetySetOption(JNIEnv *, jclass,
                                                        jlong core, jint option,
                                                        jdouble value)
{
  return xcs_safety_set_option(ToCore(core), option, value);
}

/** @return the value, or NaN on error */
JNIEXPORT jdouble JNICALL
Java_org_xcsoar_mobile_NativeCore_nativeSafetyGetOption(JNIEnv *, jclass,
                                                        jlong core, jint option)
{
  double value;
  return xcs_safety_get_option(ToCore(core), option, &value) == XCS_OK
    ? value
    : std::numeric_limits<double>::quiet_NaN();
}

/** @return the JSON of xcs_polar_get(), or null */
JNIEXPORT jstring JNICALL
Java_org_xcsoar_mobile_NativeCore_nativePolarGet(JNIEnv *env, jclass,
                                                 jlong core, jint index)
{
  char buffer[1024];
  size_t length;
  return xcs_polar_get(ToCore(core), index, buffer, sizeof(buffer), &length)
    == XCS_OK
    ? env->NewStringUTF(buffer)
    : nullptr;
}

JNIEXPORT jint JNICALL
Java_org_xcsoar_mobile_NativeCore_nativePlaneSetDetails(JNIEnv *env, jclass,
                                                        jlong core,
                                                        jstring path,
                                                        jdouble empty_mass,
                                                        jdouble reference_mass,
                                                        jdouble max_ballast,
                                                        jint dump_time,
                                                        jdouble max_speed,
                                                        jdouble wing_area,
                                                        jint handicap)
{
  xcs_plane_details details{};
  details.struct_size = sizeof(details);
  details.empty_mass = empty_mass;
  details.reference_mass = reference_mass;
  details.max_ballast = max_ballast;
  details.dump_time = dump_time;
  details.max_speed = max_speed;
  details.wing_area = wing_area;
  details.handicap = handicap;
  const auto p = Java::String::GetUTFChars(env, path);
  return xcs_plane_set_details(ToCore(core), p.c_str(), &details);
}

JNIEXPORT jint JNICALL
Java_org_xcsoar_mobile_NativeCore_nativeSetCrewMass(JNIEnv *, jclass,
                                                    jlong core, jdouble kg)
{
  return xcs_set_crew_mass(ToCore(core), kg);
}

/** @return kg, or NaN on error */
JNIEXPORT jdouble JNICALL
Java_org_xcsoar_mobile_NativeCore_nativeGetCrewMass(JNIEnv *, jclass,
                                                    jlong core)
{
  double kg;
  return xcs_get_crew_mass(ToCore(core), &kg) == XCS_OK
    ? kg
    : std::numeric_limits<double>::quiet_NaN();
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

JNIEXPORT jstring JNICALL
Java_org_xcsoar_mobile_NativeCore_nativeTilesTypes(JNIEnv *env, jclass,
                                                   jlong core)
{
  return GetJson(env, [core](char *buffer, size_t size, size_t *length){
    return xcs_tiles_types(ToCore(core), buffer, size, length);
  });
}

JNIEXPORT jstring JNICALL
Java_org_xcsoar_mobile_NativeCore_nativeTilesLayouts(JNIEnv *env, jclass,
                                                     jlong core)
{
  return GetJson(env, [core](char *buffer, size_t size, size_t *length){
    return xcs_tiles_layouts(ToCore(core), buffer, size, length);
  });
}

JNIEXPORT jint JNICALL
Java_org_xcsoar_mobile_NativeCore_nativeTilesSet(JNIEnv *, jclass,
                                                 jlong core, jint layout,
                                                 jint tile, jint type)
{
  return xcs_tiles_set(ToCore(core), layout, tile, type);
}

JNIEXPORT jstring JNICALL
Java_org_xcsoar_mobile_NativeCore_nativeTilesUpdate(JNIEnv *env, jclass,
                                                    jlong core, jint layout)
{
  return GetJson(env, [core, layout](char *buffer, size_t size,
                                     size_t *length){
    return xcs_tiles_update(ToCore(core), layout, buffer, size, length);
  });
}

JNIEXPORT jstring JNICALL
Java_org_xcsoar_mobile_NativeCore_nativePlanesList(JNIEnv *env, jclass,
                                                   jlong core)
{
  return GetJson(env, [core](char *buffer, size_t size, size_t *length){
    return xcs_planes_list(ToCore(core), buffer, size, length);
  });
}

JNIEXPORT jstring JNICALL
Java_org_xcsoar_mobile_NativeCore_nativePolarsList(JNIEnv *env, jclass,
                                                   jlong core)
{
  return GetJson(env, [core](char *buffer, size_t size, size_t *length){
    return xcs_polars_list(ToCore(core), buffer, size, length);
  });
}

JNIEXPORT jstring JNICALL
Java_org_xcsoar_mobile_NativeCore_nativePlaneSave(JNIEnv *env, jclass,
                                                  jlong core, jstring path,
                                                  jstring registration,
                                                  jstring competition_id,
                                                  jstring type, jint polar,
                                                  jint weglide_type,
                                                  jboolean double_seater)
{
  const auto p = Java::String::GetUTFChars(env, path);
  const auto r = Java::String::GetUTFChars(env, registration);
  const auto c = Java::String::GetUTFChars(env, competition_id);
  const auto t = Java::String::GetUTFChars(env, type);
  return GetJson(env, [&](char *buffer, size_t size, size_t *length){
    return xcs_plane_save(ToCore(core), p.c_str(), r.c_str(), c.c_str(),
                          t.c_str(), polar, weglide_type, double_seater,
                          buffer, size, length);
  });
}

JNIEXPORT jint JNICALL
Java_org_xcsoar_mobile_NativeCore_nativePlaneActivate(JNIEnv *env, jclass,
                                                      jlong core,
                                                      jstring path)
{
  const auto p = Java::String::GetUTFChars(env, path);
  return xcs_plane_activate(ToCore(core), p.c_str());
}

JNIEXPORT jint JNICALL
Java_org_xcsoar_mobile_NativeCore_nativePlaneDelete(JNIEnv *env, jclass,
                                                    jlong core, jstring path)
{
  const auto p = Java::String::GetUTFChars(env, path);
  return xcs_plane_delete(ToCore(core), p.c_str());
}

JNIEXPORT jstring JNICALL
Java_org_xcsoar_mobile_NativeCore_nativeCrewGet(JNIEnv *env, jclass,
                                                jlong core)
{
  return GetJson(env, [core](char *buffer, size_t size, size_t *length){
    return xcs_crew_get(ToCore(core), buffer, size, length);
  });
}

JNIEXPORT jint JNICALL
Java_org_xcsoar_mobile_NativeCore_nativeCrewSet(JNIEnv *env, jclass,
                                                jlong core, jstring pilot,
                                                jstring copilot)
{
  const auto p = OptionalUTFChars(env, pilot);
  const auto c = OptionalUTFChars(env, copilot);
  return xcs_crew_set(ToCore(core), p.c_str(), c.c_str());
}

JNIEXPORT jstring JNICALL
Java_org_xcsoar_mobile_NativeCore_nativeRaspGet(JNIEnv *env, jclass,
                                                jlong core)
{
  return GetJson(env, [core](char *buffer, size_t size, size_t *length){
    return xcs_rasp_get(ToCore(core), buffer, size, length);
  });
}

JNIEXPORT jint JNICALL
Java_org_xcsoar_mobile_NativeCore_nativeRaspSet(JNIEnv *env, jclass,
                                                jlong core, jint field,
                                                jstring time)
{
  const auto t = OptionalUTFChars(env, time);
  return xcs_rasp_set(ToCore(core), field, t.c_str());
}

JNIEXPORT jstring JNICALL
Java_org_xcsoar_mobile_NativeCore_nativeWeatherList(JNIEnv *env, jclass,
                                                    jlong core)
{
  return GetJson(env, [core](char *buffer, size_t size, size_t *length){
    return xcs_weather_list(ToCore(core), buffer, size, length);
  });
}

JNIEXPORT jint JNICALL
Java_org_xcsoar_mobile_NativeCore_nativeWeatherAdd(JNIEnv *env, jclass,
                                                   jlong core, jstring code)
{
  const auto c = Java::String::GetUTFChars(env, code);
  return xcs_weather_add(ToCore(core), c.c_str());
}

JNIEXPORT jint JNICALL
Java_org_xcsoar_mobile_NativeCore_nativeWeatherRemove(JNIEnv *env, jclass,
                                                      jlong core, jstring code)
{
  const auto c = Java::String::GetUTFChars(env, code);
  return xcs_weather_remove(ToCore(core), c.c_str());
}

JNIEXPORT jint JNICALL
Java_org_xcsoar_mobile_NativeCore_nativeWeatherUpdate(JNIEnv *, jclass,
                                                      jlong core)
{
  return xcs_weather_update(ToCore(core));
}

JNIEXPORT jstring JNICALL
Java_org_xcsoar_mobile_NativeCore_nativeTrackingGet(JNIEnv *env, jclass,
                                                    jlong core)
{
  return GetJson(env, [core](char *buffer, size_t size, size_t *length){
    return xcs_tracking_get(ToCore(core), buffer, size, length);
  });
}

JNIEXPORT jint JNICALL
Java_org_xcsoar_mobile_NativeCore_nativeTrackingSet(JNIEnv *env, jclass,
                                                    jlong core, jstring json)
{
  const auto j = Java::String::GetUTFChars(env, json);
  return xcs_tracking_set(ToCore(core), j.c_str());
}

JNIEXPORT jstring JNICALL
Java_org_xcsoar_mobile_NativeCore_nativeWeGlideGet(JNIEnv *env, jclass,
                                                   jlong core)
{
  return GetJson(env, [core](char *buffer, size_t size, size_t *length){
    return xcs_weglide_get(ToCore(core), buffer, size, length);
  });
}

JNIEXPORT jint JNICALL
Java_org_xcsoar_mobile_NativeCore_nativeWeGlideSet(JNIEnv *env, jclass,
                                                   jlong core,
                                                   jboolean enabled,
                                                   jint pilot_id,
                                                   jstring birthdate)
{
  const auto b = Java::String::GetUTFChars(env, birthdate);
  return xcs_weglide_set(ToCore(core), enabled, pilot_id, b.c_str());
}

JNIEXPORT jstring JNICALL
Java_org_xcsoar_mobile_NativeCore_nativeWeGlideAircraftSearch(JNIEnv *env,
                                                              jclass,
                                                              jlong core,
                                                              jstring query,
                                                              jint max)
{
  const auto q = Java::String::GetUTFChars(env, query);
  return GetJson(env, [&](char *buffer, size_t size, size_t *length){
    return xcs_weglide_aircraft_search(ToCore(core), q.c_str(), max,
                                       buffer, size, length);
  });
}

JNIEXPORT jstring JNICALL
Java_org_xcsoar_mobile_NativeCore_nativeWeGlideAircraftUpdate(JNIEnv *env,
                                                              jclass,
                                                              jlong core)
{
  return GetNetworkJson(env, [core](char *buffer, size_t size,
                                    size_t *length){
    return xcs_weglide_aircraft_update(ToCore(core), buffer, size, length);
  });
}

JNIEXPORT jstring JNICALL
Java_org_xcsoar_mobile_NativeCore_nativeWeGlideAircraftGet(JNIEnv *env,
                                                           jclass,
                                                           jlong core,
                                                           jint id)
{
  return GetNetworkJson(env, [core, id](char *buffer, size_t size,
                                        size_t *length){
    return xcs_weglide_aircraft_get(ToCore(core), id, buffer, size, length);
  });
}

JNIEXPORT jstring JNICALL
Java_org_xcsoar_mobile_NativeCore_nativeWeGlideUpload(JNIEnv *env, jclass,
                                                      jlong core,
                                                      jstring igc_path)
{
  const auto p = Java::String::GetUTFChars(env, igc_path);
  return GetNetworkJson(env, [&](char *buffer, size_t size, size_t *length){
    return xcs_weglide_upload(ToCore(core), p.c_str(), buffer, size,
                              length);
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
