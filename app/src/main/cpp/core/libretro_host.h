// Copyright 2026 Google LLC

#ifndef DELTA_CORE_LIBRETRO_HOST_H_
#define DELTA_CORE_LIBRETRO_HOST_H_

#include <array>
#include <atomic>
#include <cstddef>
#include <cstdint>
#include <functional>
#include <map>
#include <memory>
#include <mutex>
#include <string>
#include <vector>

#include "core/audio_resampler.h"
#include "core/audio_ring_buffer.h"
#include "core/lr_core_interface.h"
#include "core/lr_libretro.h"

namespace delta {

struct BuiltinCoreInfo {
  const char* id;
  const char* display_name;
  const char* system_id;
  const lr_core_interface* (*get_interface)();
};

const std::vector<BuiltinCoreInfo>& BuiltinCores();

struct CoreOption {
  std::string key;
  std::string description;
  std::string info;
  std::vector<std::string> values;
  std::vector<std::string> labels;
  std::string default_value;
  std::string value;
  bool visible = true;
};

// Directories handed to the core. They must be known before retro_init(), since
// melonDS / parallel-n64 / Genesis Plus GX query them during initialization.
struct HostDirs {
  std::string system_dir;
  std::string save_dir;
  std::string cache_dir;
};

struct VideoGeometry {
  unsigned base_width = 0;
  unsigned base_height = 0;
  unsigned max_width = 0;
  unsigned max_height = 0;
  float aspect_ratio = 0.0f;
};

class LibretroHost {
 public:
  static constexpr unsigned kMaxPorts = 4;
  static constexpr size_t kMaxRomBytes = 512u * 1024u * 1024u;
  static constexpr size_t kMaxSaveRamBytes = 16u * 1024u * 1024u;

  using LogCallback =
      std::function<void(int level, const std::string& message)>;
  using RumbleCallback =
      std::function<void(unsigned port, int effect, uint16_t strength)>;

  static std::unique_ptr<LibretroHost> CreateWithInterface(
      const lr_core_interface& core, const std::string& core_path,
      void* dl_handle, std::string* error, const HostDirs& dirs = HostDirs());
  static std::unique_ptr<LibretroHost> CreateBuiltin(const std::string& core_id,
                                                     std::string* error);
  static std::unique_ptr<LibretroHost> CreateBuiltin(const std::string& core_id,
                                                     const HostDirs& dirs,
                                                     std::string* error);
  static std::unique_ptr<LibretroHost> CreateFromDynamicLibrary(
      const std::string& path, std::string* error);
  static std::unique_ptr<LibretroHost> CreateFromDlHandle(
      void* handle, const std::string& path, std::string* error);

  ~LibretroHost();
  LibretroHost(const LibretroHost&) = delete;

  void Shutdown();
  bool is_shut_down() const { return !initialized_; }
  LibretroHost& operator=(const LibretroHost&) = delete;

  void SetSystemDirectory(const std::string& dir);
  void SetSaveDirectory(const std::string& dir);
  void SetCacheDirectory(const std::string& dir);
  const std::string& system_directory() const { return system_dir_; }
  const std::string& cache_directory() const { return cache_dir_; }
  void SetLogCallback(LogCallback cb);
  void SetRumbleCallback(RumbleCallback cb);

  void SetVariable(const std::string& key, const std::string& value);
  std::string GetVariable(const std::string& key) const;
  std::vector<CoreOption> CoreOptions() const;

  const std::string& library_name() const { return library_name_; }
  const std::string& library_version() const { return library_version_; }
  const std::string& valid_extensions() const { return valid_extensions_; }
  bool need_fullpath() const { return need_fullpath_; }
  unsigned api_version() const { return api_version_; }

  bool LoadGame(const std::string& path, std::string* error);
  bool LoadGameFromMemory(const std::string& virtual_path, const uint8_t* data,
                          size_t size, std::string* error);
  void UnloadGame();
  bool game_loaded() const { return game_loaded_; }
  void Reset();

  void RunFrames(int count, bool fast_forward);
  void RunFrame() { RunFrames(1, false); }
  uint64_t frames_run() const { return frames_run_.load(); }
  bool shutdown_requested() const { return shutdown_requested_.load(); }

  void SetJoypadMask(unsigned port, uint16_t mask);
  uint16_t joypad_mask(unsigned port) const;
  void SetAnalog(unsigned port, unsigned index, int16_t x, int16_t y);
  void SetPointerState(bool pressed, int16_t x, int16_t y);

  // Cheats.
  void ClearCheats();
  void SetCheat(unsigned index, bool enabled, const std::string& code);

  bool CopyFrameRgba(std::vector<uint8_t>* out, unsigned* width,
                     unsigned* height, uint64_t* serial) const;
  bool CopyFrameRgbaInto(uint8_t* out, size_t capacity, unsigned* width,
                         unsigned* height, uint64_t* serial) const;
  uint64_t video_serial() const { return video_serial_.load(); }
  retro_pixel_format pixel_format() const { return pixel_format_; }
  VideoGeometry geometry() const;
  double fps() const { return fps_; }
  double sample_rate() const { return sample_rate_; }
  unsigned rotation() const { return rotation_; }

  AudioRingBuffer* audio_ring() { return &audio_ring_; }
  AudioResampler* audio_resampler() { return &resampler_; }

  // Answers a libretro environment query exactly as a core would see it
  // (used by tests to verify directory injection).
  bool QueryEnvironment(unsigned cmd, void* data) { return Environment(cmd, data); }

  bool SerializeState(std::vector<uint8_t>* out, std::string* error);
  bool UnserializeState(const uint8_t* data, size_t size, std::string* error);
  size_t save_ram_size() const;
  std::vector<uint8_t> SaveRam() const;
  bool LoadSaveRam(const uint8_t* data, size_t size, std::string* error);

 private:
  LibretroHost(const lr_core_interface& core, std::string core_path,
               void* dl_handle);

  bool Environment(unsigned cmd, void* data);
  void OnVideo(const void* data, unsigned width, unsigned height, size_t pitch);
  size_t OnAudio(const int16_t* data, size_t frames);
  void OnInputPoll();
  int16_t OnInputState(unsigned port, unsigned device, unsigned index,
                       unsigned id) const;
  void Log(int level, const std::string& message) const;
  bool FinishLoad(std::string* error);
  void RegisterOption(CoreOption option);

  static bool EnvironmentThunk(unsigned cmd, void* data);
  static void VideoThunk(const void* data, unsigned w, unsigned h, size_t p);
  static void AudioSampleThunk(int16_t left, int16_t right);
  static size_t AudioBatchThunk(const int16_t* data, size_t frames);
  static void InputPollThunk();
  static int16_t InputStateThunk(unsigned port, unsigned device, unsigned index,
                                 unsigned id);
  static void LogThunk(enum retro_log_level level, const char* fmt, ...);
  static bool RumbleThunk(unsigned port, enum retro_rumble_effect effect,
                          uint16_t strength);

  static std::atomic<LibretroHost*> active_;

  lr_core_interface core_;
  std::string core_path_;
  void* dl_handle_;
  bool initialized_ = false;

  mutable std::recursive_mutex core_mutex_;

  std::string library_name_;
  std::string library_version_;
  std::string valid_extensions_;
  bool need_fullpath_ = false;
  unsigned api_version_ = 0;

  std::string system_dir_;
  std::string save_dir_;
  std::string cache_dir_;
  std::string game_path_;
  std::string temp_rom_path_;
  std::vector<uint8_t> rom_data_;
  bool game_loaded_ = false;
  bool support_no_game_ = false;

  LogCallback log_cb_;
  RumbleCallback rumble_cb_;

  std::vector<std::string> option_order_;
  std::map<std::string, CoreOption> options_;
  std::map<std::string, std::string> overrides_;
  bool options_updated_ = false;

  retro_pixel_format pixel_format_ = RETRO_PIXEL_FORMAT_0RGB1555;
  VideoGeometry geometry_;
  double fps_ = 60.0;
  double sample_rate_ = 44100.0;
  unsigned rotation_ = 0;
  bool video_enabled_ = true;
  bool audio_enabled_ = true;
  bool fast_forward_ = false;
  std::atomic<bool> shutdown_requested_{false};
  std::atomic<uint64_t> frames_run_{0};

  std::array<std::atomic<uint16_t>, kMaxPorts> joypad_{};
  // Sub-frame latch: bits that were pressed (rising edge) since the last
  // retro_run(). Guarantees a tap that is pressed and released between two
  // emulated frames is still observed for at least one frame.
  std::array<std::atomic<uint16_t>, kMaxPorts> latched_joypad_{};
  std::array<std::array<std::atomic<int16_t>, 4>, kMaxPorts> analog_{};
  std::array<uint16_t, kMaxPorts> polled_joypad_{};
  std::array<std::array<int16_t, 4>, kMaxPorts> polled_analog_{};
  std::atomic<bool> pointer_pressed_{false};
  std::atomic<int16_t> pointer_x_{0};
  std::atomic<int16_t> pointer_y_{0};
  bool polled_pointer_pressed_ = false;
  int16_t polled_pointer_x_ = 0;
  int16_t polled_pointer_y_ = 0;

  mutable std::mutex frame_mutex_;
  std::vector<uint8_t> frame_rgba_;
  unsigned frame_width_ = 0;
  unsigned frame_height_ = 0;
  std::atomic<uint64_t> video_serial_{0};

  AudioRingBuffer audio_ring_;
  AudioResampler resampler_;
};

}  // namespace delta

#endif  // DELTA_CORE_LIBRETRO_HOST_H_
