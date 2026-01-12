import numpy as np
from scipy.signal import resample_poly

def to_rate(signal: np.ndarray, src_rate: int, dst_rate: int) -> np.ndarray:
    g = np.gcd(src_rate, dst_rate)
    up = dst_rate // g
    down = src_rate // g
    y = resample_poly(signal.astype(np.float32), up, down)
    return y.astype(np.float32)