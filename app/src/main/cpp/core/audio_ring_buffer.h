// Copyright 2026 Google LLC

#ifndef DELTA_CORE_AUDIO_RING_BUFFER_H_
#define DELTA_CORE_AUDIO_RING_BUFFER_H_

#include <atomic>
#include <cstddef>
#include <cstdint>
#include <memory>

namespace delta {

class AudioRingBuffer {
 public:
  explicit AudioRingBuffer(size_t capacity_frames);

  AudioRingBuffer(const AudioRingBuffer&) = delete;
  AudioRingBuffer& operator=(const AudioRingBuffer&) = delete;

  size_t capacity_frames() const { return capacity_; }

  size_t Write(const int16_t* interleaved, size_t frames);
  size_t Read(int16_t* interleaved_out, size_t frames);
  size_t Discard(size_t frames);

  size_t AvailableToRead() const;
  size_t AvailableToWrite() const;

  void RequestFlush() {
    flush_requested_.store(true, std::memory_order_release);
  }
  bool ApplyPendingFlush();

  uint64_t total_written() const {
    return write_pos_.load(std::memory_order_acquire);
  }
  uint64_t total_read() const {
    return read_pos_.load(std::memory_order_acquire);
  }

 private:
  size_t capacity_;
  size_t mask_;
  std::unique_ptr<int16_t[]> data_;
  alignas(64) std::atomic<uint64_t> write_pos_{0};
  alignas(64) std::atomic<uint64_t> read_pos_{0};
  std::atomic<bool> flush_requested_{false};
};

}  // namespace delta

#endif  // DELTA_CORE_AUDIO_RING_BUFFER_H_
