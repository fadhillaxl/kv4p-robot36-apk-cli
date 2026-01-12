import threading
import time
import serial
from serial.tools import list_ports
from protocol import FrameParser, Sender, RCV
from opus_codec import OpusEncoderWrapper, OpusDecoderWrapper

class RadioCallbacks:
    def smeter(self, value):
        pass
    def rx_audio(self, samples):
        pass
    def hello(self):
        pass
    def version(self, payload):
        pass
    def window_update(self, size):
        pass
    def connected(self):
        pass
    def disconnected(self):
        pass

class RadioService:
    def __init__(self, callbacks=None, sample_rate=48000, opus_frame_size=1920):
        self._callbacks = callbacks or RadioCallbacks()
        self._sr = sample_rate
        self._fs = opus_frame_size
        self._port = None
        self._ser = None
        self._sender = None
        self._parser = FrameParser(self._on_cmd)
        self._rx_thread = None
        self._running = False
        self._enc = OpusEncoderWrapper(self._sr, self._fs)
        self._dec = OpusDecoderWrapper(self._sr)

    def _write(self, b):
        if self._ser:
            self._ser.write(b)

    def connect(self, port=None):
        self._port = port or self._find_port()
        self._ser = serial.Serial(self._port, 115200, timeout=0)
        self._sender = Sender(self._write)
        self._running = True
        self._rx_thread = threading.Thread(target=self._rx_loop, daemon=True)
        self._rx_thread.start()
        self._callbacks.connected()

    def disconnect(self):
        self._running = False
        if self._rx_thread:
            self._rx_thread.join(timeout=1)
        if self._ser:
            try:
                self._ser.close()
            except Exception:
                pass
        self._callbacks.disconnected()

    def _rx_loop(self):
        while self._running:
            try:
                data = self._ser.read(4096)
                if data:
                    self._parser.process(data)
                else:
                    time.sleep(0.01)
            except Exception:
                break

    def _on_cmd(self, cmd, param, plen):
        if cmd == RCV["RX_AUDIO"]:
            samples = self._dec.decode(param[:plen])
            self._callbacks.rx_audio(samples)
        elif cmd == RCV["SMETER_REPORT"]:
            v = param[0] & 0xFF if plen == 1 else 0
            self._callbacks.smeter(self._calc_s9(v))
        elif cmd == RCV["HELLO"]:
            self._callbacks.hello()
        elif cmd == RCV["VERSION"]:
            self._callbacks.version(param[:plen])
        elif cmd == RCV["WINDOW_UPDATE"]:
            if plen == 4:
                size = int.from_bytes(param[:4], byteorder="little", signed=False)
                self._sender.enlarge_flow_control_window(size)
                self._callbacks.window_update(size)

    def _calc_s9(self, v):
        import math
        r = 9.73 * math.log(0.0297 * v) - 1.88
        return max(1, min(9, round(r)))

    def send_tx_audio(self, samples):
        data = self._enc.encode(samples)
        self._sender.tx_audio(data)

    def ptt_down(self):
        self._sender.ptt_down()

    def ptt_up(self):
        self._sender.ptt_up()

    def tune_simplex(self, freq_mhz, bandwidth_25k=True, squelch=0):
        bw = 0x01 if bandwidth_25k else 0x00
        f = float(freq_mhz)
        self._sender.group(bw, f, f, 0, squelch, 0)

    def _find_port(self):
        ports = list_ports.comports()
        for p in ports:
            if p.vid in (4292, 6790):
                return p.device
        return ports[0].device if ports else None