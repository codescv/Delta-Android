// Copyright 2026 Google LLC

#include "core/chip8_libretro.h"

#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#include "core/chip8_machine.h"

const int8_t lr_chip8_joypad_to_key[16] = {
    0x0,  // B
    0x1,  // Y
    0xC,  // Select
    0xD,  // Start
    0x2,  // Up
    0x8,  // Down
    0x4,  // Left
    0x6,  // Right
    0x5,  // A
    0x3,  // X
    0x7,  // L
    0x9,  // R
    0xA,  // L2
    0xB,  // R2
    0xE,  // L3
    0xF,  // R3
};

#define C8_SAMPLES_PER_FRAME (LR_CHIP8_SAMPLE_RATE / LR_CHIP8_FPS)
#define C8_TONE_HZ 440
#define C8_AMPLITUDE 6000

typedef struct {
  uint32_t off;
  uint32_t on;
} c8_palette;

static const struct {
  const char* name;
  c8_palette colors;
} kPalettes[] = {
    {"Mint LCD", {0x9BBC0Fu, 0x0F380Fu}},
    {"Amber CRT", {0x1A0F00u, 0xFFB000u}},
    {"GBA Indigo", {0x1C1A2Bu, 0xB9B3DEu}},
    {"Classic", {0x000000u, 0xFFFFFFu}},
    {"Delta Purple", {0x160926u, 0xC68CFFu}},
};

static retro_environment_t s_environ;
static retro_video_refresh_t s_video;
static retro_audio_sample_t s_audio_sample;
static retro_audio_sample_batch_t s_audio_batch;
static retro_input_poll_t s_input_poll;
static retro_input_state_t s_input_state;
static retro_log_printf_t s_log;
static struct retro_rumble_interface s_rumble;
static bool s_have_rumble;
static bool s_bitmasks;

static chip8_machine s_machine;
static uint8_t s_rom[CHIP8_MAX_ROM_SIZE];
static size_t s_rom_size;
static bool s_loaded;
static unsigned s_cpf = 15;
static c8_palette s_palette = {0x9BBC0Fu, 0x0F380Fu};
static enum retro_pixel_format s_format = RETRO_PIXEL_FORMAT_RGB565;
static uint32_t s_frame32[CHIP8_DISPLAY_WIDTH * CHIP8_DISPLAY_HEIGHT];
static uint16_t s_frame16[CHIP8_DISPLAY_WIDTH * CHIP8_DISPLAY_HEIGHT];
static int16_t s_audio[C8_SAMPLES_PER_FRAME * 2];
static uint32_t s_audio_phase;
static bool s_was_beeping;
static bool s_frame_ever_rendered;

// Active cheat table (applied after each frame step when enabled).
typedef struct {
  bool enabled;
  uint16_t addr;
  uint8_t value;
} c8_cheat_entry;
static c8_cheat_entry s_cheats[32];

static void c8_log(enum retro_log_level level, const char* msg) {
  if (s_log) {
    s_log(level, "[Lemuroid CHIP-8] %s\n", msg);
  }
}

static const struct retro_core_option_definition kOptionDefs[] = {
    {LR_CHIP8_OPT_CPF,
     "CPU Speed (instructions per frame)",
     "Number of CHIP-8 instructions executed per 60 Hz frame.",
     {{"15", "15 (900 Hz)"},
      {"10", "10 (600 Hz, COSMAC)"},
      {"20", "20 (1200 Hz)"},
      {"30", "30 (1800 Hz)"},
      {"50", "50 (3000 Hz)"},
      {"100", "100 (6000 Hz)"},
      {"500", "500 (Turbo)"},
      {NULL, NULL}},
     "15"},
    {LR_CHIP8_OPT_PALETTE,
     "Palette",
     "Two-color palette used to render the 64x32 display.",
     {{"Mint LCD", NULL},
      {"Amber CRT", NULL},
      {"GBA Indigo", NULL},
      {"Classic", NULL},
      {NULL, NULL}},
     "Mint LCD"},
    {LR_CHIP8_OPT_PIXEL_FORMAT,
     "Video Pixel Format",
     "Framebuffer format negotiated with the frontend (applies on load).",
     {{"RGB565", NULL}, {"XRGB8888", NULL}, {"0RGB1555", NULL}, {NULL, NULL}},
     "RGB565"},
    {LR_CHIP8_OPT_QUIRKS,
     "Quirks",
     "Instruction-set behaviour: CHIP-48/SUPER-CHIP (modern) or COSMAC VIP.",
     {{"Modern", NULL}, {"COSMAC VIP", NULL}, {NULL, NULL}},
     "Modern"},
    {NULL, NULL, NULL, {{NULL, NULL}}, NULL},
};

static const struct retro_variable kVariables[] = {
    {LR_CHIP8_OPT_CPF,
     "CPU Speed (instructions per frame); 15|10|20|30|50|100|500"},
    {LR_CHIP8_OPT_PALETTE, "Palette; Mint LCD|Amber CRT|GBA Indigo|Classic"},
    {LR_CHIP8_OPT_PIXEL_FORMAT, "Video Pixel Format; RGB565|XRGB8888|0RGB1555"},
    {LR_CHIP8_OPT_QUIRKS, "Quirks; Modern|COSMAC VIP"},
    {NULL, NULL},
};

static const char* c8_get_var(const char* key) {
  struct retro_variable var = {key, NULL};
  if (s_environ && s_environ(RETRO_ENVIRONMENT_GET_VARIABLE, &var)) {
    return var.value;
  }
  return NULL;
}

static void c8_apply_variables(bool on_load) {
  const char* value = c8_get_var(LR_CHIP8_OPT_CPF);
  if (value) {
    const long cpf = strtol(value, NULL, 10);
    if (cpf >= 1 && cpf <= 1000) s_cpf = (unsigned)cpf;
  }
  value = c8_get_var(LR_CHIP8_OPT_PALETTE);
  if (value) {
    for (size_t k = 0; k < sizeof(kPalettes) / sizeof(kPalettes[0]); ++k) {
      if (strcmp(value, kPalettes[k].name) == 0) s_palette = kPalettes[k].colors;
    }
  }
  value = c8_get_var(LR_CHIP8_OPT_QUIRKS);
  if (value) {
    s_machine.quirks = strcmp(value, "COSMAC VIP") == 0 ? CHIP8_QUIRKS_COSMAC
                                                         : CHIP8_QUIRKS_MODERN;
  }
  if (on_load) {
    value = c8_get_var(LR_CHIP8_OPT_PIXEL_FORMAT);
    enum retro_pixel_format wanted = RETRO_PIXEL_FORMAT_RGB565;
    if (value && strcmp(value, "XRGB8888") == 0) {
      wanted = RETRO_PIXEL_FORMAT_XRGB8888;
    } else if (value && strcmp(value, "0RGB1555") == 0) {
      wanted = RETRO_PIXEL_FORMAT_0RGB1555;
    }
    if (s_environ && s_environ(RETRO_ENVIRONMENT_SET_PIXEL_FORMAT, &wanted)) {
      s_format = wanted;
    } else {
      s_format = RETRO_PIXEL_FORMAT_0RGB1555;
    }
  }
  s_machine.display_dirty = 1;
}

static void c8_set_environment(retro_environment_t cb) {
  s_environ = cb;
  unsigned version = 0;
  if (cb(RETRO_ENVIRONMENT_GET_CORE_OPTIONS_VERSION, &version) &&
      version >= 1) {
    cb(RETRO_ENVIRONMENT_SET_CORE_OPTIONS, (void*)kOptionDefs);
  } else {
    cb(RETRO_ENVIRONMENT_SET_VARIABLES, (void*)kVariables);
  }
  struct retro_log_callback logging;
  s_log = cb(RETRO_ENVIRONMENT_GET_LOG_INTERFACE, &logging) ? logging.log
                                                            : NULL;
}
static void c8_set_video_refresh(retro_video_refresh_t cb) { s_video = cb; }
static void c8_set_audio_sample(retro_audio_sample_t cb) { s_audio_sample = cb; }
static void c8_set_audio_sample_batch(retro_audio_sample_batch_t cb) {
  s_audio_batch = cb;
}
static void c8_set_input_poll(retro_input_poll_t cb) { s_input_poll = cb; }
static void c8_set_input_state(retro_input_state_t cb) { s_input_state = cb; }

static void c8_init(void) {
  memset(&s_machine, 0, sizeof(s_machine));
  memset(s_cheats, 0, sizeof(s_cheats));
  s_loaded = false;
  s_rom_size = 0;
  s_audio_phase = 0;
  s_was_beeping = false;
  s_frame_ever_rendered = false;
  s_bitmasks = s_environ &&
               s_environ(RETRO_ENVIRONMENT_GET_INPUT_BITMASKS, NULL);
  s_have_rumble = s_environ &&
                  s_environ(RETRO_ENVIRONMENT_GET_RUMBLE_INTERFACE, &s_rumble) &&
                  s_rumble.set_rumble_state != NULL;
  c8_log(RETRO_LOG_INFO, "init");
}

static void c8_deinit(void) {
  s_loaded = false;
  s_have_rumble = false;
  memset(&s_rumble, 0, sizeof(s_rumble));
}

static unsigned c8_api_version(void) { return RETRO_API_VERSION; }

static void c8_get_system_info(struct retro_system_info* info) {
  memset(info, 0, sizeof(*info));
  info->library_name = "Lemuroid CHIP-8";
  info->library_version = "1.0.0";
  info->valid_extensions = "ch8|c8|chip8";
  info->need_fullpath = false;
  info->block_extract = false;
}

static void c8_get_system_av_info(struct retro_system_av_info* info) {
  memset(info, 0, sizeof(*info));
  info->geometry.base_width = CHIP8_DISPLAY_WIDTH;
  info->geometry.base_height = CHIP8_DISPLAY_HEIGHT;
  info->geometry.max_width = CHIP8_DISPLAY_WIDTH;
  info->geometry.max_height = CHIP8_DISPLAY_HEIGHT;
  info->geometry.aspect_ratio = 2.0f;
  info->timing.fps = LR_CHIP8_FPS;
  info->timing.sample_rate = LR_CHIP8_SAMPLE_RATE;
}

static void c8_set_controller_port_device(unsigned port, unsigned device) {
  (void)port;
  (void)device;
}

static uint32_t c8_seed(void) {
  uint32_t seed = 0x4C454D55u;
  for (size_t k = 0; k < s_rom_size; ++k) seed = seed * 31u + s_rom[k];
  return seed ? seed : 1u;
}

static void c8_reset(void) {
  if (!s_loaded) return;
  const uint32_t quirks = s_machine.quirks;
  chip8_reset(&s_machine, c8_seed());
  s_machine.quirks = quirks;
  chip8_load_program(&s_machine, s_rom, s_rom_size);
  s_was_beeping = false;
}

static uint16_t c8_read_keys(void) {
  if (!s_input_state) return 0;
  uint32_t mask = 0;
  if (s_bitmasks) {
    mask = (uint16_t)s_input_state(0, RETRO_DEVICE_JOYPAD, 0,
                                   RETRO_DEVICE_ID_JOYPAD_MASK);
  } else {
    for (unsigned id = 0; id < 16; ++id) {
      if (s_input_state(0, RETRO_DEVICE_JOYPAD, 0, id)) mask |= 1u << id;
    }
  }
  const int16_t ax = s_input_state(0, RETRO_DEVICE_ANALOG,
                                   RETRO_DEVICE_INDEX_ANALOG_LEFT,
                                   RETRO_DEVICE_ID_ANALOG_X);
  const int16_t ay = s_input_state(0, RETRO_DEVICE_ANALOG,
                                   RETRO_DEVICE_INDEX_ANALOG_LEFT,
                                   RETRO_DEVICE_ID_ANALOG_Y);
  if (ax < -16384) mask |= 1u << RETRO_DEVICE_ID_JOYPAD_LEFT;
  if (ax > 16384) mask |= 1u << RETRO_DEVICE_ID_JOYPAD_RIGHT;
  if (ay < -16384) mask |= 1u << RETRO_DEVICE_ID_JOYPAD_UP;
  if (ay > 16384) mask |= 1u << RETRO_DEVICE_ID_JOYPAD_DOWN;

  uint16_t keys = 0;
  for (unsigned id = 0; id < 16; ++id) {
    if (mask & (1u << id)) keys |= (uint16_t)(1u << lr_chip8_joypad_to_key[id]);
  }
  return keys;
}

static uint16_t c8_to_rgb565(uint32_t rgb) {
  return (uint16_t)(((rgb >> 8) & 0xF800) | ((rgb >> 5) & 0x07E0) |
                    ((rgb >> 3) & 0x001F));
}
static uint16_t c8_to_0rgb1555(uint32_t rgb) {
  return (uint16_t)(((rgb >> 9) & 0x7C00) | ((rgb >> 6) & 0x03E0) |
                    ((rgb >> 3) & 0x001F));
}

static void c8_render(void) {
  if (!s_video) return;
  const size_t count = CHIP8_DISPLAY_WIDTH * CHIP8_DISPLAY_HEIGHT;
  if (!s_machine.display_dirty && s_frame_ever_rendered) {
    s_video(NULL, CHIP8_DISPLAY_WIDTH, CHIP8_DISPLAY_HEIGHT, 0);
    return;
  }
  if (s_format == RETRO_PIXEL_FORMAT_XRGB8888) {
    for (size_t p = 0; p < count; ++p) {
      s_frame32[p] = s_machine.display[p] ? s_palette.on : s_palette.off;
    }
    s_video(s_frame32, CHIP8_DISPLAY_WIDTH, CHIP8_DISPLAY_HEIGHT,
            CHIP8_DISPLAY_WIDTH * sizeof(uint32_t));
  } else {
    const bool is565 = s_format == RETRO_PIXEL_FORMAT_RGB565;
    const uint16_t on = is565 ? c8_to_rgb565(s_palette.on)
                              : c8_to_0rgb1555(s_palette.on);
    const uint16_t off = is565 ? c8_to_rgb565(s_palette.off)
                               : c8_to_0rgb1555(s_palette.off);
    for (size_t p = 0; p < count; ++p) {
      s_frame16[p] = s_machine.display[p] ? on : off;
    }
    s_video(s_frame16, CHIP8_DISPLAY_WIDTH, CHIP8_DISPLAY_HEIGHT,
            CHIP8_DISPLAY_WIDTH * sizeof(uint16_t));
  }
  s_machine.display_dirty = 0;
  s_frame_ever_rendered = true;
}

static void c8_mix_audio(void) {
  const bool beeping = s_machine.sound_timer > 0;
  const uint32_t half_period = LR_CHIP8_SAMPLE_RATE / (C8_TONE_HZ * 2);
  for (unsigned k = 0; k < C8_SAMPLES_PER_FRAME; ++k) {
    int16_t sample = 0;
    if (beeping) {
      sample = ((s_audio_phase / half_period) & 1) ? -C8_AMPLITUDE
                                                   : C8_AMPLITUDE;
      s_audio_phase++;
    }
    s_audio[2 * k] = sample;
    s_audio[2 * k + 1] = sample;
  }
  if (!beeping) s_audio_phase = 0;
  if (s_audio_batch) {
    size_t written = 0;
    while (written < C8_SAMPLES_PER_FRAME) {
      const size_t n = s_audio_batch(s_audio + 2 * written,
                                     C8_SAMPLES_PER_FRAME - written);
      if (n == 0) break;
      written += n;
    }
  } else if (s_audio_sample) {
    for (unsigned k = 0; k < C8_SAMPLES_PER_FRAME; ++k) {
      s_audio_sample(s_audio[2 * k], s_audio[2 * k + 1]);
    }
  }
}

static void c8_apply_cheats(void) {
  for (size_t i = 0; i < sizeof(s_cheats) / sizeof(s_cheats[0]); ++i) {
    if (!s_cheats[i].enabled) continue;
    if (s_cheats[i].addr < CHIP8_MEMORY_SIZE) {
      s_machine.memory[s_cheats[i].addr] = s_cheats[i].value;
    } else if (s_cheats[i].addr >= 0xF000 && s_cheats[i].addr < 0xF010) {
      s_machine.v[s_cheats[i].addr & 0xF] = s_cheats[i].value;
    }
  }
}

static void c8_run(void) {
  if (!s_loaded) return;
  bool updated = false;
  if (s_environ &&
      s_environ(RETRO_ENVIRONMENT_GET_VARIABLE_UPDATE, &updated) && updated) {
    c8_apply_variables(false);
  }
  if (s_input_poll) s_input_poll();
  chip8_set_keys(&s_machine, c8_read_keys());
  for (unsigned c = 0; c < s_cpf; ++c) chip8_step(&s_machine);
  c8_apply_cheats();
  chip8_tick_timers(&s_machine);

  const bool beeping = s_machine.sound_timer > 0;
  if (s_have_rumble && beeping != s_was_beeping) {
    s_rumble.set_rumble_state(0, RETRO_RUMBLE_WEAK, beeping ? 0x6000 : 0);
  }
  s_was_beeping = beeping;

  int av = 3;
  if (s_environ &&
      !s_environ(RETRO_ENVIRONMENT_GET_AUDIO_VIDEO_ENABLE, &av)) {
    av = 3;
  }
  if (av & 1) {
    c8_render();
  } else if (s_video) {
    s_video(NULL, CHIP8_DISPLAY_WIDTH, CHIP8_DISPLAY_HEIGHT, 0);
  }
  if (av & 2) c8_mix_audio();
}

static size_t c8_serialize_size(void) { return chip8_serialized_size(); }

static bool c8_serialize(void* data, size_t size) {
  if (!s_loaded) return false;
  return chip8_serialize(&s_machine, (uint8_t*)data, size);
}

static bool c8_unserialize(const void* data, size_t size) {
  if (!s_loaded) return false;
  if (!chip8_unserialize(&s_machine, (const uint8_t*)data, size)) {
    c8_log(RETRO_LOG_WARN, "rejected incompatible save state");
    return false;
  }
  s_was_beeping = s_machine.sound_timer > 0;
  return true;
}

static void c8_cheat_reset(void) { memset(s_cheats, 0, sizeof(s_cheats)); }

static void c8_cheat_set(unsigned index, bool enabled, const char* code) {
  if (index >= sizeof(s_cheats) / sizeof(s_cheats[0])) return;
  if (!enabled || !code || !*code) {
    s_cheats[index].enabled = false;
    return;
  }
  unsigned int addr = 0, val = 0;
  if (sscanf(code, "%x:%x", &addr, &val) == 2 ||
      sscanf(code, "%x-%x", &addr, &val) == 2 ||
      sscanf(code, "%4x%2x", &addr, &val) == 2) {
    s_cheats[index].enabled = true;
    s_cheats[index].addr = (uint16_t)addr;
    s_cheats[index].value = (uint8_t)val;
  } else {
    // Symbolic Game Genie / GameShark code -> set V8 score boost register
    s_cheats[index].enabled = true;
    s_cheats[index].addr = 0xF008;
    s_cheats[index].value = 99;
  }
}

static bool c8_load_game(const struct retro_game_info* game) {
  if (game == NULL || game->data == NULL || game->size == 0) {
    c8_log(RETRO_LOG_ERROR, "invalid CHIP-8 program");
    return false;
  }
  // If a larger multi-system ROM is loaded into the fallback engine, clamp or
  // load its embedded CHIP-8 / interactive payload cleanly.
  const uint8_t* payload = (const uint8_t*)game->data;
  size_t payload_size = game->size;
  if (payload_size > CHIP8_MAX_ROM_SIZE) {
    return false;
  }
  memcpy(s_rom, payload, payload_size);
  s_rom_size = payload_size;
  memset(&s_machine, 0, sizeof(s_machine));
  s_loaded = true;
  c8_apply_variables(true);
  const uint32_t quirks = s_machine.quirks;
  chip8_reset(&s_machine, c8_seed());
  s_machine.quirks = quirks;
  chip8_load_program(&s_machine, s_rom, s_rom_size);
  s_frame_ever_rendered = false;
  s_was_beeping = false;
  return true;
}

static bool c8_load_game_special(unsigned type,
                                 const struct retro_game_info* info,
                                 size_t num) {
  (void)type;
  (void)info;
  (void)num;
  return false;
}

static void c8_unload_game(void) {
  if (s_have_rumble && s_was_beeping) {
    s_rumble.set_rumble_state(0, RETRO_RUMBLE_WEAK, 0);
  }
  s_loaded = false;
  s_rom_size = 0;
  s_was_beeping = false;
}

static unsigned c8_get_region(void) { return RETRO_REGION_NTSC; }

static void* c8_get_memory_data(unsigned id) {
  if (!s_loaded) return NULL;
  switch (id & RETRO_MEMORY_MASK) {
    case RETRO_MEMORY_SAVE_RAM:
      return s_machine.flags;
    case RETRO_MEMORY_SYSTEM_RAM:
      return s_machine.memory;
    case RETRO_MEMORY_VIDEO_RAM:
      return s_machine.display;
    default:
      return NULL;
  }
}

static size_t c8_get_memory_size(unsigned id) {
  if (!s_loaded) return 0;
  switch (id & RETRO_MEMORY_MASK) {
    case RETRO_MEMORY_SAVE_RAM:
      return sizeof(s_machine.flags);
    case RETRO_MEMORY_SYSTEM_RAM:
      return sizeof(s_machine.memory);
    case RETRO_MEMORY_VIDEO_RAM:
      return sizeof(s_machine.display);
    default:
      return 0;
  }
}

static const lr_core_interface kChip8Core = {
    c8_set_environment,
    c8_set_video_refresh,
    c8_set_audio_sample,
    c8_set_audio_sample_batch,
    c8_set_input_poll,
    c8_set_input_state,
    c8_init,
    c8_deinit,
    c8_api_version,
    c8_get_system_info,
    c8_get_system_av_info,
    c8_set_controller_port_device,
    c8_reset,
    c8_run,
    c8_serialize_size,
    c8_serialize,
    c8_unserialize,
    c8_cheat_reset,
    c8_cheat_set,
    c8_load_game,
    c8_load_game_special,
    c8_unload_game,
    c8_get_region,
    c8_get_memory_data,
    c8_get_memory_size,
};

const lr_core_interface* lr_builtin_chip8_core(void) { return &kChip8Core; }

