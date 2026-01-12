import numpy as np
import sounddevice as sd

class AudioOutput:
    def __init__(self, sample_rate):
        self._sr = sample_rate
        self._stream = sd.OutputStream(samplerate=self._sr, channels=1, dtype="float32")

    def start(self):
        self._stream.start()

    def play(self, samples):
        self._stream.write(np.asarray(samples, dtype=np.float32))

    def stop(self):
        self._stream.stop()
        self._stream.close()

class AudioInput:
    def __init__(self, sample_rate, frame_size):
        self._sr = sample_rate
        self._fs = frame_size
        self._stream = sd.InputStream(samplerate=self._sr, channels=1, dtype="float32")

    def start(self):
        self._stream.start()

    def read(self):
        data, _ = self._stream.read(self._fs)
        return data[:,0]

    def stop(self):
        self._stream.stop()
        self._stream.close()