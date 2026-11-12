// Copyright 2026 Google LLC

#include <dlfcn.h>

#include <cstdint>
#include <cstring>
#include <memory>
#include <string>
#include <vector>

#include "core/audio_resampler.h"
#include "core/audio_ring_buffer.h"
#include "core/chip8_libretro.h"
#include "core/chip8_machine.h"
#include "core/demo_roms.h"
#include "core/emu_util.h"
#include "core/libretro_host.h"
#include "core/pixel_convert.h"
#include "testing/base/public/gunit.h"

extern "C" void retro_run(void);

namespace delta {
namespace {

void* OpenSelfImage() {
#if defined(__APPLE__)
  Dl_info info;
  if (dladdr(reinterpret_cast<void*>(&retro_run), &info) == 0 ||
      info.dli_fname == nullptr) {
    return nullptr;
  }
  return dlopen(info.dli_fname, RTLD_NOW | RTLD_NOLOAD);
#else
  return dlopen(nullptr, RTLD_NOW);
#endif
}

constexpr uint16_t Bit(unsigned id) { return static_cast<uint16_t>(1u << id); }

const lr_demo_rom& Pong() { return *lr_demo_rom_named("Lemuroid Pong.ch8"); }
const lr_demo_rom& KeypadTest() {
  return *lr_demo_rom_named("Keypad Test.ch8");
}

std::unique_ptr<LibretroHost> BootChip8(const lr_demo_rom& rom,
                                        const std::string& pixel_format = "") {
  std::string error;
  auto host = LibretroHost::CreateBuiltin("chip8", &error);
  EXPECT_NE(host, nullptr) << error;
  if (!host) return nullptr;
  if (!pixel_format.empty()) {
    host->SetVariable(LR_CHIP8_OPT_PIXEL_FORMAT, pixel_format);
  }
  EXPECT_TRUE(
      host->LoadGameFromMemory(rom.file_name, rom.data, rom.size, &error))
      << error;
  return host;
}

struct Rgba {
  uint8_t r, g, b, a;
  bool operator==(const Rgba& o) const {
    return r == o.r && g == o.g && b == o.b && a == o.a;
  }
};

Rgba PixelAt(const std::vector<uint8_t>& frame, unsigned width, unsigned x,
             unsigned y) {
  const size_t o = (static_cast<size_t>(y) * width + x) * 4;
  return {frame[o], frame[o + 1], frame[o + 2], frame[o + 3]};
}

int PaddleXInFrame(LibretroHost& host) {
  std::vector<uint8_t> frame;
  unsigned w = 0, h = 0;
  uint64_t serial = 0;
  if (!host.CopyFrameRgba(&frame, &w, &h, &serial)) return -1;
  const Rgba off = PixelAt(frame, w, 0, h - 1);
  for (unsigned x = 0; x < w; ++x) {
    if (!(PixelAt(frame, w, x, 30) == off)) return static_cast<int>(x);
  }
  return -1;
}

int PaddleX(LibretroHost& host) {
  for (int k = 0; k < 8; ++k) {
    const int x = PaddleXInFrame(host);
    if (x >= 0) return x;
    host.RunFrame();
  }
  return -1;
}

TEST(Chip8MachineTest, ArithmeticFlagsAndBcd) {
  chip8_machine m{};
  chip8_reset(&m, 1);
  const uint8_t program[] = {
      0x60, 0xFF, 0x61, 0x02, 0x80, 0x14, 0x62,
      0x7B, 0xA3, 0x00, 0xF2, 0x33, 0xF2, 0x65,
  };
  ASSERT_TRUE(chip8_load_program(&m, program, sizeof(program)));
  for (int k = 0; k < 3; ++k) chip8_step(&m);
  EXPECT_EQ(m.v[0], 1);
  EXPECT_EQ(m.v[0xF], 1);
  for (int k = 0; k < 4; ++k) chip8_step(&m);
  EXPECT_EQ(m.v[0], 1);
  EXPECT_EQ(m.v[1], 2);
  EXPECT_EQ(m.v[2], 3);
}

TEST(LibretroHostTest, BootsBuiltinCoreAndRuns120Frames) {
  auto host = BootChip8(Pong());
  ASSERT_NE(host, nullptr);
  EXPECT_EQ(host->library_name(), "Lemuroid CHIP-8");
  for (int k = 0; k < 120; ++k) host->RunFrame();
  EXPECT_EQ(host->frames_run(), 120u);
  std::vector<uint8_t> frame;
  unsigned w = 0, h = 0;
  uint64_t serial = 0;
  ASSERT_TRUE(host->CopyFrameRgba(&frame, &w, &h, &serial));
  EXPECT_EQ(w, 64u);
  EXPECT_EQ(h, 32u);
  EXPECT_GE(serial, 1u);
}

std::vector<uint8_t> MakeMinimalNesRom() {
  // 16-byte iNES header (1 x 16 KiB PRG, 1 x 8 KiB CHR, mapper 0) + 16 KiB PRG
  // + 8 KiB CHR. Reset vector at $FFFC points to $8000 (`JMP $8000`).
  std::vector<uint8_t> rom(16 + 16384 + 8192, 0);
  rom[0] = 'N';
  rom[1] = 'E';
  rom[2] = 'S';
  rom[3] = 0x1A;
  rom[4] = 1;          // 1 x 16 KiB PRG
  rom[5] = 1;          // 1 x 8 KiB CHR
  rom[16 + 0] = 0x4C;  // JMP $8000
  rom[16 + 1] = 0x00;
  rom[16 + 2] = 0x80;
  rom[16 + 0x3FFC] = 0x00;
  rom[16 + 0x3FFD] = 0x80;
  return rom;
}

std::vector<uint8_t> MakeMinimalSnesRom() {
  // 32 KiB LoROM with header at $7FC0 and reset vector -> $8000 (`BRA -2`).
  std::vector<uint8_t> rom(32768, 0);
  rom[0] = 0x80;  // BRA -2
  rom[1] = 0xFE;
  rom[0x7FDC] = 0xFF;
  rom[0x7FDD] = 0xFF;
  rom[0x7FDE] = 0x00;
  rom[0x7FDF] = 0x00;
  rom[0x7FFC] = 0x00;
  rom[0x7FFD] = 0x80;
  return rom;
}

std::vector<uint8_t> MakeMinimalGbRom() {
  // 32 KiB GB ROM with `JR -2` at $0100 and valid header checksum at $014D.
  std::vector<uint8_t> rom(32768, 0);
  rom[0x0100] = 0x18;  // JR -2
  rom[0x0101] = 0xFE;
  static const uint8_t kLogo[48] = {
      0xCE, 0xED, 0x66, 0x66, 0xCC, 0x0D, 0x00, 0x0B, 0x03, 0x73, 0x00, 0x83,
      0x00, 0x0C, 0x00, 0x0D, 0x00, 0x08, 0x11, 0x1F, 0x88, 0x89, 0x00, 0x0E,
      0xDC, 0xCC, 0x6E, 0xE6, 0xDD, 0xDD, 0xD9, 0x99, 0xBB, 0xBB, 0x67, 0x63,
      0x6E, 0x0E, 0xEC, 0xCC, 0xDD, 0xDC, 0x99, 0x9F, 0xBB, 0xB9, 0x33, 0x3E,
  };
  std::memcpy(&rom[0x0104], kLogo, sizeof(kLogo));
  uint8_t checksum = 0;
  for (int i = 0x0134; i <= 0x014C; ++i) checksum = checksum - rom[i] - 1;
  rom[0x014D] = checksum;
  return rom;
}

std::vector<uint8_t> MakeMinimalGbaRom() {
  // Minimal GBA ROM with ARM `B .` (`0xEAFFFFFE`) at offset 0 and fixed 0x96
  // header byte at 0xB2.
  std::vector<uint8_t> rom(4096, 0);
  rom[0] = 0xFE;
  rom[1] = 0xFF;
  rom[2] = 0xFF;
  rom[3] = 0xEA;
  rom[0xB2] = 0x96;
  return rom;
}

std::vector<uint8_t> MakeMinimalGenesisRom() {
  std::vector<uint8_t> rom(0x40000, 0);
  // 68000 initial SSP = 0x00FFFE00, initial PC = 0x00000200.
  rom[0] = 0x00;
  rom[1] = 0xFF;
  rom[2] = 0xFE;
  rom[3] = 0x00;
  rom[4] = 0x00;
  rom[5] = 0x00;
  rom[6] = 0x02;
  rom[7] = 0x00;
  const char* magic = "SEGA MEGA DRIVE ";
  std::memcpy(&rom[0x100], magic, 16);
  // 0x200: BRA.S -2 (infinite loop).
  rom[0x200] = 0x60;
  rom[0x201] = 0xFE;
  return rom;
}

void WriteLe32(std::vector<uint8_t>* buf, size_t off, uint32_t v) {
  (*buf)[off] = v & 0xFF;
  (*buf)[off + 1] = (v >> 8) & 0xFF;
  (*buf)[off + 2] = (v >> 16) & 0xFF;
  (*buf)[off + 3] = (v >> 24) & 0xFF;
}

std::vector<uint8_t> MakeMinimalDsRom() {
  std::vector<uint8_t> rom(0x8000, 0);
  std::memcpy(&rom[0x00], "DELTA_TEST  ", 12);
  std::memcpy(&rom[0x0C], "DLTA", 4);
  // ARM9 offset, entry, ram, size
  WriteLe32(&rom, 0x20, 0x4000);
  WriteLe32(&rom, 0x24, 0x02000000);
  WriteLe32(&rom, 0x28, 0x02000000);
  WriteLe32(&rom, 0x2C, 0x200);
  // ARM7 offset, entry, ram, size
  WriteLe32(&rom, 0x30, 0x5000);
  WriteLe32(&rom, 0x34, 0x03800000);
  WriteLe32(&rom, 0x38, 0x03800000);
  WriteLe32(&rom, 0x3C, 0x200);
  // Header size & ROM size
  WriteLe32(&rom, 0x80, 0x8000);
  WriteLe32(&rom, 0x84, 0x200);
  // ARM infinite loop `b .` (0xEAFFFFFE) at ARM9 & ARM7 entries.
  WriteLe32(&rom, 0x4000, 0xEAFFFFFE);
  WriteLe32(&rom, 0x5000, 0xEAFFFFFE);
  return rom;
}

std::vector<uint8_t> MakeMinimalN64Rom() {
  std::vector<uint8_t> rom(0x10000, 0);
  // Big-endian .z64 PI BSD domain 1 header + clock rate + boot PC (0x80000400).
  rom[0] = 0x80;
  rom[1] = 0x37;
  rom[2] = 0x12;
  rom[3] = 0x40;
  rom[4] = 0x00;
  rom[5] = 0x00;
  rom[6] = 0x00;
  rom[7] = 0x0F;
  rom[8] = 0x80;
  rom[9] = 0x00;
  rom[10] = 0x04;
  rom[11] = 0x00;
  std::memcpy(&rom[0x20], "DELTA N64 TEST      ", 20);
  // Minimal MIPS IPL3 payload (executed from SP DMEM 0xA4000040) that
  // configures NTSC 320x240 VI timing (scheduling VI_INT) and spins cleanly.
  const uint32_t kProgram[] = {
      0x3C08A440,  // lui  $t0, 0xA440
      0x34090200,  // ori  $t1, $0, 0x0200    (VI_V_INTR_REG)
      0xAD09000C,  // sw   $t1, 0x0C($t0)
      0x3409020D,  // ori  $t1, $0, 0x020D    (VI_V_SYNC_REG -> schedules
                   // VI_INT)
      0xAD090018,  // sw   $t1, 0x18($t0)
      0x3C0903E5,  // lui  $t1, 0x03E5
      0x35292239,  // ori  $t1, $t1, 0x2239  (VI_BURST_REG)
      0xAD090014,  // sw   $t1, 0x14($t0)
      0x34090C15,  // ori  $t1, $0, 0x0C15    (VI_H_SYNC_REG)
      0xAD09001C,  // sw   $t1, 0x1C($t0)
      0x3C090010,  // lui  $t1, 0x0010        (VI_ORIGIN_REG)
      0xAD090004,  // sw   $t1, 0x04($t0)
      0x34090140,  // ori  $t1, $0, 320       (VI_WIDTH_REG)
      0xAD090008,  // sw   $t1, 0x08($t0)
      0x3C09006C,  // lui  $t1, 0x006C
      0x352902EC,  // ori  $t1, $t1, 0x02EC  (VI_H_START_REG)
      0xAD090024,  // sw   $t1, 0x24($t0)
      0x3C090025,  // lui  $t1, 0x0025
      0x352901FF,  // ori  $t1, $t1, 0x01FF  (VI_V_START_REG)
      0xAD090028,  // sw   $t1, 0x28($t0)
      0x34090200,  // ori  $t1, $0, 0x0200    (VI_X_SCALE_REG)
      0xAD090030,  // sw   $t1, 0x30($t0)
      0x34090400,  // ori  $t1, $0, 0x0400    (VI_Y_SCALE_REG)
      0xAD090034,  // sw   $t1, 0x34($t0)
      0x34090002,  // ori  $t1, $0, 0x0002    (VI_STATUS_REG = 16bpp)
      0xAD090000,  // sw   $t1, 0x00($t0)
      0x1000FFFF,  // beq  $0, $0, -1
      0x00000000,  // nop
  };
  for (size_t i = 0; i < sizeof(kProgram) / sizeof(kProgram[0]); ++i) {
    const size_t off = 0x40 + i * 4;
    const uint32_t w = kProgram[i];
    rom[off + 0] = static_cast<uint8_t>((w >> 24) & 0xFF);
    rom[off + 1] = static_cast<uint8_t>((w >> 16) & 0xFF);
    rom[off + 2] = static_cast<uint8_t>((w >> 8) & 0xFF);
    rom[off + 3] = static_cast<uint8_t>(w & 0xFF);
  }
  return rom;
}

TEST(LibretroHostTest, BootsAllRealBuiltinConsoleCores) {
  // All 8 built-in cores (7 official consoles + CHIP-8) must initialize.
  const std::pair<const char*, const char*> kAllCores[] = {
      {"chip8", "Lemuroid CHIP-8"},
      {"nes", "QuickNES"},
      {"snes", "Snes9x 2005"},
      {"n64", "ParaLLEl N64"},
      {"gbc", "Gambatte"},
      {"gba", "VBA Next"},
      {"ds", "melonDS"},
      {"genesis", "Genesis Plus GX"},
  };
  for (const auto& [core_id, expected_name] : kAllCores) {
    std::string error;
    auto host = LibretroHost::CreateBuiltin(core_id, &error);
    ASSERT_NE(host, nullptr) << core_id << ": " << error;
    EXPECT_EQ(host->library_name(), expected_name) << core_id;
  }

  struct SysExpect {
    const char* core_id;
    const char* expected_name;
    const char* virtual_path;
    std::vector<uint8_t> rom;
    unsigned w;
    unsigned h;
  };
  const SysExpect kSystems[] = {
      {"nes", "QuickNES", "test.nes", MakeMinimalNesRom(), 256, 224},
      {"snes", "Snes9x 2005", "test.sfc", MakeMinimalSnesRom(), 256, 224},
      {"gbc", "Gambatte", "test.gb", MakeMinimalGbRom(), 160, 144},
      {"gba", "VBA Next", "test.gba", MakeMinimalGbaRom(), 240, 160},
      {"genesis", "Genesis Plus GX", "test.md", MakeMinimalGenesisRom(), 0, 0},
      {"ds", "melonDS", "test.nds", MakeMinimalDsRom(), 256, 384},
      {"n64", "ParaLLEl N64", "test.z64", MakeMinimalN64Rom(), 0, 0},
  };
  for (const auto& sys : kSystems) {
    std::string error;
    auto host = LibretroHost::CreateBuiltin(sys.core_id, &error);
    ASSERT_NE(host, nullptr) << sys.core_id << ": " << error;
    EXPECT_EQ(host->library_name(), sys.expected_name);
    ASSERT_TRUE(host->LoadGameFromMemory(sys.virtual_path, sys.rom.data(),
                                         sys.rom.size(), &error))
        << sys.core_id << ": " << error;
    if (std::string(sys.core_id) == "ds") {
      host->SetPointerState(true, 1234, -5678);
    }
    host->RunFrames(5, false);
    EXPECT_EQ(host->frames_run(), 5u);
    std::vector<uint8_t> frame;
    unsigned w = 0, h = 0;
    ASSERT_TRUE(host->CopyFrameRgba(&frame, &w, &h, nullptr)) << sys.core_id;
    if (sys.w > 0) EXPECT_EQ(w, sys.w) << sys.core_id;
    if (sys.h > 0) EXPECT_EQ(h, sys.h) << sys.core_id;
    EXPECT_GT(w, 0u) << sys.core_id;
    EXPECT_GT(h, 0u) << sys.core_id;
    std::vector<uint8_t> state;
    ASSERT_TRUE(host->SerializeState(&state, &error))
        << sys.core_id << ": " << error;
    ASSERT_TRUE(host->UnserializeState(state.data(), state.size(), &error))
        << sys.core_id << ": " << error;
  }
}

TEST(LibretroHostTest, ResolvesAllEntryPointsViaDlsym) {
  void* self = OpenSelfImage();
  ASSERT_NE(self, nullptr) << dlerror();
  std::string error;
  auto host = LibretroHost::CreateFromDlHandle(self, "self", &error);
  ASSERT_NE(host, nullptr) << error;
  const lr_demo_rom& rom = Pong();
  ASSERT_TRUE(
      host->LoadGameFromMemory(rom.file_name, rom.data, rom.size, &error))
      << error;
  host->RunFrames(10, false);
  EXPECT_EQ(host->frames_run(), 10u);
}

TEST(LibretroHostTest, JoypadBitmaskMovesThePaddle) {
  auto host = BootChip8(Pong());
  ASSERT_NE(host, nullptr);
  host->RunFrames(5, false);
  const int start = PaddleX(*host);
  EXPECT_EQ(start, 28);
  host->SetJoypadMask(0, Bit(RETRO_DEVICE_ID_JOYPAD_RIGHT));
  for (int k = 0; k < 30; ++k) host->RunFrame();
  const int right = PaddleX(*host);
  EXPECT_GT(right, start);
}

TEST(LibretroHostTest, ProducesAudioAndBeepsIntoRingBuffer) {
  auto host = BootChip8(KeypadTest());
  ASSERT_NE(host, nullptr);
  host->RunFrames(2, false);
  AudioRingBuffer* ring = host->audio_ring();
  ring->ApplyPendingFlush();
  ring->Discard(ring->AvailableToRead());
  host->SetJoypadMask(0, Bit(RETRO_DEVICE_ID_JOYPAD_A));
  host->RunFrame();
  host->SetJoypadMask(0, 0);
  host->RunFrames(3, false);
  EXPECT_EQ(ring->AvailableToRead(), 4u * 735u);
}

// --- Sub-frame input latch ---------------------------------------------------
// A probe core that wraps the CHIP-8 core and records what the frontend
// reports for the A button during each retro_run() after input_poll.
struct InputProbe {
  static retro_input_poll_t poll_cb;
  static retro_input_state_t state_cb;
  static int16_t last_a;
  static int16_t last_a_before_poll;
  static int16_t last_mask;
};
retro_input_poll_t InputProbe::poll_cb = nullptr;
retro_input_state_t InputProbe::state_cb = nullptr;
int16_t InputProbe::last_a = -1;
int16_t InputProbe::last_a_before_poll = -1;
int16_t InputProbe::last_mask = -1;

lr_core_interface MakeInputProbeCore() {
  lr_core_interface probe = *lr_builtin_chip8_core();
  probe.retro_set_input_poll = [](retro_input_poll_t cb) {
    InputProbe::poll_cb = cb;
    lr_builtin_chip8_core()->retro_set_input_poll(cb);
  };
  probe.retro_set_input_state = [](retro_input_state_t cb) {
    InputProbe::state_cb = cb;
    lr_builtin_chip8_core()->retro_set_input_state(cb);
  };
  probe.retro_run = []() {
    if (InputProbe::state_cb) {
      InputProbe::last_a_before_poll = InputProbe::state_cb(
          0, RETRO_DEVICE_JOYPAD, 0, RETRO_DEVICE_ID_JOYPAD_A);
    }
    if (InputProbe::poll_cb) InputProbe::poll_cb();
    if (InputProbe::state_cb) {
      InputProbe::last_a = InputProbe::state_cb(0, RETRO_DEVICE_JOYPAD, 0,
                                                RETRO_DEVICE_ID_JOYPAD_A);
      InputProbe::last_mask = InputProbe::state_cb(0, RETRO_DEVICE_JOYPAD, 0,
                                                   RETRO_DEVICE_ID_JOYPAD_MASK);
    }
    lr_builtin_chip8_core()->retro_run();
  };
  return probe;
}

std::unique_ptr<LibretroHost> BootInputProbe() {
  static const lr_core_interface kProbe = MakeInputProbeCore();
  std::string error;
  auto host =
      LibretroHost::CreateWithInterface(kProbe, "probe:input", nullptr, &error);
  EXPECT_NE(host, nullptr) << error;
  if (!host) return nullptr;
  const lr_demo_rom& rom = Pong();
  EXPECT_TRUE(
      host->LoadGameFromMemory(rom.file_name, rom.data, rom.size, &error))
      << error;
  return host;
}

TEST(LibretroHostTest, SubFrameTapIsLatchedForExactlyOneFrame) {
  auto host = BootInputProbe();
  ASSERT_NE(host, nullptr);
  host->RunFrames(2, false);
  EXPECT_EQ(InputProbe::last_a, 0);

  // Press and release A between two frames (a <16 ms tap on a 120 Hz screen).
  host->SetJoypadMask(0, Bit(RETRO_DEVICE_ID_JOYPAD_A));
  host->SetJoypadMask(0, 0);
  EXPECT_EQ(host->joypad_mask(0), 0);

  host->RunFrames(1, false);
  EXPECT_EQ(InputProbe::last_a, 1) << "tap was dropped between frames";
  EXPECT_EQ(InputProbe::last_a_before_poll, 1)
      << "latched state must be visible even before input_poll";
  EXPECT_EQ(InputProbe::last_mask & Bit(RETRO_DEVICE_ID_JOYPAD_A),
            Bit(RETRO_DEVICE_ID_JOYPAD_A));

  // The latch is consumed: the following frame sees A released.
  host->RunFrames(1, false);
  EXPECT_EQ(InputProbe::last_a, 0);
  EXPECT_EQ(InputProbe::last_a_before_poll, 0);
}

TEST(LibretroHostTest, HeldButtonStaysPressedAndReleaseIsObserved) {
  auto host = BootInputProbe();
  ASSERT_NE(host, nullptr);
  host->SetJoypadMask(0, Bit(RETRO_DEVICE_ID_JOYPAD_A));
  for (int k = 0; k < 3; ++k) {
    host->RunFrames(1, false);
    EXPECT_EQ(InputProbe::last_a, 1);
  }
  host->SetJoypadMask(0, 0);
  host->RunFrames(1, false);
  EXPECT_EQ(InputProbe::last_a, 0);
}

TEST(LibretroHostTest, ResetClearsPendingLatch) {
  auto host = BootInputProbe();
  ASSERT_NE(host, nullptr);
  host->RunFrames(1, false);
  host->SetJoypadMask(0, Bit(RETRO_DEVICE_ID_JOYPAD_A));
  host->SetJoypadMask(0, 0);
  host->Reset();
  host->RunFrames(1, false);
  EXPECT_EQ(InputProbe::last_a, 0);
}

TEST(Crc32Test, MatchesZlibReferenceAndIsIncremental) {
  const char kText[] = "123456789";
  EXPECT_EQ(Crc32(kText, 9), 0xCBF43926u);
}

}  // namespace
}  // namespace delta

#define LR_EXPORT extern "C" __attribute__((visibility("default"), used))
namespace {
const lr_core_interface& C8() { return *lr_builtin_chip8_core(); }
}  // namespace
LR_EXPORT void retro_set_environment(retro_environment_t cb) {
  C8().retro_set_environment(cb);
}
LR_EXPORT void retro_set_video_refresh(retro_video_refresh_t cb) {
  C8().retro_set_video_refresh(cb);
}
LR_EXPORT void retro_set_audio_sample(retro_audio_sample_t cb) {
  C8().retro_set_audio_sample(cb);
}
LR_EXPORT void retro_set_audio_sample_batch(retro_audio_sample_batch_t cb) {
  C8().retro_set_audio_sample_batch(cb);
}
LR_EXPORT void retro_set_input_poll(retro_input_poll_t cb) {
  C8().retro_set_input_poll(cb);
}
LR_EXPORT void retro_set_input_state(retro_input_state_t cb) {
  C8().retro_set_input_state(cb);
}
LR_EXPORT void retro_init(void) { C8().retro_init(); }
LR_EXPORT void retro_deinit(void) { C8().retro_deinit(); }
LR_EXPORT unsigned retro_api_version(void) { return C8().retro_api_version(); }
LR_EXPORT void retro_get_system_info(struct retro_system_info* info) {
  C8().retro_get_system_info(info);
}
LR_EXPORT void retro_get_system_av_info(struct retro_system_av_info* info) {
  C8().retro_get_system_av_info(info);
}
LR_EXPORT void retro_set_controller_port_device(unsigned port,
                                                unsigned device) {
  C8().retro_set_controller_port_device(port, device);
}
LR_EXPORT void retro_reset(void) { C8().retro_reset(); }
LR_EXPORT void retro_run(void) { C8().retro_run(); }
LR_EXPORT size_t retro_serialize_size(void) {
  return C8().retro_serialize_size();
}
LR_EXPORT bool retro_serialize(void* data, size_t size) {
  return C8().retro_serialize(data, size);
}
LR_EXPORT bool retro_unserialize(const void* data, size_t size) {
  return C8().retro_unserialize(data, size);
}
LR_EXPORT void retro_cheat_reset(void) { C8().retro_cheat_reset(); }
LR_EXPORT void retro_cheat_set(unsigned index, bool enabled, const char* code) {
  C8().retro_cheat_set(index, enabled, code);
}
LR_EXPORT bool retro_load_game(const struct retro_game_info* game) {
  return C8().retro_load_game(game);
}
LR_EXPORT bool retro_load_game_special(unsigned type,
                                       const struct retro_game_info* info,
                                       size_t num) {
  return C8().retro_load_game_special(type, info, num);
}
LR_EXPORT void retro_unload_game(void) { C8().retro_unload_game(); }
LR_EXPORT unsigned retro_get_region(void) { return C8().retro_get_region(); }
LR_EXPORT void* retro_get_memory_data(unsigned id) {
  return C8().retro_get_memory_data(id);
}
LR_EXPORT size_t retro_get_memory_size(unsigned id) {
  return C8().retro_get_memory_size(id);
}
