// Copyright 2026 Google LLC

#ifndef DELTA_CORE_DEMO_ROMS_H_
#define DELTA_CORE_DEMO_ROMS_H_

#include <stddef.h>
#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

typedef struct lr_demo_rom {
  const char* file_name;
  const uint8_t* data;
  size_t size;
} lr_demo_rom;

size_t lr_demo_rom_count(void);
const lr_demo_rom* lr_demo_rom_at(size_t index);
const lr_demo_rom* lr_demo_rom_named(const char* file_name);

#ifdef __cplusplus
}  // extern "C"
#endif

#endif  // DELTA_CORE_DEMO_ROMS_H_
