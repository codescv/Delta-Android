// Copyright 2026 Google LLC

#ifndef EXPERIMENTAL_USERS_CHII_IOS_DELTA_CORE_AUDIO_RESAMPLER_H_
#define EXPERIMENTAL_USERS_CHII_IOS_DELTA_CORE_AUDIO_RESAMPLER_H_

#include <atomic>
#include <cstddef>
#include <cstdint>

#include "core/audio_ring_buffer.h"

namespace lemuroid {

class AudioResampler {
 public:
  static constexpr double kMaxRateDelta = 0.005;

  explicit AudioResampler(AudioRingBuffer* ring);

  void SetInputRate(double hz) {
    input_rate_.store(hz, std::memory_order_relaxed);
  }
  void SetMuted(bool muted) { muted_.store(muted, std::memory_order_relaxed); }
  void SetVolume(float volume) {
    volume_.store(volume, std::memory_order_relaxed);
  }
  bool muted() const { return muted_.load(std::memory_order_relaxed); }

  size_t Render(float* left, float* right, size_t frames, double output_rate);

  static double RateAdjustmentForFill(double fill);

  uint64_t underrun_frames() const {
    return underrun_frames_.load(std::memory_order_relaxed);
  }

 private:
  bool FetchNext();

  AudioRingBuffer* ring_;
  std::atomic<double> input_rate_{44100.0};
  std::atomic<bool> muted_{false};
  std::atomic<float> volume_{1.0f};
  std::atomic<uint64_t> underrun_frames_{0};
  float prev_[2] = {0, 0};
  float next_[2] = {0, 0};
  double frac_ = 0.0;
  float last_[2] = {0, 0};
  float tail_[2] = {0, 0};
  float tail_fade_ = 0.0f;
  bool in_underrun_ = false;
  bool starved_ = true;
};

}  // namespace lemuroid

#endif  // EXPERIMENTAL_USERS_CHII_IOS_DELTA_CORE_AUDIO_RESAMPLER_H_
