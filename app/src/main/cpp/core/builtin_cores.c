// Copyright 2026 Google LLC

#include "core/chip8_libretro.h"

#define DECLARE_AND_DEFINE_CORE(fn_name, prefix)                               \
  extern void prefix##retro_set_environment(retro_environment_t);              \
  extern void prefix##retro_set_video_refresh(retro_video_refresh_t);          \
  extern void prefix##retro_set_audio_sample(retro_audio_sample_t);            \
  extern void prefix##retro_set_audio_sample_batch(                            \
      retro_audio_sample_batch_t);                                             \
  extern void prefix##retro_set_input_poll(retro_input_poll_t);                \
  extern void prefix##retro_set_input_state(retro_input_state_t);              \
  extern void prefix##retro_init(void);                                        \
  extern void prefix##retro_deinit(void);                                      \
  extern unsigned prefix##retro_api_version(void);                             \
  extern void prefix##retro_get_system_info(struct retro_system_info* info);   \
  extern void prefix##retro_get_system_av_info(                                \
      struct retro_system_av_info* info);                                      \
  extern void prefix##retro_set_controller_port_device(unsigned port,          \
                                                       unsigned device);       \
  extern void prefix##retro_reset(void);                                       \
  extern void prefix##retro_run(void);                                         \
  extern size_t prefix##retro_serialize_size(void);                            \
  extern bool prefix##retro_serialize(void* data, size_t size);                \
  extern bool prefix##retro_unserialize(const void* data, size_t size);        \
  extern void prefix##retro_cheat_reset(void);                                 \
  extern void prefix##retro_cheat_set(unsigned index, bool enabled,            \
                                      const char* code);                       \
  extern bool prefix##retro_load_game(const struct retro_game_info* game);     \
  extern bool prefix##retro_load_game_special(                                 \
      unsigned game_type, const struct retro_game_info* info, size_t num);     \
  extern void prefix##retro_unload_game(void);                                 \
  extern unsigned prefix##retro_get_region(void);                              \
  extern void* prefix##retro_get_memory_data(unsigned id);                     \
  extern size_t prefix##retro_get_memory_size(unsigned id);                    \
                                                                               \
  static const lr_core_interface kCore_##fn_name = {                           \
      prefix##retro_set_environment,                                           \
      prefix##retro_set_video_refresh,                                         \
      prefix##retro_set_audio_sample,                                          \
      prefix##retro_set_audio_sample_batch,                                    \
      prefix##retro_set_input_poll,                                            \
      prefix##retro_set_input_state,                                           \
      prefix##retro_init,                                                      \
      prefix##retro_deinit,                                                    \
      prefix##retro_api_version,                                               \
      prefix##retro_get_system_info,                                           \
      prefix##retro_get_system_av_info,                                        \
      prefix##retro_set_controller_port_device,                                \
      prefix##retro_reset,                                                     \
      prefix##retro_run,                                                       \
      prefix##retro_serialize_size,                                            \
      prefix##retro_serialize,                                                 \
      prefix##retro_unserialize,                                               \
      prefix##retro_cheat_reset,                                               \
      prefix##retro_cheat_set,                                                 \
      prefix##retro_load_game,                                                 \
      prefix##retro_load_game_special,                                         \
      prefix##retro_unload_game,                                               \
      prefix##retro_get_region,                                                \
      prefix##retro_get_memory_data,                                           \
      prefix##retro_get_memory_size,                                           \
  };                                                                           \
  const lr_core_interface* lr_builtin_##fn_name##_core(void) {                 \
    return &kCore_##fn_name;                                                   \
  }

DECLARE_AND_DEFINE_CORE(gba, vbanext_)
DECLARE_AND_DEFINE_CORE(gbc, gambatte_)
DECLARE_AND_DEFINE_CORE(nes, quicknes_)
DECLARE_AND_DEFINE_CORE(snes, snes9x_)
DECLARE_AND_DEFINE_CORE(genesis, gpgx_)
DECLARE_AND_DEFINE_CORE(ds, melonds_)
DECLARE_AND_DEFINE_CORE(n64, n64_)
