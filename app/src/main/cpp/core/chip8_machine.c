// Copyright 2026 Google LLC

#include "core/chip8_machine.h"

#include <string.h>

static const uint8_t kChip8Font[80] = {
    0xF0, 0x90, 0x90, 0x90, 0xF0,  // 0
    0x20, 0x60, 0x20, 0x20, 0x70,  // 1
    0xF0, 0x10, 0xF0, 0x80, 0xF0,  // 2
    0xF0, 0x10, 0xF0, 0x10, 0xF0,  // 3
    0x90, 0x90, 0xF0, 0x10, 0x10,  // 4
    0xF0, 0x80, 0xF0, 0x10, 0xF0,  // 5
    0xF0, 0x80, 0xF0, 0x90, 0xF0,  // 6
    0xF0, 0x10, 0x20, 0x40, 0x40,  // 7
    0xF0, 0x90, 0xF0, 0x90, 0xF0,  // 8
    0xF0, 0x90, 0xF0, 0x10, 0xF0,  // 9
    0xF0, 0x90, 0xF0, 0x90, 0x90,  // A
    0xE0, 0x90, 0xE0, 0x90, 0xE0,  // B
    0xF0, 0x80, 0x80, 0x80, 0xF0,  // C
    0xE0, 0x90, 0x90, 0x90, 0xE0,  // D
    0xF0, 0x80, 0xF0, 0x80, 0xF0,  // E
    0xF0, 0x80, 0xF0, 0x80, 0x80,  // F
};

#define CHIP8_ADDR_MASK 0x0FFFu

static uint32_t chip8_next_random(chip8_machine* m) {
  uint32_t x = m->rng_state;
  x ^= x << 13;
  x ^= x >> 17;
  x ^= x << 5;
  m->rng_state = x;
  return x;
}

void chip8_reset(chip8_machine* m, uint32_t seed) {
  uint8_t flags[CHIP8_NUM_FLAGS];
  uint32_t quirks = m->quirks;
  memcpy(flags, m->flags, sizeof(flags));
  memset(m, 0, sizeof(*m));
  memcpy(m->flags, flags, sizeof(flags));
  m->quirks = quirks;
  memcpy(&m->memory[CHIP8_FONT_ADDRESS], kChip8Font, sizeof(kChip8Font));
  m->pc = CHIP8_PROGRAM_START;
  m->wait_register = 0xFF;
  m->wait_key = 0xFF;
  m->rng_state = seed ? seed : 0x4C454D55u;
  m->display_dirty = 1;
}

bool chip8_load_program(chip8_machine* m, const uint8_t* rom, size_t size) {
  if (rom == NULL || size == 0 || size > CHIP8_MAX_ROM_SIZE) return false;
  memset(&m->memory[CHIP8_PROGRAM_START], 0, CHIP8_MAX_ROM_SIZE);
  memcpy(&m->memory[CHIP8_PROGRAM_START], rom, size);
  m->pc = CHIP8_PROGRAM_START;
  return true;
}

void chip8_tick_timers(chip8_machine* m) {
  if (m->delay_timer > 0) m->delay_timer--;
  if (m->sound_timer > 0) m->sound_timer--;
}

void chip8_set_keys(chip8_machine* m, uint16_t keys) {
  if (m->wait_register != 0xFF) {
    if (m->wait_key == 0xFF) {
      uint16_t pressed = (uint16_t)(keys & ~m->keys);
      for (uint8_t k = 0; k < CHIP8_NUM_KEYS; ++k) {
        if (pressed & (1u << k)) {
          m->wait_key = k;
          break;
        }
      }
    } else if (!(keys & (1u << m->wait_key))) {
      m->v[m->wait_register] = m->wait_key;
      m->wait_register = 0xFF;
      m->wait_key = 0xFF;
    }
  }
  m->keys = keys;
}

static void chip8_draw(chip8_machine* m, uint8_t x_reg, uint8_t y_reg,
                       uint8_t rows) {
  const bool wrap = (m->quirks & CHIP8_QUIRK_SPRITE_WRAP) != 0;
  const unsigned x0 = m->v[x_reg] % CHIP8_DISPLAY_WIDTH;
  const unsigned y0 = m->v[y_reg] % CHIP8_DISPLAY_HEIGHT;
  uint8_t collision = 0;
  for (unsigned row = 0; row < rows; ++row) {
    unsigned y = y0 + row;
    if (y >= CHIP8_DISPLAY_HEIGHT) {
      if (!wrap) break;
      y %= CHIP8_DISPLAY_HEIGHT;
    }
    const uint8_t bits = m->memory[(m->i + row) & CHIP8_ADDR_MASK];
    for (unsigned col = 0; col < 8; ++col) {
      if (!(bits & (0x80u >> col))) continue;
      unsigned x = x0 + col;
      if (x >= CHIP8_DISPLAY_WIDTH) {
        if (!wrap) break;
        x %= CHIP8_DISPLAY_WIDTH;
      }
      uint8_t* px = &m->display[y * CHIP8_DISPLAY_WIDTH + x];
      if (*px) collision = 1;
      *px ^= 1;
    }
  }
  m->v[0xF] = collision;
  m->display_dirty = 1;
}

void chip8_step(chip8_machine* m) {
  if (m->halted || m->wait_register != 0xFF) return;

  const uint16_t op = (uint16_t)((m->memory[m->pc & CHIP8_ADDR_MASK] << 8) |
                                 m->memory[(m->pc + 1) & CHIP8_ADDR_MASK]);
  m->pc = (uint16_t)((m->pc + 2) & CHIP8_ADDR_MASK);
  m->cycles++;

  const uint8_t x = (op >> 8) & 0xF;
  const uint8_t y = (op >> 4) & 0xF;
  const uint8_t n = op & 0xF;
  const uint8_t nn = op & 0xFF;
  const uint16_t nnn = op & 0xFFF;
  uint8_t* v = m->v;

  switch (op >> 12) {
    case 0x0:
      if (op == 0x00E0) {
        memset(m->display, 0, sizeof(m->display));
        m->display_dirty = 1;
      } else if (op == 0x00EE) {
        if (m->sp == 0) {
          m->halted = 1;
          return;
        }
        m->pc = m->stack[--m->sp];
      }
      break;
    case 0x1:
      m->pc = nnn;
      break;
    case 0x2:
      if (m->sp >= CHIP8_STACK_DEPTH) {
        m->halted = 1;
        return;
      }
      m->stack[m->sp++] = m->pc;
      m->pc = nnn;
      break;
    case 0x3:
      if (v[x] == nn) m->pc = (uint16_t)((m->pc + 2) & CHIP8_ADDR_MASK);
      break;
    case 0x4:
      if (v[x] != nn) m->pc = (uint16_t)((m->pc + 2) & CHIP8_ADDR_MASK);
      break;
    case 0x5:
      if (n == 0 && v[x] == v[y]) {
        m->pc = (uint16_t)((m->pc + 2) & CHIP8_ADDR_MASK);
      }
      break;
    case 0x6:
      v[x] = nn;
      break;
    case 0x7:
      v[x] = (uint8_t)(v[x] + nn);
      break;
    case 0x8: {
      const bool reset_vf = (m->quirks & CHIP8_QUIRK_LOGIC_RESETS_VF) != 0;
      uint8_t flag;
      switch (n) {
        case 0x0:
          v[x] = v[y];
          break;
        case 0x1:
          v[x] |= v[y];
          if (reset_vf) v[0xF] = 0;
          break;
        case 0x2:
          v[x] &= v[y];
          if (reset_vf) v[0xF] = 0;
          break;
        case 0x3:
          v[x] ^= v[y];
          if (reset_vf) v[0xF] = 0;
          break;
        case 0x4: {
          const unsigned sum = (unsigned)v[x] + v[y];
          v[x] = (uint8_t)sum;
          v[0xF] = sum > 0xFF;
          break;
        }
        case 0x5:
          flag = v[x] >= v[y];
          v[x] = (uint8_t)(v[x] - v[y]);
          v[0xF] = flag;
          break;
        case 0x6: {
          const uint8_t src =
              (m->quirks & CHIP8_QUIRK_SHIFT_USES_VY) ? v[y] : v[x];
          v[x] = (uint8_t)(src >> 1);
          v[0xF] = src & 1;
          break;
        }
        case 0x7:
          flag = v[y] >= v[x];
          v[x] = (uint8_t)(v[y] - v[x]);
          v[0xF] = flag;
          break;
        case 0xE: {
          const uint8_t src =
              (m->quirks & CHIP8_QUIRK_SHIFT_USES_VY) ? v[y] : v[x];
          v[x] = (uint8_t)(src << 1);
          v[0xF] = (src >> 7) & 1;
          break;
        }
        default:
          break;
      }
      break;
    }
    case 0x9:
      if (n == 0 && v[x] != v[y]) {
        m->pc = (uint16_t)((m->pc + 2) & CHIP8_ADDR_MASK);
      }
      break;
    case 0xA:
      m->i = nnn;
      break;
    case 0xB:
      m->pc = (uint16_t)((nnn + v[0]) & CHIP8_ADDR_MASK);
      break;
    case 0xC:
      v[x] = (uint8_t)((chip8_next_random(m) >> 8) & nn);
      break;
    case 0xD:
      chip8_draw(m, x, y, n);
      break;
    case 0xE:
      if (nn == 0x9E) {
        if (m->keys & (1u << (v[x] & 0xF))) {
          m->pc = (uint16_t)((m->pc + 2) & CHIP8_ADDR_MASK);
        }
      } else if (nn == 0xA1) {
        if (!(m->keys & (1u << (v[x] & 0xF)))) {
          m->pc = (uint16_t)((m->pc + 2) & CHIP8_ADDR_MASK);
        }
      }
      break;
    case 0xF:
      switch (nn) {
        case 0x07:
          v[x] = m->delay_timer;
          break;
        case 0x0A:
          m->wait_register = x;
          m->wait_key = 0xFF;
          break;
        case 0x15:
          m->delay_timer = v[x];
          break;
        case 0x18:
          m->sound_timer = v[x];
          break;
        case 0x1E:
          m->i = (uint16_t)((m->i + v[x]) & CHIP8_ADDR_MASK);
          break;
        case 0x29:
          m->i = (uint16_t)(CHIP8_FONT_ADDRESS + (v[x] & 0xF) * 5);
          break;
        case 0x33:
          m->memory[m->i & CHIP8_ADDR_MASK] = v[x] / 100;
          m->memory[(m->i + 1) & CHIP8_ADDR_MASK] = (v[x] / 10) % 10;
          m->memory[(m->i + 2) & CHIP8_ADDR_MASK] = v[x] % 10;
          break;
        case 0x55:
          for (unsigned r = 0; r <= x; ++r) {
            m->memory[(m->i + r) & CHIP8_ADDR_MASK] = v[r];
          }
          if (m->quirks & CHIP8_QUIRK_LOAD_STORE_INC_I) {
            m->i = (uint16_t)((m->i + x + 1) & CHIP8_ADDR_MASK);
          }
          break;
        case 0x65:
          for (unsigned r = 0; r <= x; ++r) {
            v[r] = m->memory[(m->i + r) & CHIP8_ADDR_MASK];
          }
          if (m->quirks & CHIP8_QUIRK_LOAD_STORE_INC_I) {
            m->i = (uint16_t)((m->i + x + 1) & CHIP8_ADDR_MASK);
          }
          break;
        case 0x75:
          memcpy(m->flags, v, (size_t)x + 1);
          break;
        case 0x85:
          memcpy(v, m->flags, (size_t)x + 1);
          break;
        default:
          break;
      }
      break;
    default:
      break;
  }
}

#define CHIP8_STATE_MAGIC 0x53384331u
#define CHIP8_STATE_VERSION 1u

typedef struct {
  uint8_t* out;
  const uint8_t* in;
  size_t pos;
  size_t size;
  bool ok;
} chip8_cursor;

static void put_bytes(chip8_cursor* c, const void* src, size_t n) {
  if (!c->ok || c->pos + n > c->size) {
    c->ok = false;
    return;
  }
  memcpy(c->out + c->pos, src, n);
  c->pos += n;
}
static void put_u8(chip8_cursor* c, uint8_t value) { put_bytes(c, &value, 1); }
static void put_u16(chip8_cursor* c, uint16_t value) {
  const uint8_t b[2] = {(uint8_t)value, (uint8_t)(value >> 8)};
  put_bytes(c, b, 2);
}
static void put_u32(chip8_cursor* c, uint32_t value) {
  put_u16(c, (uint16_t)value);
  put_u16(c, (uint16_t)(value >> 16));
}
static void put_u64(chip8_cursor* c, uint64_t value) {
  put_u32(c, (uint32_t)value);
  put_u32(c, (uint32_t)(value >> 32));
}

static void get_bytes(chip8_cursor* c, void* dst, size_t n) {
  if (!c->ok || c->pos + n > c->size) {
    c->ok = false;
    return;
  }
  memcpy(dst, c->in + c->pos, n);
  c->pos += n;
}
static uint8_t get_u8(chip8_cursor* c) {
  uint8_t b = 0;
  get_bytes(c, &b, 1);
  return b;
}
static uint16_t get_u16(chip8_cursor* c) {
  uint8_t b[2] = {0, 0};
  get_bytes(c, b, 2);
  return (uint16_t)(b[0] | (b[1] << 8));
}
static uint32_t get_u32(chip8_cursor* c) {
  const uint32_t lo = get_u16(c);
  const uint32_t hi = get_u16(c);
  return lo | (hi << 16);
}
static uint64_t get_u64(chip8_cursor* c) {
  const uint64_t lo = get_u32(c);
  const uint64_t hi = get_u32(c);
  return lo | (hi << 32);
}

size_t chip8_serialized_size(void) {
  return 4 + 4 + CHIP8_MEMORY_SIZE + 16 + 2 + 2 + 2 * CHIP8_STACK_DEPTH + 1 +
         1 + 1 + CHIP8_DISPLAY_WIDTH * CHIP8_DISPLAY_HEIGHT + 2 + 1 + 1 +
         CHIP8_NUM_FLAGS + 4 + 4 + 1 + 8;
}

bool chip8_serialize(const chip8_machine* m, uint8_t* out, size_t size) {
  if (m == NULL || out == NULL || size < chip8_serialized_size()) return false;
  chip8_cursor c = {out, NULL, 0, size, true};
  put_u32(&c, CHIP8_STATE_MAGIC);
  put_u32(&c, CHIP8_STATE_VERSION);
  put_bytes(&c, m->memory, sizeof(m->memory));
  put_bytes(&c, m->v, sizeof(m->v));
  put_u16(&c, m->i);
  put_u16(&c, m->pc);
  for (int k = 0; k < CHIP8_STACK_DEPTH; ++k) put_u16(&c, m->stack[k]);
  put_u8(&c, m->sp);
  put_u8(&c, m->delay_timer);
  put_u8(&c, m->sound_timer);
  put_bytes(&c, m->display, sizeof(m->display));
  put_u16(&c, m->keys);
  put_u8(&c, m->wait_register);
  put_u8(&c, m->wait_key);
  put_bytes(&c, m->flags, sizeof(m->flags));
  put_u32(&c, m->rng_state);
  put_u32(&c, m->quirks);
  put_u8(&c, m->halted);
  put_u64(&c, m->cycles);
  return c.ok;
}

bool chip8_unserialize(chip8_machine* m, const uint8_t* in, size_t size) {
  if (m == NULL || in == NULL || size < chip8_serialized_size()) return false;
  chip8_cursor c = {NULL, in, 0, size, true};
  if (get_u32(&c) != CHIP8_STATE_MAGIC) return false;
  if (get_u32(&c) != CHIP8_STATE_VERSION) return false;
  chip8_machine tmp;
  memset(&tmp, 0, sizeof(tmp));
  get_bytes(&c, tmp.memory, sizeof(tmp.memory));
  get_bytes(&c, tmp.v, sizeof(tmp.v));
  tmp.i = get_u16(&c) & CHIP8_ADDR_MASK;
  tmp.pc = get_u16(&c) & CHIP8_ADDR_MASK;
  for (int k = 0; k < CHIP8_STACK_DEPTH; ++k) {
    tmp.stack[k] = get_u16(&c) & CHIP8_ADDR_MASK;
  }
  tmp.sp = get_u8(&c);
  tmp.delay_timer = get_u8(&c);
  tmp.sound_timer = get_u8(&c);
  get_bytes(&c, tmp.display, sizeof(tmp.display));
  tmp.keys = get_u16(&c);
  tmp.wait_register = get_u8(&c);
  tmp.wait_key = get_u8(&c);
  get_bytes(&c, tmp.flags, sizeof(tmp.flags));
  tmp.rng_state = get_u32(&c);
  tmp.quirks = get_u32(&c);
  tmp.halted = get_u8(&c);
  tmp.cycles = get_u64(&c);
  if (!c.ok) return false;
  if (tmp.sp > CHIP8_STACK_DEPTH) return false;
  if (tmp.wait_register != 0xFF && tmp.wait_register > 0xF) return false;
  if (tmp.wait_key != 0xFF && tmp.wait_key > 0xF) return false;
  for (size_t p = 0; p < sizeof(tmp.display); ++p) tmp.display[p] &= 1;
  if (tmp.rng_state == 0) tmp.rng_state = 0x4C454D55u;
  tmp.display_dirty = 1;
  *m = tmp;
  return true;
}
