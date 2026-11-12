// Copyright 2026 Google LLC

#include "core/pixel_convert.h"

#include <cstring>

namespace delta {
namespace {

inline uint8_t Expand5(uint32_t v) {
  return static_cast<uint8_t>((v << 3) | (v >> 2));
}
inline uint8_t Expand6(uint32_t v) {
  return static_cast<uint8_t>((v << 2) | (v >> 4));
}

}  // namespace

size_t BytesPerPixel(retro_pixel_format format) {
  switch (format) {
    case RETRO_PIXEL_FORMAT_0RGB1555:
    case RETRO_PIXEL_FORMAT_RGB565:
      return 2;
    case RETRO_PIXEL_FORMAT_XRGB8888:
      return 4;
    default:
      return 0;
  }
}

void Rgb565ToRgba(uint16_t pixel, uint8_t out[4]) {
  out[0] = Expand5((pixel >> 11) & 0x1F);
  out[1] = Expand6((pixel >> 5) & 0x3F);
  out[2] = Expand5(pixel & 0x1F);
  out[3] = 0xFF;
}

void Xrgb1555ToRgba(uint16_t pixel, uint8_t out[4]) {
  out[0] = Expand5((pixel >> 10) & 0x1F);
  out[1] = Expand5((pixel >> 5) & 0x1F);
  out[2] = Expand5(pixel & 0x1F);
  out[3] = 0xFF;
}

void Xrgb8888ToRgba(uint32_t pixel, uint8_t out[4]) {
  out[0] = static_cast<uint8_t>(pixel >> 16);
  out[1] = static_cast<uint8_t>(pixel >> 8);
  out[2] = static_cast<uint8_t>(pixel);
  out[3] = 0xFF;
}

bool ConvertToRgba8888(retro_pixel_format format, const void* src,
                       unsigned width, unsigned height, size_t pitch,
                       uint8_t* dst) {
  const size_t bpp = BytesPerPixel(format);
  if (bpp == 0 || src == nullptr || dst == nullptr) return false;
  if (pitch < static_cast<size_t>(width) * bpp) return false;
  const uint8_t* row = static_cast<const uint8_t*>(src);
  for (unsigned y = 0; y < height; ++y, row += pitch) {
    uint8_t* out = dst + static_cast<size_t>(y) * width * 4;
    if (bpp == 2) {
      for (unsigned x = 0; x < width; ++x, out += 4) {
        uint16_t px;
        std::memcpy(&px, row + x * 2, sizeof(px));
        if (format == RETRO_PIXEL_FORMAT_RGB565) {
          Rgb565ToRgba(px, out);
        } else {
          Xrgb1555ToRgba(px, out);
        }
      }
    } else {
      for (unsigned x = 0; x < width; ++x, out += 4) {
        uint32_t px;
        std::memcpy(&px, row + x * 4, sizeof(px));
        Xrgb8888ToRgba(px, out);
      }
    }
  }
  return true;
}

}  // namespace delta
