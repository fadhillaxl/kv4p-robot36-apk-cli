import numpy as np

def _encode_call(call: str, ssid: int = 0, last: bool = False) -> bytes:
    call = (call.upper() + ' ' * 6)[:6]
    b = bytearray()
    for c in call:
        b.append((ord(c) << 1) & 0xFE)
    ss = ((ssid & 0x0F) << 1) | 0x60
    if last:
        ss |= 0x01
    b.append(ss)
    return bytes(b)

def _crc16_hdlc(data: bytes) -> int:
    crc = 0xFFFF
    for byte in data:
        curr = byte
        for _ in range(8):
            mix = (crc ^ curr) & 0x01
            crc >>= 1
            if mix:
                crc ^= 0x8408
            curr >>= 1
    crc ^= 0xFFFF
    return crc & 0xFFFF

def build_aprs_message(src: str, dst: str, digis: list[str], text: str, msgnum: int | None) -> bytes:
    digi_bytes = b''
    for i, d in enumerate(digis):
        is_last = (i == len(digis) - 1)
        digi_bytes += _encode_call(d.replace('*',''), 0, is_last)
    
    # The last address byte (whether source or last digi) must have the 'last' bit (LSB=1) set.
    # The _encode_call helper sets it if 'last=True' is passed.
    # However, the previous logic was a bit messy with manual bit manipulation.
    # Let's clarify:
    # AX.25 frame: DST(7) + SRC(7) + DIGIS(7*n)
    # The LSB of the last byte of the last field in the address block must be 1.
    # All others must be 0.
    
    # Re-encoding cleanly:
    # DST (never last if SRC exists)
    dst_ssid = 0
    if '-' in dst: dst_call, dst_ssid = dst.split('-')
    else: dst_call = dst
    dst_bytes = _encode_call(dst_call, int(dst_ssid), False)
    
    # SRC (last if no digis)
    src_ssid = 0
    if '-' in src: src_call, src_ssid = src.split('-')
    else: src_call = src
    src_bytes = _encode_call(src_call, int(src_ssid), len(digis) == 0)
    
    # DIGIS
    digi_bytes = b''
    for i, d in enumerate(digis):
        d_ssid = 0
        if '-' in d: d_call, d_ssid = d.split('-')
        else: d_call = d
        is_last = (i == len(digis) - 1)
        digi_bytes += _encode_call(d_call, int(d_ssid), is_last)
        
    addr = dst_bytes + src_bytes + digi_bytes
    ctrl = bytes([0x03])
    pid = bytes([0xF0])
    target = (dst if dst else 'CQ').upper()
    info = (':' + target.ljust(9) + ':' + text)
    if msgnum is not None:
        info += '{' + str(msgnum)
    info_bytes = info.encode('ascii', 'replace')
    frame_no_fcs = addr + ctrl + pid + info_bytes
    fcs = _crc16_hdlc(frame_no_fcs)
    fcs_bytes = bytes([fcs & 0xFF, (fcs >> 8) & 0xFF])
    return frame_no_fcs + fcs_bytes