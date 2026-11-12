// Copyright 2026 Google LLC

#ifndef DELTA_CORE_PIXEL_CONVERT_H_
#define DELTA_CORE_PIXEL_CONVERT_H_

#include <cstddef>
#include <cstdint>

#include "core/lr_libretro.h"

namespace delta {

size_t BytesPerPixel(retro_pixel_format format);

bool ConvertToRgba8888(retro_pixel_format format, const void* src,
                       unsigned width, unsigned height, size_t pitch,
                       uint8_t* dst);

void Rgb565ToRgba(uint16_t pixel, uint8_t out[4]);
void Xrgb1555ToRgba(uint16_t pixel, uint8_t out[4]);
void Xrgb8888ToRgba(uint32_t pixel, uint8_t out[4]);

}  // namespace delta

#endif  // DELTA_CORE_PIXEL_CONVERT_H_
