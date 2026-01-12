from PIL import Image
import numpy as np
from pysstv.color import Robot36
from resample import to_rate
import time

LEAD_MS = 500
TAIL_MS = 500

def _silence_frames(sample_rate, frame_size, duration_ms):
    count = int(duration_ms / 40)
    frame = np.zeros(frame_size, dtype=np.float32)
    return [frame.copy() for _ in range(count)]

def transmit_image(image_path, radio_service, sample_rate=48000, opus_frame_size=1920, vox=False, export_buf=None):
    img = Image.open(image_path).convert('RGB')
    img = img.resize((320, 240), Image.LANCZOS)
    sstv_rate = 11025
    sstv = Robot36(img, sstv_rate, 16)
    values = np.array(list(sstv.gen_values()), dtype=np.float32)
    values = to_rate(values, sstv_rate, sample_rate)
    values = np.clip(values * 0.6, -1.0, 1.0)

    radio_service.ptt_down()
    for f in _silence_frames(sample_rate, opus_frame_size, LEAD_MS):
        radio_service.send_tx_audio(f)
        if export_buf is not None:
            export_buf.append(f.copy())

    idx = 0
    total = len(values)
    t0 = time.monotonic()
    n = 0
    while idx < total:
        frame = values[idx:idx+opus_frame_size]
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