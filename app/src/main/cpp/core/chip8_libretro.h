// Copyright 2026 Google LLC

#ifndef EXPERIMENTAL_USERS_CHII_IOS_DELTA_CORE_CHIP8_LIBRETRO_H_
#define EXPERIMENTAL_USERS_CHII_IOS_DELTA_CORE_CHIP8_LIBRETRO_H_

#include "core/lr_core_interface.h"

#ifdef __cplusplus
extern "C" {
#endif

#define LR_CHIP8_SAMPLE_RATE 44100
#define LR_CHIP8_FPS 60

// Core option keys understood by the built-in CHIP-8 / multi-system cores.
#define LR_CHIP8_OPT_CPF "lemuroid_chip8_cpf"
#define LR_CHIP8_OPT_PALETTE "lemuroid_chip8_palette"
#define LR_CHIP8_OPT_PIXEL_FORMAT "lemuroid_chip8_pixel_format"
#define LR_CHIP8_OPT_QUIRKS "lemuroid_chip8_quirks"

extern const int8_t lr_chip8_joypad_to_key[16];

const lr_core_interface* lr_builtin_chip8_core(void);
const lr_core_interface* lr_builtin_nes_core(void);
const lr_core_interface* lr_builtin_snes_core(void);
const lr_core_interface* lr_builtin_n64_core(void);
const lr_core_interface* lr_builtin_gbc_core(void);
const lr_core_interface* lr_builtin_gba_core(void);
const lr_core_interface* lr_builtin_ds_core(void);
const lr_core_interface* lr_builtin_genesis_core(void);

#ifdef __cplusplus
}  // extern "C"
#endif

#endif  // EXPERIMENTAL_USERS_CHII_IOS_DELTA_CORE_CHIP8_LIBRETRO_H_
