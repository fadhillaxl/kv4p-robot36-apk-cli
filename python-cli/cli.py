import sys
import click
import numpy as np
import sounddevice as sd
from audio import AudioInput, AudioOutput
from radio_service import RadioService, RadioCallbacks
from sstv_tx import transmit_image
from sstv_rx import SSTVRecorder
from wav_tx import transmit_wav
from sstv_detect import SSTVDetector
from aprs_ax25 import build_aprs_message
from afsk1200 import modulate_afsk
from resample import to_rate

class Cb(RadioCallbacks):
    def __init__(self, out, recorder_getter, detector, monitor_getter):
        self.out = out
        self.recorder_getter = recorder_getter
        self.detector = detector
        self.monitor_getter = monitor_getter
    def smeter(self, value):
        # click.echo(f"s:{value}")
        pass
    def rx_audio(self, samples):
        self.out.play(samples)
        rec = self.recorder_getter()
        if rec:
            rec.add_samples(samples)
        if self.detector:
            self.detector.process(samples)
        if self.monitor_getter():
            lvl = float(np.mean(np.abs(samples)))
            click.echo(f"rx:{lvl:.3f}")
    def connected(self):
        click.echo("connected")
    def disconnected(self):
        click.echo("disconnected")

@click.command()
@click.option("--port", default=None)
def main(port):
    sr = 48000
    fs = 1920
    out = AudioOutput(sr)
    out.start()
    recorder = { 'obj': SSTVRecorder(sample_rate=sr) }
    monitor = { 'on': False }
    tx_export = { 'on': False }
    detector = SSTVDetector(lambda: recorder['obj'], sample_rate=sr, finalize_cb=lambda p: click.echo(f"sstv saved: {p}"))
    cb = Cb(out, lambda: recorder['obj'], detector, lambda: monitor['on'])
    svc = RadioService(cb, sample_rate=sr, opus_frame_size=fs)
    svc.connect(port=port)
    inp = AudioInput(sr, fs)
    aprs_cfg = { 'callsign': '', 'path': ['WIDE1-1','WIDE2-1'], 'msgnum': 0 }
    running = True
    while running:
        cmd = sys.stdin.readline()
        if not cmd:
            break
        cmd = cmd.strip()
        if cmd == "quit":
            running = False
        elif cmd.startswith("sstv tx "):
            parts = cmd.split(" ", 2)
            if len(parts) < 3:
                click.echo("error: usage: sstv tx <IMAGE_PATH>")
            else:
                path = parts[2]
                buf = [] if tx_export['on'] else None
                transmit_image(path, svc, sample_rate=sr, opus_frame_size=fs, export_buf=buf)
                if tx_export['on'] and buf is not None:
                    import os, wave, time
                    os.makedirs(os.path.join('out','tx'), exist_ok=True)
                    ts = time.strftime('%Y%m%d_%H%M%S', time.localtime())
                    p = os.path.join('out','tx', f'{ts}.wav')
                    import numpy as np
                    pcm = np.concatenate(buf) if len(buf) else np.array([], dtype=np.float32)
                    pcm16 = np.clip(pcm * 32767.0, -32768, 32767).astype(np.int16)
                    with wave.open(p, 'wb') as w:
                        w.setnchannels(1); w.setsampwidth(2); w.setframerate(sr); w.writeframes(pcm16.tobytes())
                    click.echo(f"tx saved: {p}")
                click.echo("sstv tx done")
        elif cmd.startswith("wav tx "):
            parts = cmd.split(" ", 2)
            if len(parts) < 3:
                click.echo("error: usage: wav tx <WAV_PATH>")
            else:
                path = parts[2]
                buf = [] if tx_export['on'] else None
                transmit_wav(path, svc, sample_rate=sr, opus_frame_size=fs, export_buf=buf)
                if tx_export['on'] and buf is not None:
                    import os, wave, time
                    os.makedirs(os.path.join('out','tx'), exist_ok=True)
                    ts = time.strftime('%Y%m%d_%H%M%S', time.localtime())
                    p = os.path.join('out','tx', f'{ts}.wav')
                    import numpy as np
                    pcm = np.concatenate(buf) if len(buf) else np.array([], dtype=np.float32)
                    pcm16 = np.clip(pcm * 32767.0, -32768, 32767).astype(np.int16)
                    with wave.open(p, 'wb') as w:
                        w.setnchannels(1); w.setsampwidth(2); w.setframerate(sr); w.writeframes(pcm16.tobytes())
                    click.echo(f"tx saved: {p}")
                click.echo("wav tx done")
        elif cmd == "sstv rx start":
            if recorder['obj'] is None:
                recorder['obj'] = SSTVRecorder(sample_rate=sr)
            recorder['obj'].start()
            click.echo("sstv rx started")
        elif cmd == "sstv rx stop":
            if recorder['obj']:
                out_path = recorder['obj'].stop()
                click.echo(f"sstv saved: {out_path}")
                recorder['obj'] = None
        elif cmd.startswith("freq "):
            try:
                f = float(cmd.split()[1])
                svc.tune_simplex(f)
            except Exception:
                pass
        elif cmd == "ptt on":
            svc.ptt_down()
            # inp.start() # Removing this as it blocks the loop
            # for _ in range(10):
            #     frame = inp.read()
            #     svc.send_tx_audio(frame)
            # Sending a 1kHz test tone instead to verify audio path
            sr = 48000
            t = np.arange(sr * 2) / sr
            tone = 0.5 * np.sin(2 * np.pi * 1000 * t).astype(np.float32)
            # send in chunks
            chunk_size = 1920
            for i in range(0, len(tone), chunk_size):
                svc.send_tx_audio(tone[i:i+chunk_size])
                __import__('time').sleep(chunk_size/sr)
            svc.ptt_up()
            click.echo("ptt test tone sent")
        elif cmd == "ptt off":
            svc.ptt_up()
            # inp.stop()
        elif cmd == "status":
            click.echo("ok")
        elif cmd.startswith("aprs callsign "):
            aprs_cfg['callsign'] = cmd.split()[2].upper()
            click.echo(f"aprs callsign set {aprs_cfg['callsign']}")
        elif cmd.startswith("aprs path "):
            parts = cmd.split(" ", 2)
            aprs_cfg['path'] = [p.strip() for p in parts[2].split(',')]
            click.echo(f"aprs path set {','.join(aprs_cfg['path'])}")
        elif cmd.startswith("aprs send "):
            parts = cmd.split(" ", 3)
            if len(parts) < 4:
                click.echo("error: usage: aprs send <DESTINATION> <MESSAGE>")
            else:
                to = parts[2].upper()
                text = parts[3]
                if not aprs_cfg['callsign']:
                    click.echo("aprs callsign not set")
                else:
                    frm = build_aprs_message(aprs_cfg['callsign'], to, aprs_cfg['path'], text, aprs_cfg['msgnum'])
                    aprs_cfg['msgnum'] = (aprs_cfg['msgnum'] + 1) % 100000
                    pcm = modulate_afsk(frm, fs=11025)
                    pcm = np.clip(pcm * 0.6, -1.0, 1.0)
                    pcm48 = to_rate(pcm, 11025, sr)
                    svc.ptt_down()
                    t0 = __import__('time').monotonic()
                    n = 0
                    buf = [] if tx_export['on'] else None
                    for i in range(0, len(pcm48), fs):
                        frame = pcm48[i:i+fs]
                        if len(frame) < fs:
                            pad = np.zeros(fs - len(frame), dtype=np.float32)
                            frame = np.concatenate([frame, pad])
                        svc.send_tx_audio(frame)
                        if buf is not None:
                            buf.append(frame.copy())
                        n += 1
                        target = t0 + n * 0.040
                        now = __import__('time').monotonic()
                        sl = target - now
                        if sl > 0:
                            __import__('time').sleep(sl)
                    svc.ptt_up()
                    if tx_export['on'] and buf is not None:
                        import os, wave, time
                        os.makedirs(os.path.join('out','tx'), exist_ok=True)
                        ts = time.strftime('%Y%m%d_%H%M%S', time.localtime())
                        p = os.path.join('out','tx', f'{ts}.wav')
                        import numpy as np
                        pcm = np.concatenate(buf) if len(buf) else np.array([], dtype=np.float32)
                        pcm16 = np.clip(pcm * 32767.0, -32768, 32767).astype(np.int16)
                        with wave.open(p, 'wb') as w:
                            w.setnchannels(1); w.setsampwidth(2); w.setframerate(sr); w.writeframes(pcm16.tobytes())
                        click.echo(f"tx saved: {p}")
                    click.echo("aprs sent")
        elif cmd == "monitor on":
            monitor['on'] = True
            click.echo("monitor on")
        elif cmd == "monitor off":
            monitor['on'] = False
            click.echo("monitor off")
        elif cmd == "tx export on":
            tx_export['on'] = True
            click.echo("tx export on")
        elif cmd == "tx export off":
            tx_export['on'] = False
            click.echo("tx export off")
        elif cmd == "device list":
            devs = sd.query_devices()
            for i, d in enumerate(devs):
                click.echo(f"{i}: {d['name']} in:{d['max_input_channels']} out:{d['max_output_channels']}")
        elif cmd.startswith("device out "):
            try:
                idx = int(cmd.split()[2])
                sd.default.device = (sd.default.device[0], idx)
                click.echo(f"device out set {idx}")
            except Exception:
                click.echo("device out error")
    try:
        inp.stop()
    except Exception:
        pass
    try:
        out.stop()
    except Exception:
        pass
    svc.disconnect()

if __name__ == "__main__":
    main()