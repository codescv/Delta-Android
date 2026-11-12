// Copyright 2026 Google LLC

#include "core/audio_ring_buffer.h"

#include <algorithm>
#include <cstring>

namespace delta {
namespace {

size_t RoundUpPow2(size_t v) {
  size_t p = 2;
  while (p < v) p <<= 1;
  return p;
}

}  // namespace

AudioRingBuffer::AudioRingBuffer(size_t capacity_frames)
    : capacity_(RoundUpPow2(capacity_frames)),
      mask_(capacity_ - 1),
      data_(new int16_t[capacity_ * 2]()) {}

size_t AudioRingBuffer::Write(const int16_t* interleaved, size_t frames) {
  if (interleaved == nullptr || frames == 0) return 0;
  const uint64_t w = write_pos_.load(std::memory_order_relaxed);
  const uint64_t r = read_pos_.load(std::memory_order_acquire);
  const size_t free_frames = capacity_ - static_cast<size_t>(w - r);
  const size_t n = std::min(frames, free_frames);
  const size_t start = static_cast<size_t>(w & mask_);
  const size_t first = std::min(n, capacity_ - start);
  std::memcpy(&data_[start * 2], interleaved, first * 2 * sizeof(int16_t));
  if (n > first) {
    std::memcpy(&data_[0], interleaved + first * 2,
                (n - first) * 2 * sizeof(int16_t));
  }
  write_pos_.store(w + n, std::memory_order_release);
  return n;
}

bool AudioRingBuffer::ApplyPendingFlush() {
  if (!flush_requested_.exchange(false, std::memory_order_acq_rel)) {
    return false;
  }
  read_pos_.store(write_pos_.load(std::memory_order_acquire),
                  std::memory_order_release);
  return true;
}

size_t AudioRingBuffer::Read(int16_t* interleaved_out, size_t frames) {
  ApplyPendingFlush();
  if (interleaved_out == nullptr || frames == 0) return 0;
  const uint64_t r = read_pos_.load(std::memory_order_relaxed);
  const uint64_t w = write_pos_.load(std::memory_order_acquire);
  const size_t n = std::min(frames, static_cast<size_t>(w - r));
  const size_t start = static_cast<size_t>(r & mask_);
  const size_t first = std::min(n, capacity_ - start);
  std::memcpy(interleaved_out, &data_[start * 2], first * 2 * sizeof(int16_t));
  if (n > first) {
    std::memcpy(interleaved_out + first * 2, &data_[0],
                (n - first) * 2 * sizeof(int16_t));
  }
  read_pos_.store(r + n, std::memory_order_release);
  return n;
}

size_t AudioRingBuffer::Discard(size_t frames) {
  ApplyPendingFlush();
  const uint64_t r = read_pos_.load(std::memory_order_relaxed);
  const uint64_t w = write_pos_.load(std::memory_order_acquire);
  const size_t n = std::min(frames, static_cast<size_t>(w - r));
  read_pos_.store(r + n, std::memory_order_release);
  return n;
}

size_t AudioRingBuffer::AvailableToRead() const {
  const uint64_t w = write_pos_.load(std::memory_order_acquire);
  const uint64_t r = read_pos_.load(std::memory_order_acquire);
  return static_cast<size_t>(w - r);
}

size_t AudioRingBuffer::AvailableToWrite() const {
  return capacity_ - AvailableToRead();
}

}  // namespace delta
