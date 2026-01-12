import wave
import numpy as np
from resample import to_rate
import time

LEAD_MS = 500
TAIL_MS = 500

def _silence_frames(sample_rate, frame_size, duration_ms):
    count = int(duration_ms / 40)
    frame = np.zeros(frame_size, dtype=np.float32)
    return [frame.copy() for _ in range(count)]

def transmit_wav(path, radio_service, sample_rate=48000, opus_frame_size=1920, export_buf=None):
    with wave.open(path, 'rb') as w:
        channels = w.getnchannels()
        sampwidth = w.getsampwidth()
        src_rate = w.getframerate()
        nframes = w.getnframes()
        raw = w.readframes(nframes)
    if sampwidth == 2:
        data = np.frombuffer(raw, dtype=np.int16)
        data = data.astype(np.float32) / 32767.0
    elif sampwidth == 1:
        data = np.frombuffer(raw, dtype=np.uint8).astype(np.float32)
        data = (data - 128.0) / 128.0
    else:
        data = np.frombuffer(raw, dtype=np.int16).astype(np.float32) / 32767.0
    if channels > 1:
        data = data.reshape(-1, channels).mean(axis=1)
    if src_rate != sample_rate:
        data = to_rate(data, src_rate, sample_rate)
    data = np.clip(data * 0.6, -1.0, 1.0)

    radio_service.ptt_down()
    for f in _silence_frames(sample_rate, opus_frame_size, LEAD_MS):
        radio_service.send_tx_audio(f)
        if export_buf is not None:
            export_buf.append(f.copy())
    idx = 0
    total = len(data)
    t0 = time.monotonic()
    n = 0
    while idx < total:
        frame = data[idx:idx+opus_frame_size]
        if len(frame) < opus_frame_size:
            pad = np.zeros(opus_frame_size - len(frame), dtype=np.float32)
            frame = np.concatenate([frame, pad])
        radio_service.send_tx_audio(frame)
        if export_buf is not None:
            export_buf.append(frame.copy())
        idx += opus_frame_size
        n += 1
        target = t0 + n * 0.040
        now = time.monotonic()
        sleep = target - now
        if sleep > 0:
            time.sleep(sleep)
    for f in _silence_frames(sample_rate, opus_frame_size, TAIL_MS):
        radio_service.send_tx_audio(f)
        if export_buf is not None:
            export_buf.append(f.copy())
    radio_service.ptt_up()