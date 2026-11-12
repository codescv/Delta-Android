cmake_minimum_required(VERSION 3.22.1)
project(delta_core C CXX)

# Delta for Android native engine: the Delta 2.0.5 libretro host (core/) plus all
# statically linked libretro console cores (cores/), mirroring the iOS BUILD file.
#
# DELTA_HOST_BUILD=ON builds the same engine for the desktop JVM (used by
# `./gradlew testDebugUnitTest` to exercise the real JNI bridge + cores).

option(DELTA_HOST_BUILD "Build for the host JVM instead of Android" OFF)

set(CMAKE_POSITION_INDEPENDENT_CODE ON)
set(CMAKE_C_STANDARD 11)
set(CMAKE_CXX_STANDARD 17)

set(ROOT ${CMAKE_CURRENT_SOURCE_DIR})
set(CORES ${ROOT}/cores)

set(RETRO_PREFIX_SYMS
    retro_set_environment retro_set_video_refresh retro_set_audio_sample
    retro_set_audio_sample_batch retro_set_input_poll retro_set_input_state
    retro_init retro_deinit retro_api_version retro_get_system_info
    retro_get_system_av_info retro_set_controller_port_device retro_reset
    retro_run retro_serialize_size retro_serialize retro_unserialize
    retro_cheat_reset retro_cheat_set retro_load_game retro_load_game_special
    retro_unload_game retro_get_region retro_get_memory_data
    retro_get_memory_size option_defs_us option_defs_intl option_cats_us
    options_us options_intl log_cb)

function(delta_prefix_defs out prefix)
  set(defs "")
  foreach(s ${RETRO_PREFIX_SYMS})
    list(APPEND defs "${s}=${prefix}_${s}")
  endforeach()
  set(${out} ${defs} PARENT_SCOPE)
endfunction()

set(COMMON_CORE_DEFS HAVE_NO_LANGEXTRA __LIBRETRO__)
set(COMMON_CORE_OPTS -fvisibility=hidden -w -Wno-everything -O2
    -fno-strict-aliasing)
if(NOT DELTA_HOST_BUILD)
  list(APPEND COMMON_CORE_OPTS -ffunction-sections -fdata-sections)
endif()
if(DELTA_HOST_BUILD AND CMAKE_C_COMPILER_ID STREQUAL "GNU")
  # GCC does not know -Wno-everything; -w already silences everything.
  list(REMOVE_ITEM COMMON_CORE_OPTS -Wno-everything)
  list(APPEND COMMON_CORE_OPTS -fpermissive)
endif()

function(delta_core_lib name prefix)
  cmake_parse_arguments(A "" "" "SOURCES;DEFS;INCLUDES;OPTS;CXXOPTS" ${ARGN})
  add_library(${name} STATIC ${A_SOURCES})
  delta_prefix_defs(pdefs ${prefix})
  target_compile_definitions(${name} PRIVATE ${COMMON_CORE_DEFS} ${A_DEFS} ${pdefs})
  target_include_directories(${name} PRIVATE ${A_INCLUDES}
      ${CORES}/libretro-common/include)
  target_compile_options(${name} PRIVATE ${COMMON_CORE_OPTS} ${A_OPTS})
  if(A_CXXOPTS)
    target_compile_options(${name} PRIVATE $<$<COMPILE_LANGUAGE:CXX>:${A_CXXOPTS}>)
  endif()
endfunction()

# --- libretro-common VFS ------------------------------------------------------
set(LRC ${CORES}/libretro-common)
add_library(libretro_common_vfs STATIC
    ${LRC}/compat/compat_posix_string.c
    ${LRC}/compat/compat_snprintf.c
    ${LRC}/compat/compat_strcasestr.c
    ${LRC}/compat/compat_strl.c
    ${LRC}/compat/fopen_utf8.c
    ${LRC}/encodings/encoding_utf.c
    ${LRC}/file/file_path.c
    ${LRC}/file/file_path_io.c
    ${LRC}/memalign.c
    ${LRC}/streams/file_stream.c
    ${LRC}/streams/file_stream_transforms.c
    ${LRC}/streams/memory_stream.c
    ${LRC}/string/rstrtod.c
    ${LRC}/string/stdstring.c
    ${LRC}/time/rtime.c
    ${LRC}/vfs/vfs_implementation.c)
target_include_directories(libretro_common_vfs PRIVATE ${LRC}/include)
target_compile_options(libretro_common_vfs PRIVATE -w -O2)

# --- VBA-Next (GBA) -----------------------------------------------------------
delta_core_lib(vba_next_core vbanext
  SOURCES
    ${CORES}/vba-next/libretro/libretro.c
    ${CORES}/vba-next/src/gba.c
    ${CORES}/vba-next/src/memory.c
    ${CORES}/vba-next/src/sound.c
    ${CORES}/vba-next/src/system.c
  DEFS HAVE_HLE_BIOS FRONTEND_SUPPORTS_RGB565 USE_CHEATS LOAD_FROM_MEMORY INLINE=inline
  INCLUDES ${CORES}/vba-next ${CORES}/vba-next/src ${CORES}/vba-next/libretro)

# --- Gambatte (GB/GBC) --------------------------------------------------------
file(GLOB_RECURSE GAMBATTE_SRCS
    ${CORES}/gambatte/libgambatte/src/*.cpp)
file(GLOB GAMBATTE_LR_SRCS
    ${CORES}/gambatte/libgambatte/libretro/*.c
    ${CORES}/gambatte/libgambatte/libretro/*.cpp)
delta_core_lib(gambatte_core gambatte
  SOURCES ${GAMBATTE_SRCS} ${GAMBATTE_LR_SRCS}
  DEFS VIDEO_RGB565 HAVE_STDINT_H HAVE_INTTYPES_H CC_RESAMPLER_NO_HIGHPASS
  INCLUDES ${CORES}/gambatte/libgambatte/src ${CORES}/gambatte/libgambatte/include
           ${CORES}/gambatte/common ${CORES}/gambatte/libgambatte/libretro)

# --- QuickNES (NES) -----------------------------------------------------------
file(GLOB QUICKNES_SRCS ${CORES}/quicknes/nes_emu/*.cpp ${CORES}/quicknes/libretro/*.cpp)
delta_core_lib(quicknes_core quicknes
  SOURCES ${QUICKNES_SRCS}
  INCLUDES ${CORES}/quicknes ${CORES}/quicknes/nes_emu ${CORES}/quicknes/libretro)

# --- Snes9x 2005 (SNES) -------------------------------------------------------
file(GLOB SNES9X_SRCS ${CORES}/snes9x2005/source/*.c)
list(REMOVE_ITEM SNES9X_SRCS
    ${CORES}/snes9x2005/source/dsp1emu.c
    ${CORES}/snes9x2005/source/dsp2emu.c
    ${CORES}/snes9x2005/source/dsp4emu.c)
delta_core_lib(snes9x_core snes9x
  SOURCES ${CORES}/snes9x2005/libretro.c ${SNES9X_SRCS}
  DEFS LOAD_FROM_MEMORY LAGFIX
  INCLUDES ${CORES}/snes9x2005 ${CORES}/snes9x2005/source)

# --- Genesis Plus GX (Genesis / SMS / GG) ------------------------------------
file(GLOB_RECURSE GPGX_SRCS ${CORES}/genesis-plus-gx/*.c)
set(GPGX ${CORES}/genesis-plus-gx)
delta_core_lib(genesis_plus_gx_core gpgx
  SOURCES ${GPGX_SRCS}
  DEFS USE_16BPP_RENDERING FRONTEND_SUPPORTS_RGB565 LSB_FIRST BYTE_ORDER=LITTLE_ENDIAN
       HAVE_ZLIB USE_LIBRETRO_VFS USE_LIBTREMOR USE_PER_SOUND_CHANNELS_CONFIG
       M68K_OVERCLOCK_SHIFT=20 Z80_OVERCLOCK_SHIFT=20 HAVE_YM3438_CORE HAVE_OPLL_CORE
       MAXROMSIZE=33554432 vram=gpgx_vram GIT_VERSION="b9c2316"
  INCLUDES ${GPGX}/core ${GPGX}/core/z80 ${GPGX}/core/m68k ${GPGX}/core/ntsc
           ${GPGX}/core/sound ${GPGX}/core/sound/minimp3 ${GPGX}/core/sound/tremor
           ${GPGX}/core/input_hw ${GPGX}/core/cd_hw ${GPGX}/core/cart_hw
           ${GPGX}/core/cart_hw/svp ${GPGX}/libretro ${GPGX}/libretro/deps/zlib-1.2.11)

# --- melonDS (Nintendo DS) ----------------------------------------------------
set(MDS ${CORES}/melonds/src)
file(GLOB_RECURSE MELONDS_C_SRCS ${MDS}/*.c)
file(GLOB_RECURSE MELONDS_CXX_SRCS ${MDS}/*.cpp)
list(FILTER MELONDS_CXX_SRCS EXCLUDE REGEX ".*/src/ARMJIT[^/]*\\.cpp$")
list(FILTER MELONDS_CXX_SRCS EXCLUDE REGEX ".*/src/(ARM_InstrInfo|GPU_OpenGL|GPU3D_OpenGL|OpenGLSupport)\\.cpp$")
list(FILTER MELONDS_CXX_SRCS EXCLUDE REGEX ".*/libretro/opengl\\.cpp$")
list(FILTER MELONDS_CXX_SRCS EXCLUDE REGEX ".*/teakra/src/test[^/]*\\.cpp$")
delta_core_lib(melonds_core melonds
  SOURCES ${MELONDS_C_SRCS} ${MELONDS_CXX_SRCS}
  DEFS MELONDS_VERSION="0.9.3" GIT_VERSION="66b5d26" HAVE_WIFI CORE
       video_cb=melonds_video_cb environ_cb=melonds_environ_cb
  INCLUDES ${MDS} ${MDS}/frontend ${MDS}/libretro ${MDS}/teakra/include ${MDS}/teakra/src)

# --- parallel-n64 / Mupen64Plus (N64, angrylion software RDP) -----------------
set(N64 ${CORES}/parallel-n64)
file(GLOB_RECURSE N64_C_SRCS ${N64}/*.c)
set(N64_TEXTUAL_C
    mupen64plus-rsp-cxd4/module.c mupen64plus-rsp-cxd4/su.c
    mupen64plus-rsp-cxd4/vu/add.c mupen64plus-rsp-cxd4/vu/divide.c
    mupen64plus-rsp-cxd4/vu/logical.c mupen64plus-rsp-cxd4/vu/multiply.c
    mupen64plus-rsp-cxd4/vu/select.c mupen64plus-rsp-cxd4/vu/vu.c
    mupen64plus-video-angrylion/n64video/rdp.c
    mupen64plus-video-angrylion/n64video/rdp/blender.c
    mupen64plus-video-angrylion/n64video/rdp/combiner.c
    mupen64plus-video-angrylion/n64video/rdp/coverage.c
    mupen64plus-video-angrylion/n64video/rdp/dither.c
    mupen64plus-video-angrylion/n64video/rdp/dps.c
    mupen64plus-video-angrylion/n64video/rdp/fbuffer.c
    mupen64plus-video-angrylion/n64video/rdp/fill_tri.c
    mupen64plus-video-angrylion/n64video/rdp/rasterizer.c
    mupen64plus-video-angrylion/n64video/rdp/rdram.c
    mupen64plus-video-angrylion/n64video/rdp/tcoord.c
    mupen64plus-video-angrylion/n64video/rdp/tex.c
    mupen64plus-video-angrylion/n64video/rdp/tmem.c
    mupen64plus-video-angrylion/n64video/rdp/zbuffer.c
    mupen64plus-video-angrylion/n64video/vi.c
    mupen64plus-video-angrylion/n64video/vi/divot.c
    mupen64plus-video-angrylion/n64video/vi/fetch.c
    mupen64plus-video-angrylion/n64video/vi/fill.c
    mupen64plus-video-angrylion/n64video/vi/gamma.c
    mupen64plus-video-angrylion/n64video/vi/lerp.c
    mupen64plus-video-angrylion/n64video/vi/restore.c
    mupen64plus-video-angrylion/n64video/vi/video.c)
foreach(f ${N64_TEXTUAL_C})
  list(REMOVE_ITEM N64_C_SRCS ${N64}/${f})
endforeach()
delta_core_lib(parallel_n64_core n64
  SOURCES ${N64_C_SRCS} ${N64}/Graphics/RDP/gDP_funcs.cpp ${N64}/Graphics/RSP/gSP_funcs.cpp
  DEFS HAVE_LLE HAVE_THR_AL INLINE=inline GIT_VERSION="bdfbe0b" M64P_PLUGIN_API
       M64P_CORE_PROTOTYPES
  INCLUDES ${N64} ${N64}/include ${N64}/libretro ${N64}/libretro-common/include
           ${N64}/mupen64plus-core/src ${N64}/mupen64plus-core/src/api
           ${N64}/mupen64plus-core/custom ${N64}/mupen64plus-core/subprojects/md5
           ${N64}/mupen64plus-core/src/plugin/audio_libretro
  OPTS -fcommon -fwrapv)
# parallel-n64 ships its own libretro-common; it must win over the shared one.
target_include_directories(parallel_n64_core BEFORE PRIVATE ${N64}/libretro-common/include)

# --- Delta host engine + CHIP-8 + JNI bridge ----------------------------------
add_library(delta_core SHARED
    core/builtin_cores.c
    core/chip8_libretro.c
    core/chip8_machine.c
    core/demo_roms.c
    core/audio_resampler.cc
    core/audio_ring_buffer.cc
    core/emu_util.cc
    core/libretro_host.cc
    core/pixel_convert.cc
    jni/delta_jni.cpp)
target_include_directories(delta_core PRIVATE ${ROOT})
set_source_files_properties(
    core/audio_resampler.cc core/audio_ring_buffer.cc core/emu_util.cc
    core/libretro_host.cc core/pixel_convert.cc jni/delta_jni.cpp
    PROPERTIES COMPILE_OPTIONS "-std=c++20")
target_compile_options(delta_core PRIVATE -O2 -fvisibility=hidden)

if(DELTA_HOST_BUILD)
  find_package(JNI REQUIRED)
  target_include_directories(delta_core PRIVATE ${JNI_INCLUDE_DIRS})
  target_compile_definitions(delta_core PRIVATE DELTA_HOST_BUILD=1)
  set(PLATFORM_LIBS pthread dl m)
else()
  find_library(log-lib log)
  find_library(aaudio-lib aaudio)
  set(PLATFORM_LIBS ${log-lib} ${aaudio-lib} m)
  target_link_options(delta_core PRIVATE -Wl,--gc-sections -Wl,--exclude-libs,ALL
      -Wl,-z,max-page-size=16384)
endif()

target_link_libraries(delta_core PRIVATE
    -Wl,--start-group
    vba_next_core gambatte_core quicknes_core snes9x_core genesis_plus_gx_core
    melonds_core parallel_n64_core libretro_common_vfs
    -Wl,--end-group
    ${PLATFORM_LIBS})
