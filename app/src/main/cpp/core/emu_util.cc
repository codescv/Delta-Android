// Copyright 2026 Google LLC

#include "core/emu_util.h"

#include <algorithm>
#include <array>

namespace lemuroid {
namespace {

constexpr std::array<uint32_t, 256> MakeCrcTable() {
  std::array<uint32_t, 256> table{};
  for (uint32_t n = 0; n < 256; ++n) {
    uint32_t c = n;
    for (int k = 0; k < 8; ++k) c = (c & 1) ? 0xEDB88320u ^ (c >> 1) : c >> 1;
    table[n] = c;
  }
  return table;
}

constexpr std::array<uint32_t, 256> kCrcTable = MakeCrcTable();

}  // namespace

uint32_t Crc32Update(uint32_t crc, const void* data, size_t size) {
  const uint8_t* p = static_cast<const uint8_t*>(data);
  uint32_t c = crc ^ 0xFFFFFFFFu;
  for (size_t k = 0; k < size; ++k) c = kCrcTable[(c ^ p[k]) & 0xFF] ^ (c >> 8);
  return c ^ 0xFFFFFFFFu;
}

FramePacer::FramePacer(double core_fps) { SetCoreFps(core_fps); }

void FramePacer::SetCoreFps(double fps) {
  core_fps_ = (fps > 1.0 && fps < 1000.0) ? fps : 60.0;
}

void FramePacer::SetSpeedMultiplier(int multiplier) {
  multiplier_ = std::clamp(multiplier, 1, 8);
}

int FramePacer::FramesForElapsed(double elapsed_seconds) {
  if (elapsed_seconds < 0) elapsed_seconds = 0;
  elapsed_seconds = std::min(elapsed_seconds, 0.25);
  accumulator_ += elapsed_seconds * core_fps_ * multiplier_;
  constexpr double kJitter = 0.02;
  int frames = static_cast<int>(accumulator_ + kJitter);
  frames = std::clamp(frames, 0, max_frames_per_tick());
  accumulator_ -= frames;
  accumulator_ = std::clamp(accumulator_, -kJitter,
                            static_cast<double>(max_frames_per_tick()));
  return frames;
}

}  // namespace lemuroid
