import numpy as np
try:
    from opuslib import Encoder as LibEncoder, Decoder as LibDecoder, APPLICATION_VOIP as OPUS_APPLICATION_VOIP
except Exception:
    LibEncoder = None
    LibDecoder = None

try:
    from pyogg import OpusEncoder as PyoEncoder, OpusDecoder as PyoDecoder
except Exception:
    PyoEncoder = None
    PyoDecoder = None

class OpusEncoderWrapper:
    def __init__(self, sample_rate, frame_size):
        self._fs = frame_size
        if LibEncoder is not None:
            self._backend = "opuslib"
            self._enc = LibEncoder(sample_rate, 1, OPUS_APPLICATION_VOIP)
        elif PyoEncoder is not None:
            self._backend = "pyogg"
            enc = PyoEncoder()
            enc.set_application(PyoEncoder.Application.VOIP)
            enc.set_sampling_frequency(sample_rate)
            enc.set_channels(1)
            enc.set_bitrate(32000)
            enc.set_max_bandwidth(PyoEncoder.Bandwidth.NARROWBAND)
            self._enc = enc
        else:
            raise RuntimeError("No Opus backend available (install opuslib or pyogg)")

    def encode(self, frame):
        pcm = np.asarray(frame, dtype=np.float32)
        pcm16 = np.clip(pcm * 32768.0, -32768, 32767).astype(np.int16)
        if self._backend == "opuslib":
            try:
                return self._enc.encode(pcm16.tobytes(), self._fs)
            except TypeError:
                import array
                return self._enc.encode(array.array('h', pcm16.tolist()), self._fs)
        else:
            return self._enc.encode(pcm16.tobytes())

class OpusDecoderWrapper:
    def __init__(self, sample_rate):
        if LibDecoder is not None:
            self._backend = "opuslib"
            self._dec = LibDecoder(sample_rate, 1)
        elif PyoDecoder is not None:
            self._backend = "pyogg"
            dec = PyoDecoder()
            dec.set_sampling_frequency(sample_rate)
            dec.set_channels(1)
            self._dec = dec
        else:
            raise RuntimeError("No Opus backend available (install opuslib or pyogg)")

    def decode(self, data):
        if self._backend == "opuslib":
            try:
                pcm16 = self._dec.decode(data, 1920)
            except TypeError:
                pcm16 = self._dec.decode(data, 1920)
            if hasattr(pcm16, 'tobytes'):
                buf = pcm16.tobytes()
            elif isinstance(pcm16, (bytes, bytearray)):
                buf = bytes(pcm16)
            else:
                import array
                buf = array.array('h', pcm16).tobytes()
            pcm = np.frombuffer(buf, dtype=np.int16).astype(np.float32) / 32768.0
            return pcm
        else:
            pcm16 = self._dec.decode(data)
            pcm = np.frombuffer(pcm16, dtype=np.int16).astype(np.float32) / 32768.0
            return pcm