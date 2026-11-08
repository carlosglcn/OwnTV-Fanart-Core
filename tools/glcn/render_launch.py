"""glcn: the launch screen's backdrop — the icon's sunset as a wide landscape (night sky, a low sun,
layered ridges in haze). Original artwork, drawn from numbers; run from the repo root:
    python3 tools/glcn/render_launch.py [preview.png]"""
import math, os, sys
import numpy as np
from PIL import Image, ImageFilter

W, H = 3840, 2160          # master; the app ships it at the TV's UI size (1920 × 1080)
OUT = 'core/src/main/res/drawable-nodpi/glcn_launch_backdrop.webp'
rng = np.random.default_rng(7)


def lerp_stops(t, stops):
    t = np.clip(t, 0, 1)
    out = np.zeros(t.shape + (3,), np.float32)
    for (t0, c0), (t1, c1) in zip(stops, stops[1:]):
        m = (t >= t0) & (t <= t1)
        k = ((t - t0) / (t1 - t0))[m][:, None]
        k = k * k * (3 - 2 * k)  # smoothstep between stops: no visible seams
        out[m] = np.array(c0) * (1 - k) + np.array(c1) * k
    return out


def ridge(xs, base, amp, seed, octaves=6, rough=0.52):
    """A mountain line: summed sines of growing frequency (1-D fractal), y in 0..1."""
    r = np.random.default_rng(seed)
    y = np.zeros_like(xs)
    a, f = 1.0, 1.0
    for _ in range(octaves):
        ph = r.uniform(0, 2 * math.pi)
        y += a * np.sin(xs * f * 2 * math.pi * r.uniform(0.8, 1.3) + ph)
        a *= rough; f *= 2.1
    y = y / np.abs(y).max()
    return base - amp * y


def render():
    yy, xx = np.mgrid[0:H, 0:W].astype(np.float32)
    u, v = xx / W, yy / H
    horizon = 0.74
    # Sky: deep night at the top, violet, rose, then warm gold at the horizon (the icon's palette).
    sky = lerp_stops(v / horizon, [
        (0.00, (11, 10, 30)), (0.38, (34, 24, 78)), (0.66, (110, 44, 112)),
        (0.84, (208, 82, 118)), (0.95, (250, 146, 96)), (1.00, (255, 192, 120))])
    # The glow of a sun just set, behind where the logo sits (the logo carries the sun itself).
    sx, sy, sr = 0.5, 0.50, 0.0
    d = np.sqrt(((u - sx) * W / H) ** 2 + (v - sy) ** 2)
    glow = np.exp(-(d / 0.34) ** 2)[..., None]
    sky = sky * (1 - 0.55 * glow) + np.array((255, 176, 120)) * 0.55 * glow
    halo = np.exp(-(d / 0.16) ** 2)[..., None]
    sky = sky * (1 - 0.28 * halo) + np.array((255, 200, 160)) * 0.28 * halo
    # Stars, only high in the sky, fading towards the glow.
    stars = np.zeros((H, W), np.float32)
    n = 1400
    px, py = rng.integers(0, W, n), (rng.random(n) ** 1.8 * H * 0.42).astype(int)
    stars[py, px] = rng.uniform(0.25, 1.0, n)
    stars = np.array(Image.fromarray((stars * 255).astype(np.uint8)).filter(ImageFilter.GaussianBlur(1.6)), np.float32) / 255 * 3.2
    stars *= np.clip(1 - v / 0.5, 0, 1)
    sky = sky + stars[..., None] * np.array((235, 228, 255))
    img = sky
    # Ridges: far ones pale with haze, near ones dark plum (as in the icon).
    xs = np.linspace(0, 1, W, dtype=np.float32)
    layers = [
        (horizon + 0.00, 0.035, 11, (132, 58, 116), 0.25),
        (horizon + 0.05, 0.050, 23, (94, 40, 100), 0.12),
        (horizon + 0.11, 0.060, 37, (60, 28, 82), 0.05),
        (horizon + 0.18, 0.055, 51, (34, 17, 54), 0.0),
        (horizon + 0.25, 0.040, 67, (16, 9, 30), 0.0),
    ]
    for base, amp, seed, col, haze in layers:
        top = ridge(xs, base, amp, seed)[None, :]
        inside = np.clip((v - top) * H / 1.5, 0, 1)[..., None]
        # A touch of the sky's glow on the far ridges, darker towards their feet.
        shade = np.clip((v - top) / 0.25, 0, 1)[..., None]
        c = np.array(col, np.float32) * (1 - 0.35 * shade) + img * haze * (1 - shade)
        img = img * (1 - inside) + c * inside
    # Soft vignette, so the edges sink and the middle (the logo) holds the eye.
    vig = 1 - 0.38 * np.clip(((u - 0.5) ** 2 * 1.4 + (v - 0.5) ** 2) / 0.5, 0, 1) ** 1.3
    img = img * vig[..., None]
    # Fine grain: hides 8-bit banding in the long gradients.
    img += rng.normal(0, 1.1, img.shape[:2])[..., None]
    return Image.fromarray(np.clip(img, 0, 255).astype(np.uint8), 'RGB')


if __name__ == '__main__':
    master = render()
    small = master.resize((1920, 1080), Image.LANCZOS)
    os.makedirs(os.path.dirname(OUT), exist_ok=True)
    small.save(OUT, 'WEBP', quality=90, method=6)
    if len(sys.argv) > 1:
        master.save(sys.argv[1])
    # The mark at the size the launch screen draws it (560 of 1920, twice for sharpness on 4K panels).
    sys.path.insert(0, os.path.dirname(__file__))
    import render_icon
    render_icon.foreground().resize((1120, 1120), Image.LANCZOS).save(
        'core/src/main/res/drawable-nodpi/glcn_launch_mark.png', optimize=True)
    print('done', os.path.getsize(OUT) // 1024, 'KB')
