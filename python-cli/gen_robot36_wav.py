import sys
import os
import wave
import numpy as np
from PIL import Image
from pysstv.color import Robot36

def gen_robot36_wav(img_path: str, out_path: str):
    img = Image.open(img_path).convert('RGB')
    img = img.resize((320, 240), Image.LANCZOS)

    sr = 11025
    sstv = Robot36(img, sr, 16)
    values = np.array(list(sstv.gen_values()), dtype=np.float32)

    lead = np.zeros(int(0.5 * sr), dtype=np.float32)
    tail = np.zeros(int(0.5 * sr), dtype=np.float32)
    pcm = np.concatenate([lead, values, tail])

    pcm16 = np.clip(pcm * 32767.0, -32768, 32767).astype(np.int16)
    os.makedirs(os.path.dirname(out_path), exist_ok=True)
    with wave.open(out_path, 'wb') as w:
        w.setnchannels(1)
        w.setsampwidth(2)
        w.setframerate(sr)
        w.writeframes(pcm16.tobytes())

if __name__ == '__main__':
    if len(sys.argv) < 3:
        print('usage: gen_robot36_wav.py <IMAGE_PATH> <OUT_WAV_PATH>')
        sys.exit(1)
    gen_robot36_wav(sys.argv[1], sys.argv[2])

