import numpy as np
import math

MARK = 1200.0
SPACE = 2200.0
BAUD = 1200.0

def _bits_from_bytes_le(data: bytes) -> list[int]:
    bits = []
    for b in data:
        for i in range(8):
            bits.append((b >> i) & 1)
    return bits

def _bitstuff(bits: list[int]) -> list[int]:
    out = []
    ones = 0
    for bit in bits:
        out.append(bit)
        if bit == 1:
            ones += 1
            if ones == 5:
                out.append(0)
                ones = 0
        else:
            ones = 0
    return out

def _nrzi(bits: list[int]) -> list[int]:
    s = 0
    out = []
    for bit in bits:
        if bit == 0:
            s ^= 1
        out.append(s)
    return out

def modulate_afsk(frame: bytes, fs: int = 11025) -> np.ndarray:
    flag = 0x7E
    pre_flags = 32
    post_flags = 8
    flags = bytes([flag] * pre_flags)
    tail = bytes([flag] * post_flags)
    bits = _bits_from_bytes_le(flags + frame + tail)
    bits = _bitstuff(bits)
    nrzi = _nrzi(bits)
    spb = int(fs / BAUD)
    out = np.zeros(len(nrzi) * spb, dtype=np.float32)
    phase = 0.0
    for i, v in enumerate(nrzi):
        f = MARK if v == 1 else SPACE
        for j in range(spb):
            out[i*spb + j] = math.sin(phase)
            phase += 2.0 * math.pi * f / fs
            if phase > 2.0 * math.pi:
                phase -= 2.0 * math.pi
    return out