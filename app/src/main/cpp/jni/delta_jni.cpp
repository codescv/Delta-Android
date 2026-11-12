// Delta for Android — JNI bridge between Kotlin (`NativeBridge`) and the Delta
// 2.0.5 libretro host (core/libretro_host.*). Mirrors bridge/LRBridge.mm.
//
// Natives are registered dynamically in JNI_OnLoad so the Kotlin package can be
// renamed by changing kBridgeClass only.

#include <jni.h>

#include <cstdint>
#include <cstdio>
#include <cstring>
#include <memory>
#include <mutex>
#include <string>
#include <vector>

#include "core/emu_util.h"
#include "core/libretro_host.h"
#include "core/pixel_convert.h"

#if defined(__ANDROID__)
#include <android/log.h>
#define DLOG(level, ...) __android_log_print(level, "DeltaCore", __VA_ARGS__)
#define DLOG_INFO ANDROID_LOG_INFO
#define DLOG_WARN ANDROID_LOG_WARN
#define DLOG_ERROR ANDROID_LOG_ERROR
#define DLOG_DEBUG ANDROID_LOG_DEBUG
#else
#include <cstdio>
#define DLOG(level, ...)          \
  do {                            \
    (void)(level);                \
    std::fprintf(stderr, "DeltaCore: "); \
    std::fprintf(stderr, __VA_ARGS__);   \
    std::fprintf(stderr, "\n");   \
  } while (0)
#define DLOG_INFO 4
#define DLOG_WARN 5
#define DLOG_ERROR 6
#define DLOG_DEBUG 3
#endif

namespace {

using lemuroid::LibretroHost;

constexpr const char* kBridgeClass = "com/codescv/delta/emulation/NativeBridge";

struct Session {
  std::unique_ptr<LibretroHost> host;
  std::vector<float> left;
  std::vector<float> right;
};

Session* FromHandle(jlong handle) {
  return reinterpret_cast<Session*>(static_cast<intptr_t>(handle));
}

LibretroHost* HostOf(jlong handle) {
  Session* s = FromHandle(handle);
  return (s && s->host) ? s->host.get() : nullptr;
}

std::string ToStd(JNIEnv* env, jstring s) {
  if (s == nullptr) return {};
  const char* chars = env->GetStringUTFChars(s, nullptr);
  std::string out = chars ? chars : "";
  if (chars) env->ReleaseStringUTFChars(s, chars);
  return out;
}

jstring ToJ(JNIEnv* env, const std::string& s) {
  return env->NewStringUTF(s.c_str());
}

jbyteArray ToJBytes(JNIEnv* env, const std::vector<uint8_t>& v) {
  jbyteArray arr = env->NewByteArray(static_cast<jsize>(v.size()));
  if (arr && !v.empty()) {
    env->SetByteArrayRegion(arr, 0, static_cast<jsize>(v.size()),
                            reinterpret_cast<const jbyte*>(v.data()));
  }
  return arr;
}

std::string JsonEscape(const std::string& s) {
  std::string out;
  out.reserve(s.size() + 8);
  for (unsigned char c : s) {
    switch (c) {
      case '"': out += "\\\""; break;
      case '\\': out += "\\\\"; break;
      case '\n': out += "\\n"; break;
      case '\r': out += "\\r"; break;
      case '\t': out += "\\t"; break;
      default:
        if (c < 0x20) {
          char buf[8];
          std::snprintf(buf, sizeof(buf), "\\u%04x", c);
          out += buf;
        } else {
          out += static_cast<char>(c);
        }
    }
  }
  return out;
}

// ---------------------------------------------------------------------------
// Core discovery & lifecycle
// ---------------------------------------------------------------------------

jobjectArray BuiltinCores(JNIEnv* env, jclass) {
  const auto& cores = lemuroid::BuiltinCores();
  jclass str = env->FindClass("java/lang/String");
  jobjectArray arr =
      env->NewObjectArray(static_cast<jsize>(cores.size() * 3), str, nullptr);
  for (size_t i = 0; i < cores.size(); ++i) {
    env->SetObjectArrayElement(arr, static_cast<jsize>(i * 3),
                               env->NewStringUTF(cores[i].id));
    env->SetObjectArrayElement(arr, static_cast<jsize>(i * 3 + 1),
                               env->NewStringUTF(cores[i].display_name));
    env->SetObjectArrayElement(arr, static_cast<jsize>(i * 3 + 2),
                               env->NewStringUTF(cores[i].system_id));
  }
  return arr;
}

// Returns a handle (> 0) or throws IllegalStateException with the error.
jlong Create(JNIEnv* env, jclass, jstring core_id) {
  std::string error;
  std::string id = ToStd(env, core_id);
  auto host = LibretroHost::CreateBuiltin(id, &error);
  if (!host) {
    jclass ex = env->FindClass("java/lang/IllegalStateException");
    env->ThrowNew(ex, ("cannot create core '" + id + "': " + error).c_str());
    return 0;
  }
  host->SetLogCallback([](int level, const std::string& message) {
    const int prio = level >= 3 ? DLOG_ERROR : level == 2 ? DLOG_WARN
                     : level == 1 ? DLOG_INFO : DLOG_DEBUG;
    DLOG(prio, "%s", message.c_str());
  });
  auto* session = new Session();
  session->host = std::move(host);
  return static_cast<jlong>(reinterpret_cast<intptr_t>(session));
}

void Destroy(JNIEnv*, jclass, jlong handle) {
  Session* s = FromHandle(handle);
  if (!s) return;
  if (s->host) s->host->Shutdown();
  s->host.reset();
  delete s;
}

jobjectArray CoreInfo(JNIEnv* env, jclass, jlong handle) {
  LibretroHost* h = HostOf(handle);
  jclass str = env->FindClass("java/lang/String");
  jobjectArray arr = env->NewObjectArray(3, str, nullptr);
  if (!h) return arr;
  env->SetObjectArrayElement(arr, 0, ToJ(env, h->library_name()));
  env->SetObjectArrayElement(arr, 1, ToJ(env, h->library_version()));
  env->SetObjectArrayElement(arr, 2, ToJ(env, h->valid_extensions()));
  return arr;
}

void SetDirectories(JNIEnv* env, jclass, jlong handle, jstring system_dir,
                    jstring save_dir) {
  if (LibretroHost* h = HostOf(handle)) {
    h->SetSystemDirectory(ToStd(env, system_dir));
    h->SetSaveDirectory(ToStd(env, save_dir));
  }
}

void SetOption(JNIEnv* env, jclass, jlong handle, jstring key, jstring value) {
  if (LibretroHost* h = HostOf(handle)) {
    h->SetVariable(ToStd(env, key), ToStd(env, value));
  }
}

jstring GetOption(JNIEnv* env, jclass, jlong handle, jstring key) {
  LibretroHost* h = HostOf(handle);
  return ToJ(env, h ? h->GetVariable(ToStd(env, key)) : "");
}

// JSON array of {key,title,info,values[],labels[],default,value,visible}.
jstring CoreOptionsJson(JNIEnv* env, jclass, jlong handle) {
  LibretroHost* h = HostOf(handle);
  std::string out = "[";
  if (h) {
    bool first = true;
    for (const auto& o : h->CoreOptions()) {
      if (!first) out += ",";
      first = false;
      out += "{\"key\":\"" + JsonEscape(o.key) + "\",\"title\":\"" +
             JsonEscape(o.description) + "\",\"info\":\"" + JsonEscape(o.info) +
             "\",\"values\":[";
      for (size_t i = 0; i < o.values.size(); ++i) {
        if (i) out += ",";
        out += "\"" + JsonEscape(o.values[i]) + "\"";
      }
      out += "],\"labels\":[";
      for (size_t i = 0; i < o.labels.size(); ++i) {
        if (i) out += ",";
        out += "\"" + JsonEscape(o.labels[i]) + "\"";
      }
      out += "],\"default\":\"" + JsonEscape(o.default_value) +
             "\",\"value\":\"" + JsonEscape(o.value) + "\",\"visible\":" +
             (o.visible ? "true" : "false") + "}";
    }
  }
  out += "]";
  return ToJ(env, out);
}

// Returns null on success or an error message.
jstring LoadGame(JNIEnv* env, jclass, jlong handle, jstring path) {
  LibretroHost* h = HostOf(handle);
  if (!h) return ToJ(env, "no core");
  std::string error;
  if (!h->LoadGame(ToStd(env, path), &error)) return ToJ(env, error);
  return nullptr;
}

jstring LoadGameData(JNIEnv* env, jclass, jlong handle, jbyteArray data,
                     jstring virtual_path) {
  LibretroHost* h = HostOf(handle);
  if (!h) return ToJ(env, "no core");
  if (!data) return ToJ(env, "no data");
  const jsize n = env->GetArrayLength(data);
  std::vector<uint8_t> bytes(static_cast<size_t>(n));
  env->GetByteArrayRegion(data, 0, n, reinterpret_cast<jbyte*>(bytes.data()));
  std::string error;
  if (!h->LoadGameFromMemory(ToStd(env, virtual_path), bytes.data(),
                             bytes.size(), &error)) {
    return ToJ(env, error);
  }
  return nullptr;
}

void UnloadGame(JNIEnv*, jclass, jlong handle) {
  if (LibretroHost* h = HostOf(handle)) h->UnloadGame();
}

void Reset(JNIEnv*, jclass, jlong handle) {
  if (LibretroHost* h = HostOf(handle)) h->Reset();
}

jboolean GameLoaded(JNIEnv*, jclass, jlong handle) {
  LibretroHost* h = HostOf(handle);
  return h && h->game_loaded() ? JNI_TRUE : JNI_FALSE;
}

void RunFrames(JNIEnv*, jclass, jlong handle, jint count, jboolean ff) {
  if (LibretroHost* h = HostOf(handle)) h->RunFrames(count, ff == JNI_TRUE);
}

jlong FramesRun(JNIEnv*, jclass, jlong handle) {
  LibretroHost* h = HostOf(handle);
  return h ? static_cast<jlong>(h->frames_run()) : 0;
}

// ---------------------------------------------------------------------------
// Input
// ---------------------------------------------------------------------------

void SetJoypadMask(JNIEnv*, jclass, jlong handle, jint port, jint mask) {
  if (LibretroHost* h = HostOf(handle)) {
    h->SetJoypadMask(static_cast<unsigned>(port), static_cast<uint16_t>(mask));
  }
}

jint JoypadMask(JNIEnv*, jclass, jlong handle, jint port) {
  LibretroHost* h = HostOf(handle);
  return h ? h->joypad_mask(static_cast<unsigned>(port)) : 0;
}

void SetAnalog(JNIEnv*, jclass, jlong handle, jint port, jint index, jint x,
               jint y) {
  if (LibretroHost* h = HostOf(handle)) {
    h->SetAnalog(static_cast<unsigned>(port), static_cast<unsigned>(index),
                 static_cast<int16_t>(x), static_cast<int16_t>(y));
  }
}

void SetPointer(JNIEnv*, jclass, jlong handle, jboolean pressed, jint x,
                jint y) {
  if (LibretroHost* h = HostOf(handle)) {
    h->SetPointerState(pressed == JNI_TRUE, static_cast<int16_t>(x),
                       static_cast<int16_t>(y));
  }
}

void ClearCheats(JNIEnv*, jclass, jlong handle) {
  if (LibretroHost* h = HostOf(handle)) h->ClearCheats();
}

void SetCheat(JNIEnv* env, jclass, jlong handle, jint index, jboolean enabled,
              jstring code) {
  if (LibretroHost* h = HostOf(handle)) {
    h->SetCheat(static_cast<unsigned>(index), enabled == JNI_TRUE,
                ToStd(env, code));
  }
}

// ---------------------------------------------------------------------------
// Video
// ---------------------------------------------------------------------------

// [baseWidth, baseHeight, maxWidth, maxHeight, rotation, pixelFormat]
jintArray VideoGeometry(JNIEnv* env, jclass, jlong handle) {
  jint vals[6] = {0, 0, 0, 0, 0, 0};
  if (LibretroHost* h = HostOf(handle)) {
    const auto g = h->geometry();
    vals[0] = static_cast<jint>(g.base_width);
    vals[1] = static_cast<jint>(g.base_height);
    vals[2] = static_cast<jint>(g.max_width);
    vals[3] = static_cast<jint>(g.max_height);
    vals[4] = static_cast<jint>(h->rotation());
    vals[5] = static_cast<jint>(h->pixel_format());
  }
  jintArray arr = env->NewIntArray(6);
  env->SetIntArrayRegion(arr, 0, 6, vals);
  return arr;
}

// [aspectRatio, fps, sampleRate]
jdoubleArray Timing(JNIEnv* env, jclass, jlong handle) {
  jdouble vals[3] = {0, 60, 44100};
  if (LibretroHost* h = HostOf(handle)) {
    vals[0] = h->geometry().aspect_ratio;
    vals[1] = h->fps();
    vals[2] = h->sample_rate();
  }
  jdoubleArray arr = env->NewDoubleArray(3);
  env->SetDoubleArrayRegion(arr, 0, 3, vals);
  return arr;
}

jlong VideoSerial(JNIEnv*, jclass, jlong handle) {
  LibretroHost* h = HostOf(handle);
  return h ? static_cast<jlong>(h->video_serial()) : 0;
}

// Copies the latest RGBA8888 frame into a direct ByteBuffer.
// Returns (width << 16) | height, or 0 if no frame / buffer too small.
jint CopyFrame(JNIEnv* env, jclass, jlong handle, jobject buffer) {
  LibretroHost* h = HostOf(handle);
  if (!h || !buffer) return 0;
  void* addr = env->GetDirectBufferAddress(buffer);
  const jlong cap = env->GetDirectBufferCapacity(buffer);
  if (!addr || cap <= 0) return 0;
  unsigned w = 0, hgt = 0;
  uint64_t serial = 0;
  if (!h->CopyFrameRgbaInto(static_cast<uint8_t*>(addr),
                            static_cast<size_t>(cap), &w, &hgt, &serial)) {
    return 0;
  }
  return static_cast<jint>((w << 16) | (hgt & 0xFFFF));
}

// ---------------------------------------------------------------------------
// Audio (pulled by the Kotlin AudioTrack MODE_STREAM thread)
// ---------------------------------------------------------------------------

// Renders `frames` stereo frames of interleaved float PCM at `output_rate`.
jint RenderAudio(JNIEnv* env, jclass, jlong handle, jfloatArray out,
                 jint frames, jdouble output_rate) {
  Session* s = FromHandle(handle);
  if (!s || !s->host || !out || frames <= 0) return 0;
  const size_t n = static_cast<size_t>(frames);
  if (s->left.size() < n) {
    s->left.resize(n);
    s->right.resize(n);
  }
  const size_t rendered = s->host->audio_resampler()->Render(
      s->left.data(), s->right.data(), n, output_rate);
  std::vector<float> interleaved(n * 2);
  for (size_t i = 0; i < n; ++i) {
    interleaved[i * 2] = s->left[i];
    interleaved[i * 2 + 1] = s->right[i];
  }
  env->SetFloatArrayRegion(out, 0, static_cast<jsize>(n * 2),
                           interleaved.data());
  return static_cast<jint>(rendered);
}

void SetAudioMuted(JNIEnv*, jclass, jlong handle, jboolean muted) {
  if (LibretroHost* h = HostOf(handle)) {
    h->audio_resampler()->SetMuted(muted == JNI_TRUE);
  }
}

void SetAudioVolume(JNIEnv*, jclass, jlong handle, jfloat volume) {
  if (LibretroHost* h = HostOf(handle)) h->audio_resampler()->SetVolume(volume);
}

jint QueuedAudioFrames(JNIEnv*, jclass, jlong handle) {
  LibretroHost* h = HostOf(handle);
  return h ? static_cast<jint>(h->audio_ring()->AvailableToRead()) : 0;
}

jlong TotalAudioFramesWritten(JNIEnv*, jclass, jlong handle) {
  LibretroHost* h = HostOf(handle);
  return h ? static_cast<jlong>(h->audio_ring()->total_written()) : 0;
}

// ---------------------------------------------------------------------------
// Save states & battery SRAM
// ---------------------------------------------------------------------------

jbyteArray SaveState(JNIEnv* env, jclass, jlong handle) {
  LibretroHost* h = HostOf(handle);
  if (!h) return nullptr;
  std::vector<uint8_t> out;
  std::string error;
  if (!h->SerializeState(&out, &error)) {
    DLOG(DLOG_WARN, "save state failed: %s", error.c_str());
    return nullptr;
  }
  return ToJBytes(env, out);
}

jstring LoadState(JNIEnv* env, jclass, jlong handle, jbyteArray data) {
  LibretroHost* h = HostOf(handle);
  if (!h) return ToJ(env, "no core");
  if (!data) return ToJ(env, "empty save state");
  const jsize n = env->GetArrayLength(data);
  std::vector<uint8_t> bytes(static_cast<size_t>(n));
  env->GetByteArrayRegion(data, 0, n, reinterpret_cast<jbyte*>(bytes.data()));
  std::string error;
  if (!h->UnserializeState(bytes.data(), bytes.size(), &error)) {
    return ToJ(env, error);
  }
  return nullptr;
}

jint SaveRamSize(JNIEnv*, jclass, jlong handle) {
  LibretroHost* h = HostOf(handle);
  return h ? static_cast<jint>(h->save_ram_size()) : 0;
}

jbyteArray GetSaveRam(JNIEnv* env, jclass, jlong handle) {
  LibretroHost* h = HostOf(handle);
  if (!h) return nullptr;
  auto v = h->SaveRam();
  if (v.empty()) return nullptr;
  return ToJBytes(env, v);
}

jstring LoadSaveRam(JNIEnv* env, jclass, jlong handle, jbyteArray data) {
  LibretroHost* h = HostOf(handle);
  if (!h) return ToJ(env, "no core");
  if (!data) return ToJ(env, "empty save");
  const jsize n = env->GetArrayLength(data);
  std::vector<uint8_t> bytes(static_cast<size_t>(n));
  env->GetByteArrayRegion(data, 0, n, reinterpret_cast<jbyte*>(bytes.data()));
  std::string error;
  if (!h->LoadSaveRam(bytes.data(), bytes.size(), &error)) {
    return ToJ(env, error);
  }
  return nullptr;
}

// ---------------------------------------------------------------------------
// Utilities
// ---------------------------------------------------------------------------

jint Crc32(JNIEnv* env, jclass, jbyteArray data, jint offset, jint length) {
  if (!data || length <= 0) return 0;
  jbyte* p = env->GetByteArrayElements(data, nullptr);
  const uint32_t crc = lemuroid::Crc32(p + offset, static_cast<size_t>(length));
  env->ReleaseByteArrayElements(data, p, JNI_ABORT);
  return static_cast<jint>(crc);
}

jint Crc32Update(JNIEnv* env, jclass, jint crc, jbyteArray data, jint offset,
                 jint length) {
  if (!data || length <= 0) return crc;
  jbyte* p = env->GetByteArrayElements(data, nullptr);
  const uint32_t out = lemuroid::Crc32Update(static_cast<uint32_t>(crc),
                                             p + offset,
                                             static_cast<size_t>(length));
  env->ReleaseByteArrayElements(data, p, JNI_ABORT);
  return static_cast<jint>(out);
}

const JNINativeMethod kMethods[] = {
    {"nativeBuiltinCores", "()[Ljava/lang/String;", (void*)BuiltinCores},
    {"nativeCreate", "(Ljava/lang/String;)J", (void*)Create},
    {"nativeDestroy", "(J)V", (void*)Destroy},
    {"nativeCoreInfo", "(J)[Ljava/lang/String;", (void*)CoreInfo},
    {"nativeSetDirectories", "(JLjava/lang/String;Ljava/lang/String;)V",
     (void*)SetDirectories},
    {"nativeSetOption", "(JLjava/lang/String;Ljava/lang/String;)V",
     (void*)SetOption},
    {"nativeGetOption", "(JLjava/lang/String;)Ljava/lang/String;",
     (void*)GetOption},
    {"nativeCoreOptionsJson", "(J)Ljava/lang/String;", (void*)CoreOptionsJson},
    {"nativeLoadGame", "(JLjava/lang/String;)Ljava/lang/String;",
     (void*)LoadGame},
    {"nativeLoadGameData", "(J[BLjava/lang/String;)Ljava/lang/String;",
     (void*)LoadGameData},
    {"nativeUnloadGame", "(J)V", (void*)UnloadGame},
    {"nativeReset", "(J)V", (void*)Reset},
    {"nativeGameLoaded", "(J)Z", (void*)GameLoaded},
    {"nativeRunFrames", "(JIZ)V", (void*)RunFrames},
    {"nativeFramesRun", "(J)J", (void*)FramesRun},
    {"nativeSetJoypadMask", "(JII)V", (void*)SetJoypadMask},
    {"nativeJoypadMask", "(JI)I", (void*)JoypadMask},
    {"nativeSetAnalog", "(JIIII)V", (void*)SetAnalog},
    {"nativeSetPointer", "(JZII)V", (void*)SetPointer},
    {"nativeClearCheats", "(J)V", (void*)ClearCheats},
    {"nativeSetCheat", "(JIZLjava/lang/String;)V", (void*)SetCheat},
    {"nativeVideoGeometry", "(J)[I", (void*)VideoGeometry},
    {"nativeTiming", "(J)[D", (void*)Timing},
    {"nativeVideoSerial", "(J)J", (void*)VideoSerial},
    {"nativeCopyFrame", "(JLjava/nio/ByteBuffer;)I", (void*)CopyFrame},
    {"nativeRenderAudio", "(J[FID)I", (void*)RenderAudio},
    {"nativeSetAudioMuted", "(JZ)V", (void*)SetAudioMuted},
    {"nativeSetAudioVolume", "(JF)V", (void*)SetAudioVolume},
    {"nativeQueuedAudioFrames", "(J)I", (void*)QueuedAudioFrames},
    {"nativeTotalAudioFramesWritten", "(J)J", (void*)TotalAudioFramesWritten},
    {"nativeSaveState", "(J)[B", (void*)SaveState},
    {"nativeLoadState", "(J[B)Ljava/lang/String;", (void*)LoadState},
    {"nativeSaveRamSize", "(J)I", (void*)SaveRamSize},
    {"nativeGetSaveRam", "(J)[B", (void*)GetSaveRam},
    {"nativeLoadSaveRam", "(J[B)Ljava/lang/String;", (void*)LoadSaveRam},
    {"nativeCrc32", "([BII)I", (void*)Crc32},
    {"nativeCrc32Update", "(I[BII)I", (void*)Crc32Update},
};

}  // namespace

extern "C" JNIEXPORT jint JNI_OnLoad(JavaVM* vm, void*) {
  JNIEnv* env = nullptr;
  if (vm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6) != JNI_OK) {
    return JNI_ERR;
  }
  jclass cls = env->FindClass(kBridgeClass);
  if (cls == nullptr) {
    DLOG(DLOG_ERROR, "JNI_OnLoad: class %s not found", kBridgeClass);
    return JNI_ERR;
  }
  if (env->RegisterNatives(cls, kMethods,
                           sizeof(kMethods) / sizeof(kMethods[0])) != JNI_OK) {
    DLOG(DLOG_ERROR, "JNI_OnLoad: RegisterNatives failed");
    return JNI_ERR;
  }
  return JNI_VERSION_1_6;
}
