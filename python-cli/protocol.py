import struct
import threading

COMMAND_DELIMITER = bytes([0xDE, 0xAD, 0xBE, 0xEF])
PROTO_MTU = 2048

SND = {
    "HOST_PTT_DOWN": 0x01,
    "HOST_PTT_UP": 0x02,
    "HOST_GROUP": 0x03,
    "HOST_FILTERS": 0x04,
    "HOST_STOP": 0x05,
    "HOST_CONFIG": 0x06,
    "HOST_TX_AUDIO": 0x07,
    "HOST_HL": 0x08,
    "HOST_RSSI": 0x09,
}

RCV = {
    "SMETER_REPORT": 0x53,
    "PHYS_PTT_DOWN": 0x44,
    "PHYS_PTT_UP": 0x55,
    "DEBUG_INFO": 0x01,
    "DEBUG_ERROR": 0x02,
    "DEBUG_WARN": 0x03,
    "DEBUG_DEBUG": 0x04,
    "DEBUG_TRACE": 0x05,
    "HELLO": 0x06,
    "RX_AUDIO": 0x07,
    "VERSION": 0x08,
    "WINDOW_UPDATE": 0x09,
}

class Sender:
    def __init__(self, write_fn):
        self._write = write_fn
        self._window = 1024
        self._lock = threading.RLock()
        self._cond = threading.Condition(self._lock)

    def _send(self, cmd, param):
        plen = 0 if param is None else len(param)
        frame = bytearray()
        frame += COMMAND_DELIMITER
        frame.append(cmd & 0xFF)
        frame += struct.pack("<H", plen)
        if plen:
            frame += param
        size = len(frame)
        with self._lock:
            while self._window <= size:
                self._cond.wait()
            self._write(bytes(frame))
            self._window -= size

    def ptt_down(self):
        self._send(SND["HOST_PTT_DOWN"], None)

    def ptt_up(self):
        self._send(SND["HOST_PTT_UP"], None)

    def group(self, bw, freq_tx, freq_rx, ctcss_tx, squelch, ctcss_rx):
        payload = struct.pack("<BffBBB", bw, freq_tx, freq_rx, ctcss_tx & 0xFF, squelch & 0xFF, ctcss_rx & 0xFF)
        self._send(SND["HOST_GROUP"], payload)

    def filters(self, pre, high, low):
        b = 0
        if pre: b |= 0x01
        if high: b |= 0x02
        if low: b |= 0x04
        self._send(SND["HOST_FILTERS"], bytes([b]))

    def stop(self):
        self._send(SND["HOST_STOP"], None)

    def config(self, is_high):
        self._send(SND["HOST_CONFIG"], bytes([0x01 if is_high else 0x00]))

    def tx_audio(self, audio_bytes):
        self._send(SND["HOST_TX_AUDIO"], audio_bytes)

    def set_high_power(self, is_high_power):
        self._send(SND["HOST_HL"], bytes([0x01 if is_high_power else 0x00]))

    def set_rssi(self, on):
        self._send(SND["HOST_RSSI"], bytes([0x01 if on else 0x00]))

    def set_flow_control_window(self, size):
        with self._lock:
            self._window = size
            self._cond.notify_all()

    def enlarge_flow_control_window(self, size):
        with self._lock:
            self._window += size
            self._cond.notify_all()

class FrameParser:
    def __init__(self, on_command):
        self._on = on_command
        self._match = 0
        self._cmd = 0
        self._plen = 0
        self._params = bytearray(PROTO_MTU)
        self._pidx = 0

    def process(self, data):
        for b in data:
            self._process_byte(b if isinstance(b, int) else b[0])

    def _process_byte(self, b):
        if self._match < len(COMMAND_DELIMITER):
            self._match = self._match + 1 if b == COMMAND_DELIMITER[self._match] else 0
        elif self._match == len(COMMAND_DELIMITER):
            self._cmd = b
            self._match += 1
        elif self._match == len(COMMAND_DELIMITER) + 1:
            self._plen = b & 0xFF
            self._match += 1
        elif self._match == len(COMMAND_DELIMITER) + 2:
            self._plen = ((b & 0xFF) << 8) | self._plen
            self._pidx = 0
            self._match += 1
            if self._plen == 0:
                self._dispatch()
                self._reset()
            if self._plen > len(self._params):
                self._reset()
        else:
            if self._pidx < self._plen:
                self._params[self._pidx] = b
                self._pidx += 1
            self._match += 1
            if self._pidx == self._plen:
                self._dispatch()
                self._reset()

    def _dispatch(self):
        self._on(self._cmd, bytes(self._params[:self._plen]), self._plen)

    def _reset(self):
        self._match = 0
        self._pidx = 0
        self._plen = 0