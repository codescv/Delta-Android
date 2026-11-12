// Copyright 2026 Google LLC

#ifndef DELTA_CORE_EMU_UTIL_H_
#define DELTA_CORE_EMU_UTIL_H_

#include <cstddef>
#include <cstdint>

namespace delta {

uint32_t Crc32Update(uint32_t crc, const void* data, size_t size);
inline uint32_t Crc32(const void* data, size_t size) {
  return Crc32Update(0, data, size);
}

class FramePacer {
 public:
  explicit FramePacer(double core_fps = 60.0);

  void SetCoreFps(double fps);
  double core_fps() const { return core_fps_; }

  void SetSpeedMultiplier(int multiplier);
  int speed_multiplier() const { return multiplier_; }

  int FramesForElapsed(double elapsed_seconds);

  int max_frames_per_tick() const { return 4 * multiplier_; }
  void Reset() { accumulator_ = 0.0; }

 private:
  double core_fps_;
  int multiplier_ = 1;
  double accumulator_ = 0.0;
};

}  // namespace delta

#endif  // DELTA_CORE_EMU_UTIL_H_
