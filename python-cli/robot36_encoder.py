"""
Custom Robot36 SSTV Encoder - Compatible with KV4PHT Java Decoder

This encoder addresses several compatibility issues with PySSTV's Robot36:
1. Correct chroma subsampling (160 pixels, not 320)
2. Correct separator frequencies for even/odd line detection
3. Proper YCbCr encoding matching the Java decoder's YUV2RGB conversion
"""

import numpy as np
from PIL import Image
import math

# SSTV Frequencies (Hz)
FREQ_SYNC = 1200
FREQ_VIS_BIT1 = 1100
FREQ_VIS_BIT0 = 1300
FREQ_BLACK = 1500
FREQ_WHITE = 2300
FREQ_VIS_START = 1900  # Leader tone

# Robot36 Parameters
VIS_CODE = 0x08  # 8 decimal
WIDTH = 320
HEIGHT = 240

# Timing (milliseconds)
SYNC_MS = 9.0
SYNC_PORCH_MS = 3.0
Y_SCAN_MS = 88.0
SEPARATOR_MS = 4.5
PORCH_MS = 1.5
C_SCAN_MS = 44.0

# Separator frequencies (critical for decoder even/odd detection)
# Even lines (0, 2, 4...): send Cr, separator = 1500 Hz (black) -> separator < 0 after offset
# Odd lines (1, 3, 5...): send Cb, separator = 2300 Hz (white) -> separator > 0 after offset
SEPARATOR_EVEN = FREQ_BLACK  # 1500 Hz for even lines (Cr)
SEPARATOR_ODD = FREQ_WHITE   # 2300 Hz for odd lines (Cb)


class Robot36Encoder:
    def __init__(self, image, sample_rate=11025):
        """
        Initialize Robot36 encoder.
        
        Args:
            image: PIL Image or path to image file
            sample_rate: Output sample rate (default 11025 Hz - native SSTV rate)
        """
        if isinstance(image, str):
            image = Image.open(image)
        
        # Resize to 320x240
        self.image = image.convert('RGB').resize((WIDTH, HEIGHT), Image.LANCZOS)
        self.sample_rate = sample_rate
        
        # Convert to YCbCr
        self.ycbcr = self.image.convert('YCbCr')
        self.pixels = self.ycbcr.load()
        
        # Phase accumulator for continuous phase
        self.phase = 0.0
        
    def _ms_to_samples(self, ms):
        """Convert milliseconds to number of samples."""
        return int(ms * self.sample_rate / 1000.0)
    
    def _generate_tone(self, freq, duration_ms):
        """Generate a single frequency tone with phase continuity."""
        n_samples = self._ms_to_samples(duration_ms)
        samples = np.zeros(n_samples)
        phase_inc = 2 * np.pi * freq / self.sample_rate
        
        for i in range(n_samples):
            samples[i] = np.sin(self.phase)
            self.phase += phase_inc
            
        # Keep phase in reasonable range
        self.phase = self.phase % (2 * np.pi)
        return samples
    
    def _value_to_freq(self, value):
        """Convert 0-255 value to frequency (1500-2300 Hz)."""
        return FREQ_BLACK + (FREQ_WHITE - FREQ_BLACK) * value / 255.0
    
    def _generate_header(self):
        """Generate VIS header with leader tones."""
        samples = []
        
        # Leader tone: 300ms of 1900 Hz
        samples.append(self._generate_tone(FREQ_VIS_START, 300))
        
        # Break: 10ms of 1200 Hz
        samples.append(self._generate_tone(FREQ_SYNC, 10))
        
        # Leader tone: 300ms of 1900 Hz
        samples.append(self._generate_tone(FREQ_VIS_START, 300))
        
        # VIS start bit: 30ms of 1200 Hz
        samples.append(self._generate_tone(FREQ_SYNC, 30))
        
        # VIS code bits (LSB first, 8 bits)
        vis = VIS_CODE
        for i in range(8):
            bit = (vis >> i) & 1
            freq = FREQ_VIS_BIT1 if bit == 1 else FREQ_VIS_BIT0
            samples.append(self._generate_tone(freq, 30))
        
        # VIS stop bit: 30ms of 1200 Hz
        samples.append(self._generate_tone(FREQ_SYNC, 30))
        
        return np.concatenate(samples)
    
    def _generate_scanline(self, line_num):
        """
        Generate a single scanline.
        
        Robot36 format per line:
        - Sync pulse: 9ms at 1200 Hz
        - Sync porch: 3ms at 1500 Hz
        - Y (luminance): 88ms, 320 pixels
        - Separator: 4.5ms at 1500 Hz (even) or 2300 Hz (odd)
        - Porch: 1.5ms at 1900 Hz
        - C (chrominance): 44ms, 160 pixels (Cr for even, Cb for odd)
        """
        samples = []
        
        # Sync pulse
        samples.append(self._generate_tone(FREQ_SYNC, SYNC_MS))
        
        # Sync porch
        samples.append(self._generate_tone(FREQ_BLACK, SYNC_PORCH_MS))
        
        # Y (luminance) - 320 pixels in 88ms
        y_total_samples = self._ms_to_samples(Y_SCAN_MS)
        y_audio = np.zeros(y_total_samples)
        samples_per_pixel = y_total_samples / WIDTH
        
        sample_idx = 0
        for x in range(WIDTH):
            y_val = self.pixels[x, line_num][0]  # Y component
            freq = self._value_to_freq(y_val)
            phase_inc = 2 * np.pi * freq / self.sample_rate
            
            # Number of samples for this pixel
            end_sample = int((x + 1) * samples_per_pixel)
            while sample_idx < end_sample and sample_idx < y_total_samples:
                y_audio[sample_idx] = np.sin(self.phase)
                self.phase += phase_inc
                sample_idx += 1
        
        self.phase = self.phase % (2 * np.pi)
        samples.append(y_audio)
        
        # Separator - frequency determines even/odd for decoder
        is_even = (line_num % 2 == 0)
        separator_freq = SEPARATOR_EVEN if is_even else SEPARATOR_ODD
        samples.append(self._generate_tone(separator_freq, SEPARATOR_MS))
        
        # Porch
        samples.append(self._generate_tone(FREQ_VIS_START, PORCH_MS))
        
        # C (chrominance) - 160 pixels in 44ms
        # Even lines: Cr (index 2 in YCbCr)
        # Odd lines: Cb (index 1 in YCbCr)
        c_total_samples = self._ms_to_samples(C_SCAN_MS)
        c_audio = np.zeros(c_total_samples)
        c_samples_per_pixel = c_total_samples / 160
        
        # Chroma is subsampled - average pairs of pixels
        chroma_idx = 2 if is_even else 1  # Cr for even, Cb for odd
        
        sample_idx = 0
        for cx in range(160):
            # Average two horizontal pixels for chroma subsampling
            x1 = cx * 2
            x2 = min(cx * 2 + 1, WIDTH - 1)
            c_val = (self.pixels[x1, line_num][chroma_idx] + 
                     self.pixels[x2, line_num][chroma_idx]) // 2
            freq = self._value_to_freq(c_val)
            phase_inc = 2 * np.pi * freq / self.sample_rate
            
            end_sample = int((cx + 1) * c_samples_per_pixel)
            while sample_idx < end_sample and sample_idx < c_total_samples:
                c_audio[sample_idx] = np.sin(self.phase)
                self.phase += phase_inc
                sample_idx += 1
        
        self.phase = self.phase % (2 * np.pi)
        samples.append(c_audio)
        
        return np.concatenate(samples)
    
    def generate(self):
        """Generate complete Robot36 SSTV audio."""
        samples = [self._generate_header()]
        
        for line in range(HEIGHT):
            samples.append(self._generate_scanline(line))
            
        return np.concatenate(samples).astype(np.float32)
    
    def generate_resampled(self, target_rate=48000):
        """Generate and resample to target sample rate."""
        audio = self.generate()
        
        if target_rate != self.sample_rate:
            from resample import to_rate
            audio = to_rate(audio, self.sample_rate, target_rate)
        
        return audio


def encode_image(image_path, sample_rate=11025):
    """
    Convenience function to encode an image.
    
    Args:
        image_path: Path to input image
        sample_rate: Output sample rate (default 11025)
        
    Returns:
        numpy array of float32 audio samples
    """
    encoder = Robot36Encoder(image_path, sample_rate)
    return encoder.generate()


def save_wav(audio, filename, sample_rate=11025):
    """Save audio to WAV file."""
    import scipy.io.wavfile
    pcm = (audio * 32767).astype(np.int16)
    scipy.io.wavfile.write(filename, sample_rate, pcm)


if __name__ == "__main__":
    import sys
    
    if len(sys.argv) < 2:
        print("Usage: python robot36_encoder.py <image_path> [output.wav]")
        sys.exit(1)
    
    image_path = sys.argv[1]
    output_path = sys.argv[2] if len(sys.argv) > 2 else "robot36_output.wav"
    
    print(f"Encoding {image_path}...")
    encoder = Robot36Encoder(image_path)
    audio = encoder.generate()
    
    print(f"Generated {len(audio)} samples at {encoder.sample_rate} Hz")
    print(f"Duration: {len(audio) / encoder.sample_rate:.2f} seconds")
    
    save_wav(audio, output_path, encoder.sample_rate)
    print(f"Saved to {output_path}")
