// Copyright 2026 Google LLC

#include "core/libretro_host.h"

#include <dlfcn.h>
#include <unistd.h>

#include <algorithm>
#include <cstdarg>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <fstream>
#include <iterator>
#include <utility>

#include "core/chip8_libretro.h"
#include "core/pixel_convert.h"

namespace delta {
namespace {

struct retro_core_options_intl_payload {
  const retro_core_option_definition* us;
  const retro_core_option_definition* local;
};
struct retro_core_option_display_payload {
  const char* key;
  bool visible;
};

void SetError(std::string* error, const std::string& message) {
  if (error) *error = message;
}

std::string Trim(const std::string& s) {
  const size_t b = s.find_first_not_of(" \t");
  if (b == std::string::npos) return "";
  const size_t e = s.find_last_not_of(" \t");
  return s.substr(b, e - b + 1);
}

CoreOption ParseLegacyVariable(const char* key, const char* value) {
  CoreOption option;
  option.key = key;
  std::string text = value ? value : "";
  const size_t semi = text.find(';');
  option.description =
      Trim(semi == std::string::npos ? text : text.substr(0, semi));
  if (semi != std::string::npos) {
    std::string rest = text.substr(semi + 1);
    size_t start = 0;
    while (start <= rest.size()) {
      size_t bar = rest.find('|', start);
      if (bar == std::string::npos) bar = rest.size();
      std::string v = Trim(rest.substr(start, bar - start));
      if (!v.empty()) {
        option.values.push_back(v);
        option.labels.push_back(v);
      }
      start = bar + 1;
    }
  }
  if (!option.values.empty()) option.default_value = option.values.front();
  return option;
}

}  // namespace

std::atomic<LibretroHost*> LibretroHost::active_{nullptr};

const std::vector<BuiltinCoreInfo>& BuiltinCores() {
  static const std::vector<BuiltinCoreInfo>* const kCores =
      new std::vector<BuiltinCoreInfo>{
          {"chip8", "Lemuroid CHIP-8", "chip8", &lr_builtin_chip8_core},
          {"nes", "QuickNES (NES)", "nes", &lr_builtin_nes_core},
          {"snes", "Snes9x 2005 (SNES)", "snes", &lr_builtin_snes_core},
          {"n64", "Mupen64Plus / parallel-n64 (N64)", "n64",
           &lr_builtin_n64_core},
          {"gbc", "Gambatte (GB/GBC)", "gbc", &lr_builtin_gbc_core},
          {"gba", "VBA Next (GBA)", "gba", &lr_builtin_gba_core},
          {"ds", "melonDS (Nintendo DS)", "ds", &lr_builtin_ds_core},
          {"genesis", "Genesis Plus GX (Genesis/SMS/GG)", "genesis",
           &lr_builtin_genesis_core},
      };
  return *kCores;
}

LibretroHost::LibretroHost(const lr_core_interface& core, std::string core_path,
                           void* dl_handle)
    : core_(core),
      core_path_(std::move(core_path)),
      dl_handle_(dl_handle),
      audio_ring_(4096),
      resampler_(&audio_ring_) {}

std::unique_ptr<LibretroHost> LibretroHost::CreateWithInterface(
    const lr_core_interface& core, const std::string& core_path,
    void* dl_handle, std::string* error, const HostDirs& dirs) {
  const void* const* fns = reinterpret_cast<const void* const*>(&core);
  for (size_t k = 0; k < sizeof(core) / sizeof(void*); ++k) {
    if (fns[k] == nullptr) {
      SetError(error, "core interface is missing an entry point");
      if (dl_handle) dlclose(dl_handle);
      return nullptr;
    }
  }
  std::unique_ptr<LibretroHost> host(
      new LibretroHost(core, core_path, dl_handle));
  LibretroHost* expected = nullptr;
  if (!active_.compare_exchange_strong(expected, host.get())) {
    SetError(error, "another libretro core is already running");
    host->dl_handle_ = nullptr;
    if (dl_handle) dlclose(dl_handle);
    return nullptr;
  }

  std::lock_guard<std::recursive_mutex> lock(host->core_mutex_);
  // Directories are applied before retro_set_environment()/retro_init().
  host->system_dir_ = dirs.system_dir;
  host->save_dir_ = dirs.save_dir;
  host->cache_dir_ = dirs.cache_dir;
  host->api_version_ = core.retro_api_version();
  if (host->api_version_ != RETRO_API_VERSION) {
    SetError(error, "unsupported libretro API version " +
                        std::to_string(host->api_version_));
    return nullptr;
  }
  retro_system_info info{};
  core.retro_get_system_info(&info);
  host->library_name_ = info.library_name ? info.library_name : "Unknown";
  host->library_version_ = info.library_version ? info.library_version : "";
  host->valid_extensions_ = info.valid_extensions ? info.valid_extensions : "";
  host->need_fullpath_ = info.need_fullpath;

  core.retro_set_environment(&EnvironmentThunk);
  core.retro_set_video_refresh(&VideoThunk);
  core.retro_set_audio_sample(&AudioSampleThunk);
  core.retro_set_audio_sample_batch(&AudioBatchThunk);
  core.retro_set_input_poll(&InputPollThunk);
  core.retro_set_input_state(&InputStateThunk);
  core.retro_init();
  host->initialized_ = true;
  return host;
}

std::unique_ptr<LibretroHost> LibretroHost::CreateBuiltin(
    const std::string& core_id, std::string* error) {
  return CreateBuiltin(core_id, HostDirs(), error);
}

std::unique_ptr<LibretroHost> LibretroHost::CreateBuiltin(
    const std::string& core_id, const HostDirs& dirs, std::string* error) {
  for (const BuiltinCoreInfo& info : BuiltinCores()) {
    if (core_id == info.id) {
      return CreateWithInterface(*info.get_interface(),
                                 std::string("builtin:") + info.id, nullptr,
                                 error, dirs);
    }
  }
  SetError(error, "unknown built-in core '" + core_id + "'");
  return nullptr;
}

std::unique_ptr<LibretroHost> LibretroHost::CreateFromDynamicLibrary(
    const std::string& path, std::string* error) {
  void* handle = dlopen(path.c_str(), RTLD_NOW | RTLD_LOCAL);
  if (handle == nullptr) {
    const char* why = dlerror();
    SetError(error, std::string("dlopen failed: ") + (why ? why : path));
    return nullptr;
  }
  return CreateFromDlHandle(handle, path, error);
}

std::unique_ptr<LibretroHost> LibretroHost::CreateFromDlHandle(
    void* handle, const std::string& path, std::string* error) {
  if (handle == nullptr) {
    SetError(error, "null library handle");
    return nullptr;
  }
  lr_core_interface core{};
  bool ok = true;
  std::string missing;
#define LR_RESOLVE(name)                          \
  do {                                            \
    void* sym = dlsym(handle, #name);             \
    if (sym == nullptr) {                         \
      ok = false;                                 \
      missing = #name;                            \
    } else {                                      \
      std::memcpy(&core.name, &sym, sizeof(sym)); \
    }                                             \
  } while (0)
  LR_RESOLVE(retro_set_environment);
  LR_RESOLVE(retro_set_video_refresh);
  LR_RESOLVE(retro_set_audio_sample);
  LR_RESOLVE(retro_set_audio_sample_batch);
  LR_RESOLVE(retro_set_input_poll);
  LR_RESOLVE(retro_set_input_state);
  LR_RESOLVE(retro_init);
  LR_RESOLVE(retro_deinit);
  LR_RESOLVE(retro_api_version);
  LR_RESOLVE(retro_get_system_info);
  LR_RESOLVE(retro_get_system_av_info);
  LR_RESOLVE(retro_set_controller_port_device);
  LR_RESOLVE(retro_reset);
  LR_RESOLVE(retro_run);
  LR_RESOLVE(retro_serialize_size);
  LR_RESOLVE(retro_serialize);
  LR_RESOLVE(retro_unserialize);
  LR_RESOLVE(retro_cheat_reset);
  LR_RESOLVE(retro_cheat_set);
  LR_RESOLVE(retro_load_game);
  LR_RESOLVE(retro_load_game_special);
  LR_RESOLVE(retro_unload_game);
  LR_RESOLVE(retro_get_region);
  LR_RESOLVE(retro_get_memory_data);
  LR_RESOLVE(retro_get_memory_size);
#undef LR_RESOLVE
  if (!ok) {
    dlclose(handle);
    SetError(error, "not a libretro core (missing " + missing + ")");
    return nullptr;
  }
  return CreateWithInterface(core, path, handle, error);
}

LibretroHost::~LibretroHost() {
  Shutdown();
  LibretroHost* self = this;
  active_.compare_exchange_strong(self, nullptr);
  if (dl_handle_) dlclose(dl_handle_);
}

void LibretroHost::Shutdown() {
  std::lock_guard<std::recursive_mutex> lock(core_mutex_);
  if (!initialized_) return;
  if (game_loaded_) UnloadGame();
  core_.retro_deinit();
  initialized_ = false;
  LibretroHost* self = this;
  active_.compare_exchange_strong(self, nullptr);
}

void LibretroHost::SetSystemDirectory(const std::string& dir) {
  std::lock_guard<std::recursive_mutex> lock(core_mutex_);
  system_dir_ = dir;
}

void LibretroHost::SetSaveDirectory(const std::string& dir) {
  std::lock_guard<std::recursive_mutex> lock(core_mutex_);
  save_dir_ = dir;
}

void LibretroHost::SetCacheDirectory(const std::string& dir) {
  std::lock_guard<std::recursive_mutex> lock(core_mutex_);
  cache_dir_ = dir;
}

void LibretroHost::SetLogCallback(LogCallback cb) {
  std::lock_guard<std::recursive_mutex> lock(core_mutex_);
  log_cb_ = std::move(cb);
}

void LibretroHost::SetRumbleCallback(RumbleCallback cb) {
  std::lock_guard<std::recursive_mutex> lock(core_mutex_);
  rumble_cb_ = std::move(cb);
}

void LibretroHost::RegisterOption(CoreOption option) {
  auto override_it = overrides_.find(option.key);
  const std::string* previous = nullptr;
  auto existing = options_.find(option.key);
  if (existing != options_.end()) previous = &existing->second.value;
  std::string wanted = override_it != overrides_.end()
                           ? override_it->second
                           : (previous ? *previous : option.default_value);
  if (std::find(option.values.begin(), option.values.end(), wanted) ==
      option.values.end()) {
    wanted = option.default_value;
  }
  option.value = wanted;
  if (existing == options_.end()) option_order_.push_back(option.key);
  options_[option.key] = std::move(option);
}

void LibretroHost::SetVariable(const std::string& key,
                               const std::string& value) {
  std::lock_guard<std::recursive_mutex> lock(core_mutex_);
  overrides_[key] = value;
  auto it = options_.find(key);
  if (it != options_.end()) {
    CoreOption& option = it->second;
    const bool valid = option.values.empty() ||
                       std::find(option.values.begin(), option.values.end(),
                                 value) != option.values.end();
    if (valid && option.value != value) {
      option.value = value;
      options_updated_ = true;
    }
  }
}

std::string LibretroHost::GetVariable(const std::string& key) const {
  std::lock_guard<std::recursive_mutex> lock(core_mutex_);
  auto it = options_.find(key);
  if (it != options_.end()) return it->second.value;
  auto ov = overrides_.find(key);
  return ov != overrides_.end() ? ov->second : "";
}

std::vector<CoreOption> LibretroHost::CoreOptions() const {
  std::lock_guard<std::recursive_mutex> lock(core_mutex_);
  std::vector<CoreOption> out;
  out.reserve(option_order_.size());
  for (const std::string& key : option_order_) out.push_back(options_.at(key));
  return out;
}

bool LibretroHost::LoadGame(const std::string& path, std::string* error) {
  if (!initialized_) {
    SetError(error, "core has been shut down");
    return false;
  }
  if (need_fullpath_) {
    std::lock_guard<std::recursive_mutex> lock(core_mutex_);
    if (game_loaded_) UnloadGame();
    game_path_ = path;
    rom_data_.clear();
    retro_game_info info{game_path_.c_str(), nullptr, 0, nullptr};
    if (!core_.retro_load_game(&info)) {
      SetError(error, "core rejected " + path);
      return false;
    }
    return FinishLoad(error);
  }
  std::ifstream file(path, std::ios::binary | std::ios::ate);
  if (!file) {
    SetError(error, "cannot open " + path);
    return false;
  }
  const std::streamoff size = file.tellg();
  if (size <= 0 || static_cast<uint64_t>(size) > kMaxRomBytes) {
    SetError(error, "invalid ROM size");
    return false;
  }
  std::vector<uint8_t> bytes(static_cast<size_t>(size));
  file.seekg(0);
  if (!file.read(reinterpret_cast<char*>(bytes.data()), size)) {
    SetError(error, "cannot read " + path);
    return false;
  }
  return LoadGameFromMemory(path, bytes.data(), bytes.size(), error);
}

bool LibretroHost::LoadGameFromMemory(const std::string& virtual_path,
                                      const uint8_t* data, size_t size,
                                      std::string* error) {
  if (data == nullptr || size == 0 || size > kMaxRomBytes) {
    SetError(error, "invalid ROM data");
    return false;
  }
  std::lock_guard<std::recursive_mutex> lock(core_mutex_);
  if (!initialized_) {
    SetError(error, "core has been shut down");
    return false;
  }
  if (game_loaded_) UnloadGame();
  game_path_ = virtual_path;
  rom_data_.assign(data, data + size);
  const void* info_data = rom_data_.data();
  size_t info_size = rom_data_.size();
  if (need_fullpath_) {
    std::ifstream probe(virtual_path, std::ios::binary);
    if (!probe.good()) {
      const size_t slash = virtual_path.find_last_of('/');
      const std::string base = slash == std::string::npos
                                   ? virtual_path
                                   : virtual_path.substr(slash + 1);
      const size_t dot = base.find_last_of('.');
      const std::string ext = dot == std::string::npos ? "" : base.substr(dot);
      // Never fall back to /tmp (absent on Android): use the injected cache dir.
      std::string dir = !cache_dir_.empty() ? cache_dir_ : save_dir_;
      if (dir.empty()) {
        SetError(error, "no cache directory for a full-path core");
        rom_data_.clear();
        return false;
      }
      while (dir.size() > 1 && dir.back() == '/') dir.pop_back();
      std::string tmpl = dir + "/delta_rom_XXXXXX";
      std::vector<char> buf(tmpl.begin(), tmpl.end());
      buf.push_back('\0');
      const int fd = mkstemp(buf.data());
      if (fd >= 0) {
        close(fd);
        const std::string dst = std::string(buf.data()) + ext;
        std::rename(buf.data(), dst.c_str());
        std::ofstream out(dst, std::ios::binary | std::ios::trunc);
        if (out && out.write(reinterpret_cast<const char*>(rom_data_.data()),
                             static_cast<std::streamsize>(rom_data_.size()))) {
          out.close();
          temp_rom_path_ = dst;
          game_path_ = temp_rom_path_;
          info_data = nullptr;
          info_size = 0;
        } else {
          std::remove(dst.c_str());
        }
      }
    }
  }
  retro_game_info info{game_path_.c_str(), info_data, info_size, nullptr};
  if (!core_.retro_load_game(&info)) {
    rom_data_.clear();
    if (!temp_rom_path_.empty()) {
      std::remove(temp_rom_path_.c_str());
      temp_rom_path_.clear();
    }
    SetError(error, "core rejected the game");
    return false;
  }
  return FinishLoad(error);
}

bool LibretroHost::FinishLoad(std::string* error) {
  (void)error;
  retro_system_av_info av{};
  core_.retro_get_system_av_info(&av);
  geometry_ = {av.geometry.base_width, av.geometry.base_height,
               av.geometry.max_width, av.geometry.max_height,
               av.geometry.aspect_ratio};
  if (av.timing.fps > 0) fps_ = av.timing.fps;
  if (av.timing.sample_rate > 0) sample_rate_ = av.timing.sample_rate;
  resampler_.SetInputRate(sample_rate_);
  for (unsigned port = 0; port < kMaxPorts; ++port) {
    core_.retro_set_controller_port_device(port, RETRO_DEVICE_JOYPAD);
  }
  game_loaded_ = true;
  shutdown_requested_ = false;
  frames_run_ = 0;
  audio_ring_.RequestFlush();
  Log(RETRO_LOG_INFO, "loaded " + game_path_ + " with " + library_name_);
  return true;
}

void LibretroHost::UnloadGame() {
  std::lock_guard<std::recursive_mutex> lock(core_mutex_);
  if (!game_loaded_) return;
  core_.retro_unload_game();
  game_loaded_ = false;
  rom_data_.clear();
  if (!temp_rom_path_.empty()) {
    std::remove(temp_rom_path_.c_str());
    temp_rom_path_.clear();
  }
  for (unsigned p = 0; p < kMaxPorts; ++p) {
    latched_joypad_[p].store(0, std::memory_order_relaxed);
  }
  audio_ring_.RequestFlush();
}

void LibretroHost::Reset() {
  std::lock_guard<std::recursive_mutex> lock(core_mutex_);
  for (unsigned p = 0; p < kMaxPorts; ++p) {
    latched_joypad_[p].store(0, std::memory_order_relaxed);
  }
  if (initialized_ && game_loaded_) core_.retro_reset();
}

void LibretroHost::RunFrames(int count, bool fast_forward) {
  std::lock_guard<std::recursive_mutex> lock(core_mutex_);
  if (!game_loaded_ || count <= 0) return;
  if (fast_forward != fast_forward_) {
    audio_ring_.RequestFlush();
    fast_forward_ = fast_forward;
  }
  for (int k = 0; k < count; ++k) {
    video_enabled_ = (k == count - 1);
    audio_enabled_ = !fast_forward;
    // Snapshot the sub-frame latch so taps that were pressed and released
    // before this frame are still visible to the core for exactly one frame.
    // polled_joypad_ is pre-populated in case a core reads input state
    // before (or without) calling input_poll.
    std::array<uint16_t, kMaxPorts> consumed_latch{};
    for (unsigned p = 0; p < kMaxPorts; ++p) {
      consumed_latch[p] = latched_joypad_[p].load(std::memory_order_relaxed);
      polled_joypad_[p] =
          joypad_[p].load(std::memory_order_relaxed) | consumed_latch[p];
    }
    core_.retro_run();
    for (unsigned p = 0; p < kMaxPorts; ++p) {
      if (consumed_latch[p] != 0) {
        latched_joypad_[p].fetch_and(static_cast<uint16_t>(~consumed_latch[p]),
                                     std::memory_order_relaxed);
      }
    }
    frames_run_.fetch_add(1);
  }
  video_enabled_ = true;
  audio_enabled_ = true;
}

void LibretroHost::SetJoypadMask(unsigned port, uint16_t mask) {
  if (port < kMaxPorts) {
    uint16_t prev = joypad_[port].exchange(mask, std::memory_order_relaxed);
    uint16_t newly_pressed = static_cast<uint16_t>(mask & ~prev);
    if (newly_pressed != 0) {
      latched_joypad_[port].fetch_or(newly_pressed, std::memory_order_relaxed);
    }
  }
}

uint16_t LibretroHost::joypad_mask(unsigned port) const {
  return port < kMaxPorts ? joypad_[port].load(std::memory_order_relaxed) : 0;
}

void LibretroHost::SetAnalog(unsigned port, unsigned index, int16_t x,
                             int16_t y) {
  if (port >= kMaxPorts || index > 1) return;
  analog_[port][index * 2].store(x, std::memory_order_relaxed);
  analog_[port][index * 2 + 1].store(y, std::memory_order_relaxed);
}

void LibretroHost::SetPointerState(bool pressed, int16_t x, int16_t y) {
  pointer_pressed_.store(pressed, std::memory_order_relaxed);
  pointer_x_.store(x, std::memory_order_relaxed);
  pointer_y_.store(y, std::memory_order_relaxed);
}

void LibretroHost::ClearCheats() {
  std::lock_guard<std::recursive_mutex> lock(core_mutex_);
  if (initialized_) core_.retro_cheat_reset();
}

void LibretroHost::SetCheat(unsigned index, bool enabled,
                            const std::string& code) {
  std::lock_guard<std::recursive_mutex> lock(core_mutex_);
  if (initialized_) core_.retro_cheat_set(index, enabled, code.c_str());
}

void LibretroHost::OnInputPoll() {
  for (unsigned port = 0; port < kMaxPorts; ++port) {
    polled_joypad_[port] =
        joypad_[port].load(std::memory_order_relaxed) |
        latched_joypad_[port].load(std::memory_order_relaxed);
    for (unsigned a = 0; a < 4; ++a) {
      polled_analog_[port][a] =
          analog_[port][a].load(std::memory_order_relaxed);
    }
  }
  polled_pointer_pressed_ = pointer_pressed_.load(std::memory_order_relaxed);
  polled_pointer_x_ = pointer_x_.load(std::memory_order_relaxed);
  polled_pointer_y_ = pointer_y_.load(std::memory_order_relaxed);
}

int16_t LibretroHost::OnInputState(unsigned port, unsigned device,
                                   unsigned index, unsigned id) const {
  if (port >= kMaxPorts) return 0;
  switch (device & RETRO_DEVICE_MASK) {
    case RETRO_DEVICE_JOYPAD:
      if (id == RETRO_DEVICE_ID_JOYPAD_MASK) {
        return static_cast<int16_t>(polled_joypad_[port]);
      }
      return id < 16 ? static_cast<int16_t>((polled_joypad_[port] >> id) & 1)
                     : 0;
    case RETRO_DEVICE_ANALOG:
      if (index <= RETRO_DEVICE_INDEX_ANALOG_RIGHT && id <= 1) {
        return polled_analog_[port][index * 2 + id];
      }
      return 0;
    case RETRO_DEVICE_POINTER:
      if (index != 0) return 0;
      switch (id) {
        case RETRO_DEVICE_ID_POINTER_X:
          return polled_pointer_x_;
        case RETRO_DEVICE_ID_POINTER_Y:
          return polled_pointer_y_;
        case RETRO_DEVICE_ID_POINTER_PRESSED:
          return polled_pointer_pressed_ ? 1 : 0;
        default:
          return 0;
      }
    default:
      return 0;
  }
}

void LibretroHost::OnVideo(const void* data, unsigned width, unsigned height,
                           size_t pitch) {
  if (data == nullptr || !video_enabled_) return;
  if (width == 0 || height == 0 || width > 4096 || height > 4096) return;
  const size_t bpp = BytesPerPixel(pixel_format_);
  if (bpp == 0 || pitch < static_cast<size_t>(width) * bpp) return;
  std::lock_guard<std::mutex> lock(frame_mutex_);
  frame_rgba_.resize(static_cast<size_t>(width) * height * 4);
  if (!ConvertToRgba8888(pixel_format_, data, width, height, pitch,
                         frame_rgba_.data())) {
    return;
  }
  frame_width_ = width;
  frame_height_ = height;
  video_serial_.fetch_add(1);
}

bool LibretroHost::CopyFrameRgba(std::vector<uint8_t>* out, unsigned* width,
                                 unsigned* height, uint64_t* serial) const {
  std::lock_guard<std::mutex> lock(frame_mutex_);
  if (frame_width_ == 0) return false;
  *out = frame_rgba_;
  if (width) *width = frame_width_;
  if (height) *height = frame_height_;
  if (serial) *serial = video_serial_.load();
  return true;
}

bool LibretroHost::CopyFrameRgbaInto(uint8_t* out, size_t capacity,
                                     unsigned* width, unsigned* height,
                                     uint64_t* serial) const {
  std::lock_guard<std::mutex> lock(frame_mutex_);
  if (frame_width_ == 0 || out == nullptr || capacity < frame_rgba_.size()) {
    return false;
  }
  std::memcpy(out, frame_rgba_.data(), frame_rgba_.size());
  if (width) *width = frame_width_;
  if (height) *height = frame_height_;
  if (serial) *serial = video_serial_.load();
  return true;
}

VideoGeometry LibretroHost::geometry() const {
  std::lock_guard<std::recursive_mutex> lock(core_mutex_);
  return geometry_;
}

size_t LibretroHost::OnAudio(const int16_t* data, size_t frames) {
  if (audio_enabled_ && data != nullptr) audio_ring_.Write(data, frames);
  return frames;
}

bool LibretroHost::SerializeState(std::vector<uint8_t>* out,
                                  std::string* error) {
  std::lock_guard<std::recursive_mutex> lock(core_mutex_);
  if (!game_loaded_) {
    SetError(error, "no game loaded");
    return false;
  }
  const size_t size = core_.retro_serialize_size();
  if (size == 0) {
    SetError(error, "core does not support save states");
    return false;
  }
  out->assign(size, 0);
  if (!core_.retro_serialize(out->data(), out->size())) {
    out->clear();
    SetError(error, "retro_serialize failed");
    return false;
  }
  return true;
}

bool LibretroHost::UnserializeState(const uint8_t* data, size_t size,
                                    std::string* error) {
  std::lock_guard<std::recursive_mutex> lock(core_mutex_);
  if (!game_loaded_) {
    SetError(error, "no game loaded");
    return false;
  }
  if (data == nullptr || size == 0) {
    SetError(error, "empty save state");
    return false;
  }
  if (!core_.retro_unserialize(data, size)) {
    SetError(error, "core rejected the save state");
    return false;
  }
  audio_ring_.RequestFlush();
  return true;
}

size_t LibretroHost::save_ram_size() const {
  std::lock_guard<std::recursive_mutex> lock(core_mutex_);
  if (!game_loaded_) return 0;
  return core_.retro_get_memory_data(RETRO_MEMORY_SAVE_RAM)
             ? core_.retro_get_memory_size(RETRO_MEMORY_SAVE_RAM)
             : 0;
}

std::vector<uint8_t> LibretroHost::SaveRam() const {
  std::lock_guard<std::recursive_mutex> lock(core_mutex_);
  if (!game_loaded_) return {};
  const uint8_t* data = static_cast<const uint8_t*>(
      core_.retro_get_memory_data(RETRO_MEMORY_SAVE_RAM));
  const size_t size = core_.retro_get_memory_size(RETRO_MEMORY_SAVE_RAM);
  if (data == nullptr || size == 0) return {};
  return std::vector<uint8_t>(data, data + size);
}

bool LibretroHost::LoadSaveRam(const uint8_t* data, size_t size,
                               std::string* error) {
  std::lock_guard<std::recursive_mutex> lock(core_mutex_);
  if (!game_loaded_) {
    SetError(error, "no game loaded");
    return false;
  }
  uint8_t* region =
      static_cast<uint8_t*>(core_.retro_get_memory_data(RETRO_MEMORY_SAVE_RAM));
  const size_t region_size = core_.retro_get_memory_size(RETRO_MEMORY_SAVE_RAM);
  if (region == nullptr || region_size == 0) {
    SetError(error, "this game has no battery save memory");
    return false;
  }
  if (data == nullptr || size == 0 || size > region_size ||
      size > kMaxSaveRamBytes) {
    SetError(error, "save file size " + std::to_string(size) +
                        " does not fit save memory of " +
                        std::to_string(region_size) + " bytes");
    return false;
  }
  std::memcpy(region, data, size);
  if (size < region_size) std::memset(region + size, 0, region_size - size);
  return true;
}

bool LibretroHost::Environment(unsigned cmd, void* data) {
  switch (cmd) {
    case RETRO_ENVIRONMENT_SET_ROTATION:
      if (!data) return false;
      rotation_ = *static_cast<const unsigned*>(data) % 4;
      return true;
    case RETRO_ENVIRONMENT_GET_OVERSCAN:
      if (data) *static_cast<bool*>(data) = false;
      return true;
    case RETRO_ENVIRONMENT_GET_CAN_DUPE:
      if (data) *static_cast<bool*>(data) = true;
      return true;
    case RETRO_ENVIRONMENT_SET_MESSAGE: {
      const auto* msg = static_cast<const retro_message*>(data);
      if (msg && msg->msg) Log(RETRO_LOG_INFO, msg->msg);
      return true;
    }
    case RETRO_ENVIRONMENT_SHUTDOWN:
      shutdown_requested_ = true;
      return true;
    case RETRO_ENVIRONMENT_SET_PERFORMANCE_LEVEL:
    case RETRO_ENVIRONMENT_SET_INPUT_DESCRIPTORS:
    case RETRO_ENVIRONMENT_SET_CONTROLLER_INFO:
    case RETRO_ENVIRONMENT_SET_MEMORY_MAPS:
    case RETRO_ENVIRONMENT_SET_SUBSYSTEM_INFO:
    case RETRO_ENVIRONMENT_SET_SUPPORT_ACHIEVEMENTS:
    case RETRO_ENVIRONMENT_SET_SERIALIZATION_QUIRKS:
      return true;
    case RETRO_ENVIRONMENT_GET_SYSTEM_DIRECTORY:
    case RETRO_ENVIRONMENT_GET_CORE_ASSETS_DIRECTORY:
      if (!data) return false;
      if (!system_dir_.empty()) {
        *static_cast<const char**>(data) = system_dir_.c_str();
      } else if (!cache_dir_.empty()) {
        *static_cast<const char**>(data) = cache_dir_.c_str();
      } else {
        return false;
      }
      return true;
    case RETRO_ENVIRONMENT_GET_SAVE_DIRECTORY:
      if (!data) return false;
      if (!save_dir_.empty()) {
        *static_cast<const char**>(data) = save_dir_.c_str();
      } else if (!cache_dir_.empty()) {
        *static_cast<const char**>(data) = cache_dir_.c_str();
      } else {
        return false;
      }
      return true;
    case RETRO_ENVIRONMENT_GET_LIBRETRO_PATH:
      if (!data) return false;
      *static_cast<const char**>(data) = core_path_.c_str();
      return true;
    case RETRO_ENVIRONMENT_SET_PIXEL_FORMAT: {
      if (!data) return false;
      const auto format = *static_cast<const retro_pixel_format*>(data);
      if (BytesPerPixel(format) == 0) return false;
      pixel_format_ = format;
      return true;
    }
    case RETRO_ENVIRONMENT_SET_HW_RENDER:
      return false;
    case RETRO_ENVIRONMENT_SET_VARIABLES: {
      const auto* vars = static_cast<const retro_variable*>(data);
      for (; vars && vars->key; ++vars) {
        RegisterOption(ParseLegacyVariable(vars->key, vars->value));
      }
      return true;
    }
    case RETRO_ENVIRONMENT_SET_CORE_OPTIONS:
    case RETRO_ENVIRONMENT_SET_CORE_OPTIONS_INTL: {
      const retro_core_option_definition* defs = nullptr;
      if (cmd == RETRO_ENVIRONMENT_SET_CORE_OPTIONS) {
        defs = static_cast<const retro_core_option_definition*>(data);
      } else if (data) {
        defs = static_cast<const retro_core_options_intl_payload*>(data)->us;
      }
      for (; defs && defs->key; ++defs) {
        CoreOption option;
        option.key = defs->key;
        option.description = defs->desc ? defs->desc : defs->key;
        option.info = defs->info ? defs->info : "";
        for (size_t v = 0; v < RETRO_NUM_CORE_OPTION_VALUES_MAX &&
                           defs->values[v].value != nullptr;
             ++v) {
          option.values.push_back(defs->values[v].value);
          option.labels.push_back(defs->values[v].label
                                      ? defs->values[v].label
                                      : defs->values[v].value);
        }
        option.default_value =
            defs->default_value
                ? defs->default_value
                : (option.values.empty() ? "" : option.values.front());
        RegisterOption(std::move(option));
      }
      return true;
    }
    case RETRO_ENVIRONMENT_SET_CORE_OPTIONS_DISPLAY: {
      const auto* display =
          static_cast<const retro_core_option_display_payload*>(data);
      if (display && display->key) {
        auto it = options_.find(display->key);
        if (it != options_.end()) it->second.visible = display->visible;
      }
      return true;
    }
    case RETRO_ENVIRONMENT_GET_CORE_OPTIONS_VERSION:
      if (data) *static_cast<unsigned*>(data) = 1;
      return true;
    case RETRO_ENVIRONMENT_GET_VARIABLE: {
      auto* var = static_cast<retro_variable*>(data);
      if (!var || !var->key) return false;
      auto it = options_.find(var->key);
      if (it == options_.end()) {
        auto ov = overrides_.find(var->key);
        if (ov != overrides_.end()) {
          var->value = ov->second.c_str();
          return true;
        }
        var->value = nullptr;
        return false;
      }
      var->value = it->second.value.c_str();
      return true;
    }
    case RETRO_ENVIRONMENT_GET_VARIABLE_UPDATE:
      if (!data) return false;
      *static_cast<bool*>(data) = options_updated_;
      options_updated_ = false;
      return true;
    case RETRO_ENVIRONMENT_SET_SUPPORT_NO_GAME:
      if (data) support_no_game_ = *static_cast<const bool*>(data);
      return true;
    case RETRO_ENVIRONMENT_GET_RUMBLE_INTERFACE: {
      if (!data) return false;
      static_cast<retro_rumble_interface*>(data)->set_rumble_state =
          &RumbleThunk;
      return true;
    }
    case RETRO_ENVIRONMENT_GET_INPUT_DEVICE_CAPABILITIES:
      if (data) {
        *static_cast<uint64_t*>(data) = (1u << RETRO_DEVICE_JOYPAD) |
                                        (1u << RETRO_DEVICE_ANALOG) |
                                        (1u << RETRO_DEVICE_POINTER);
      }
      return true;
    case RETRO_ENVIRONMENT_GET_LOG_INTERFACE:
      if (!data) return false;
      static_cast<retro_log_callback*>(data)->log = &LogThunk;
      return true;
    case RETRO_ENVIRONMENT_SET_SYSTEM_AV_INFO: {
      const auto* av = static_cast<const retro_system_av_info*>(data);
      if (!av) return false;
      geometry_ = {av->geometry.base_width, av->geometry.base_height,
                   av->geometry.max_width, av->geometry.max_height,
                   av->geometry.aspect_ratio};
      if (av->timing.fps > 0) fps_ = av->timing.fps;
      if (av->timing.sample_rate > 0) {
        sample_rate_ = av->timing.sample_rate;
        resampler_.SetInputRate(sample_rate_);
      }
      return true;
    }
    case RETRO_ENVIRONMENT_SET_GEOMETRY: {
      const auto* g = static_cast<const retro_game_geometry*>(data);
      if (!g) return false;
      geometry_.base_width = g->base_width;
      geometry_.base_height = g->base_height;
      geometry_.aspect_ratio = g->aspect_ratio;
      return true;
    }
    case RETRO_ENVIRONMENT_GET_USERNAME:
      if (data) *static_cast<const char**>(data) = "Delta";
      return true;
    case RETRO_ENVIRONMENT_GET_LANGUAGE:
      if (data) *static_cast<unsigned*>(data) = 0;
      return true;
    case RETRO_ENVIRONMENT_GET_AUDIO_VIDEO_ENABLE:
      if (data) {
        *static_cast<int*>(data) =
            (video_enabled_ ? 1 : 0) | (audio_enabled_ ? 2 : 0);
      }
      return true;
    case RETRO_ENVIRONMENT_GET_FASTFORWARDING:
      if (data) *static_cast<bool*>(data) = fast_forward_;
      return true;
    case RETRO_ENVIRONMENT_GET_TARGET_REFRESH_RATE:
      if (data) *static_cast<float*>(data) = 60.0f;
      return true;
    case RETRO_ENVIRONMENT_GET_INPUT_BITMASKS:
      return true;
    case RETRO_ENVIRONMENT_GET_INPUT_MAX_USERS:
      if (data) *static_cast<unsigned*>(data) = kMaxPorts;
      return true;
    case RETRO_ENVIRONMENT_GET_MESSAGE_INTERFACE_VERSION:
      if (data) *static_cast<unsigned*>(data) = 0;
      return true;
    default:
      return false;
  }
}

void LibretroHost::Log(int level, const std::string& message) const {
  if (log_cb_) {
    log_cb_(level, message);
  }
}

bool LibretroHost::EnvironmentThunk(unsigned cmd, void* data) {
  LibretroHost* host = active_.load();
  return host ? host->Environment(cmd, data) : false;
}

void LibretroHost::VideoThunk(const void* data, unsigned w, unsigned h,
                              size_t p) {
  if (LibretroHost* host = active_.load()) host->OnVideo(data, w, h, p);
}

void LibretroHost::AudioSampleThunk(int16_t left, int16_t right) {
  const int16_t pair[2] = {left, right};
  if (LibretroHost* host = active_.load()) host->OnAudio(pair, 1);
}

size_t LibretroHost::AudioBatchThunk(const int16_t* data, size_t frames) {
  LibretroHost* host = active_.load();
  return host ? host->OnAudio(data, frames) : frames;
}

void LibretroHost::InputPollThunk() {
  if (LibretroHost* host = active_.load()) host->OnInputPoll();
}

int16_t LibretroHost::InputStateThunk(unsigned port, unsigned device,
                                      unsigned index, unsigned id) {
  LibretroHost* host = active_.load();
  return host ? host->OnInputState(port, device, index, id) : 0;
}

void LibretroHost::LogThunk(enum retro_log_level level, const char* fmt, ...) {
  LibretroHost* host = active_.load();
  if (!host || !host->log_cb_ || !fmt) return;
  char buf[512];
  va_list ap;
  va_start(ap, fmt);
  vsnprintf(buf, sizeof(buf), fmt, ap);
  va_end(ap);
  std::string msg(buf);
  while (!msg.empty() && (msg.back() == '\n' || msg.back() == '\r')) {
    msg.pop_back();
  }
  host->Log(static_cast<int>(level), msg);
}

bool LibretroHost::RumbleThunk(unsigned port, enum retro_rumble_effect effect,
                               uint16_t strength) {
  LibretroHost* host = active_.load();
  if (!host) return false;
  if (host->rumble_cb_) {
    host->rumble_cb_(port, static_cast<int>(effect), strength);
  }
  return true;
}

}  // namespace delta
