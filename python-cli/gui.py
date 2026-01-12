import threading
try:
    import tkinter as tk
    use_tk = True
except Exception:
    use_tk = False
import curses
from audio import AudioOutput, AudioInput
from radio_service import RadioService, RadioCallbacks

class Cb(RadioCallbacks):
    def __init__(self, out, set_s, set_status):
        self.out = out
        self.set_s = set_s
        self.set_status = set_status
    def smeter(self, value):
        self.set_s(value)
    def rx_audio(self, samples):
        self.out.play(samples)
    def connected(self):
        self.set_status("connected")
    def disconnected(self):
        self.set_status("disconnected")

class TkApp:
    def __init__(self):
        self.root = tk.Tk()
        self.root.title("KV4P HT")
        self.s_meter_var = tk.IntVar()
        self.status_var = tk.StringVar()
        self.freq_var = tk.StringVar()
        tk.Label(self.root, textvariable=self.status_var).pack()
        tk.Scale(self.root, from_=0, to=9, orient=tk.HORIZONTAL, variable=self.s_meter_var).pack(fill=tk.X)
        tk.Entry(self.root, textvariable=self.freq_var).pack(fill=tk.X)
        self.ptt_btn = tk.Button(self.root, text="PTT", command=self.toggle_ptt)
        self.ptt_btn.pack()
        self.out = AudioOutput(48000)
        self.out.start()
        self.inp = AudioInput(48000, 1920)
        self.cb = Cb(self.out, lambda v: self.s_meter_var.set(v), lambda s: self.status_var.set(s))
        self.svc = RadioService(self.cb, sample_rate=48000, opus_frame_size=1920)
        threading.Thread(target=self.svc.connect, daemon=True).start()
        tk.Button(self.root, text="Tune", command=self.tune).pack()
        self.ptt_on = False
    def tune(self):
        try:
            f = float(self.freq_var.get())
            self.svc.tune_simplex(f)
        except Exception:
            pass
    def toggle_ptt(self):
        if not self.ptt_on:
            self.svc.ptt_down()
            self.inp.start()
            self.ptt_on = True
            self.root.after(0, self._tx_loop)
        else:
            self.svc.ptt_up()
            self.inp.stop()
            self.ptt_on = False
    def _tx_loop(self):
        if not self.ptt_on:
            return
        frame = self.inp.read()
        self.svc.send_tx_audio(frame)
        self.root.after(40, self._tx_loop)
    def run(self):
        self.root.protocol("WM_DELETE_WINDOW", self.close)
        self.root.mainloop()
    def close(self):
        try:
            self.inp.stop()
        except Exception:
            pass
        try:
            self.out.stop()
        except Exception:
            pass
        self.svc.disconnect()
        self.root.destroy()

class CursesApp:
    def __init__(self):
        self.s_value = 0
        self.status = ""
        self.freq = ""
        self.ptt_on = False
        self.out = AudioOutput(48000)
        self.out.start()
        self.inp = AudioInput(48000, 1920)
        self.cb = Cb(self.out, self._set_s, self._set_status)
        self.svc = RadioService(self.cb, sample_rate=48000, opus_frame_size=1920)
        threading.Thread(target=self.svc.connect, daemon=True).start()
    def _set_s(self, v):
        self.s_value = v
    def _set_status(self, s):
        self.status = s
    def _tx_loop(self):
        if not self.ptt_on:
            return
        frame = self.inp.read()
        self.svc.send_tx_audio(frame)
    def run(self):
        curses.wrapper(self._main)
    def _main(self, stdscr):
        stdscr.nodelay(False)
        while True:
            stdscr.clear()
            stdscr.addstr(0, 0, "KV4P HT")
            stdscr.addstr(1, 0, f"status: {self.status}")
            stdscr.addstr(2, 0, f"S-meter: {self.s_value}")
            stdscr.addstr(3, 0, f"freq: {self.freq}")
            stdscr.addstr(5, 0, "p=PTT toggle, t=Tune, q=Quit")
            stdscr.refresh()
            ch = stdscr.getch()
            if ch in (ord('q'), ord('Q')):
                break
            if ch in (ord('p'), ord('P')):
                if not self.ptt_on:
                    self.svc.ptt_down()
                    self.inp.start()
                    self.ptt_on = True
                else:
                    self.svc.ptt_up()
                    self.inp.stop()
                    self.ptt_on = False
            if ch in (ord('t'), ord('T')):
                curses.echo()
                stdscr.addstr(7, 0, "Enter freq MHz: ")
                s = stdscr.getstr(7, 16, 32)
                curses.noecho()
                try:
                    f = float(s.decode())
                    self.freq = str(f)
                    self.svc.tune_simplex(f)
                except Exception:
                    pass
            if self.ptt_on:
                self._tx_loop()
        self.close()
    def close(self):
        try:
            self.inp.stop()
        except Exception:
            pass
        try:
            self.out.stop()
        except Exception:
            pass
        self.svc.disconnect()

if __name__ == "__main__":
    if use_tk:
        TkApp().run()
    else:
        CursesApp().run()