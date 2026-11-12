// Copyright 2026 Google LLC

#include "core/audio_resampler.h"

#include <algorithm>

namespace delta {

AudioResampler::AudioResampler(AudioRingBuffer* ring) : ring_(ring) {}

double AudioResampler::RateAdjustmentForFill(double fill) {
  const double delta = (fill - 0.5) * 2.0 * kMaxRateDelta;
  return std::clamp(delta, -kMaxRateDelta, kMaxRateDelta);
}

bool AudioResampler::FetchNext() {
  int16_t frame[2];
  if (ring_->Read(frame, 1) != 1) return false;
  prev_[0] = next_[0];
  prev_[1] = next_[1];
  next_[0] = frame[0] / 32768.0f;
  next_[1] = frame[1] / 32768.0f;
  return true;
}

size_t AudioResampler::Render(float* left, float* right, size_t frames,
                              double output_rate) {
  constexpr float kFadeStep = 1.0f / 64.0f;
  if (ring_->ApplyPendingFlush()) {
    tail_[0] = last_[0];
    tail_[1] = last_[1];
    tail_fade_ = 1.0f;
    prev_[0] = prev_[1] = next_[0] = next_[1] = 0.0f;
    frac_ = 0.0;
    starved_ = true;
  }
  const float volume = volume_.load(std::memory_order_relaxed);
  if (muted_.load(std::memory_order_relaxed)) {
    ring_->Discard(ring_->AvailableToRead());
    std::fill(left, left + frames, 0.0f);
    std::fill(right, right + frames, 0.0f);
    last_[0] = last_[1] = 0.0f;
    tail_fade_ = 0.0f;
    return 0;
  }
  const double in_rate = input_rate_.load(std::memory_order_relaxed);
  const double fill = static_cast<double>(ring_->AvailableToRead()) /
                      static_cast<double>(ring_->capacity_frames());
  const double step = (output_rate > 0 ? in_rate / output_rate : 1.0) *
                      (1.0 + RateAdjustmentForFill(fill));
  size_t real = 0;
  uint64_t underruns = 0;
  for (size_t k = 0; k < frames; ++k) {
    bool ok = true;
    if (starved_) {
      ok = FetchNext();
      if (ok) {
        starved_ = false;
        prev_[0] = next_[0];
        prev_[1] = next_[1];
        frac_ = 0.0;
      }
    } else {
      frac_ += step;
      while (frac_ >= 1.0) {
        frac_ -= 1.0;
        if (!FetchNext()) {
          ok = false;
          starved_ = true;
          frac_ = 0.0;
          break;
        }
      }
    }
    float l = 0.0f;
    float r = 0.0f;
    if (ok) {
      in_underrun_ = false;
      const float t = static_cast<float>(frac_);
      l = prev_[0] + (next_[0] - prev_[0]) * t;
      r = prev_[1] + (next_[1] - prev_[1]) * t;
      ++real;
    } else {
      if (!in_underrun_) {
        in_underrun_ = true;
        tail_[0] = last_[0];
        tail_[1] = last_[1];
        tail_fade_ = 1.0f;
      }
      prev_[0] = next_[0] = 0.0f;
      prev_[1] = next_[1] = 0.0f;
      ++underruns;
    }
    if (tail_fade_ > 0.0f) {
      l += tail_[0] * tail_fade_;
      r += tail_[1] * tail_fade_;
      tail_fade_ = std::max(0.0f, tail_fade_ - kFadeStep);
    }
    l = std::clamp(l, -1.0f, 1.0f);
    r = std::clamp(r, -1.0f, 1.0f);
    last_[0] = l;
    last_[1] = r;
    left[k] = l * volume;
    right[k] = r * volume;
  }
  if (underruns) {
    underrun_frames_.fetch_add(underruns, std::memory_order_relaxed);
  }
  return real;
}

}  // namespace delta
