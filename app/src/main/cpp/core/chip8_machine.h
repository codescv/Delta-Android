// Copyright 2026 Google LLC

#ifndef DELTA_CORE_CHIP8_MACHINE_H_
#define DELTA_CORE_CHIP8_MACHINE_H_

#include <stdbool.h>
#include <stddef.h>
#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

#define CHIP8_MEMORY_SIZE 4096
#define CHIP8_PROGRAM_START 0x200
#define CHIP8_MAX_ROM_SIZE (CHIP8_MEMORY_SIZE - CHIP8_PROGRAM_START)
#define CHIP8_FONT_ADDRESS 0x050
#define CHIP8_DISPLAY_WIDTH 64
#define CHIP8_DISPLAY_HEIGHT 32
#define CHIP8_NUM_KEYS 16
#define CHIP8_NUM_FLAGS 16
#define CHIP8_STACK_DEPTH 16

// Quirk bits.
#define CHIP8_QUIRK_SHIFT_USES_VY (1u << 0)
#define CHIP8_QUIRK_LOAD_STORE_INC_I (1u << 1)
#define CHIP8_QUIRK_LOGIC_RESETS_VF (1u << 2)
#define CHIP8_QUIRK_SPRITE_WRAP (1u << 3)

#define CHIP8_QUIRKS_MODERN 0u
#define CHIP8_QUIRKS_COSMAC                                   \
  (CHIP8_QUIRK_SHIFT_USES_VY | CHIP8_QUIRK_LOAD_STORE_INC_I | \
   CHIP8_QUIRK_LOGIC_RESETS_VF)

typedef struct chip8_machine {
  uint8_t memory[CHIP8_MEMORY_SIZE];
  uint8_t v[16];
  uint16_t i;
  uint16_t pc;
  uint16_t stack[CHIP8_STACK_DEPTH];
  uint8_t sp;
  uint8_t delay_timer;
  uint8_t sound_timer;
  uint8_t display[CHIP8_DISPLAY_WIDTH * CHIP8_DISPLAY_HEIGHT];
  uint16_t keys;
  uint8_t wait_register;
  uint8_t wait_key;
  uint8_t flags[CHIP8_NUM_FLAGS];
  uint32_t rng_state;
  uint32_t quirks;
  uint8_t halted;
  uint8_t display_dirty;
  uint64_t cycles;
} chip8_machine;

void chip8_reset(chip8_machine* m, uint32_t seed);
bool chip8_load_program(chip8_machine* m, const uint8_t* rom, size_t size);
void chip8_step(chip8_machine* m);
void chip8_tick_timers(chip8_machine* m);
void chip8_set_keys(chip8_machine* m, uint16_t keys);
size_t chip8_serialized_size(void);
bool chip8_serialize(const chip8_machine* m, uint8_t* out, size_t size);
bool chip8_unserialize(chip8_machine* m, const uint8_t* in, size_t size);

#ifdef __cplusplus
}  // extern "C"
#endif

#endif  // DELTA_CORE_CHIP8_MACHINE_H_
