import os
import wave
import numpy as np
import subprocess
from datetime import datetime
from resample import to_rate

class SSTVRecorder:
    def __init__(self, sample_rate=48000):
        self.sample_rate = sample_rate
        self._buf = []
        self._recording = False

    def start(self):
        self._buf = []
        self._recording = True

    def stop(self):
        self._recording = False
        return self._write_and_decode()

    def add_samples(self, samples):
        if self._recording:
            self._buf.append(np.asarray(samples, dtype=np.float32))

    def _write_and_decode(self):
        if not self._buf:
            return None
        pcm = np.concatenate(self._buf)
        target_rate = 11025
        pcm = to_rate(pcm, self.sample_rate, target_rate)
        pcm16 = np.clip(pcm * 32767.0, -32768, 32767).astype(np.int16)
        out_dir = os.path.join('out', 'sstv')
        os.makedirs(out_dir, exist_ok=True)
        ts = datetime.utcnow().strftime('%Y%m%d_%H%M%S')
        wav_path = os.path.join(out_dir, f'{ts}.wav')
        img_path = os.path.join(out_dir, f'{ts}.png')
        with wave.open(wav_path, 'wb') as w:
            w.setnchannels(1)
            w.setsampwidth(2)
            w.setframerate(target_rate)
            w.writeframes(pcm16.tobytes())
        try:
            subprocess.run(['robot36', wav_path, img_path], check=True)
            return img_path
        except Exception:
            return wav_path