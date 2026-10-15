"""glcn: draws the OwnTV Fanart icon (a picture frame holding a sunset scene and a play mark) and the
TV banner. Original artwork; run from the repo root:  python3 tools/glcn/render_icon.py"""
import math, os
from PIL import Image, ImageDraw, ImageFilter, ImageFont

RES = 'core/src/main/res'
S = 1728  # master size of the 108 dp foreground canvas

def lerp(a, b, t): return tuple(int(a[i] + (b[i] - a[i]) * t) for i in range(len(a)))

def vgrad(w, h, stops):
    im = Image.new('RGBA', (w, h))
    px = im.load()
    for y in range(h):
        t = y / (h - 1)
        for k in range(len(stops) - 1):
            (t0, c0), (t1, c1) = stops[k], stops[k + 1]
            if t0 <= t <= t1:
                c = lerp(c0, c1, (t - t0) / (t1 - t0)); break
        for x in range(w): px[x, y] = c
    return im

def scene(w, h, sun=(0.66, 0.58), sun_r=0.17):
    """Sunset fanart: sky gradient, a glowing sun, two mountain ridges."""
    sky = vgrad(w, h, [(0, (40, 30, 92, 255)), (0.45, (214, 76, 120, 255)), (0.75, (255, 150, 84, 255)), (1, (255, 196, 110, 255))])
    glow = Image.new('RGBA', (w, h), (0, 0, 0, 0))
    g = ImageDraw.Draw(glow)
    cx, cy, r = int(w * sun[0]), int(h * sun[1]), int(w * sun_r)
    g.ellipse([cx - r * 2, cy - r * 2, cx + r * 2, cy + r * 2], fill=(255, 210, 140, 110))
    glow = glow.filter(ImageFilter.GaussianBlur(r * 0.6))
    sky.alpha_composite(glow)
    d = ImageDraw.Draw(sky)
    d.ellipse([cx - r, cy - r, cx + r, cy + r], fill=(255, 236, 190, 255))
    d.polygon([(0, h * 0.70), (w * 0.22, h * 0.52), (w * 0.42, h * 0.68), (w * 0.62, h * 0.56), (w * 0.85, h * 0.72), (w, h * 0.62), (w, h), (0, h)], fill=(92, 40, 96, 255))
    d.polygon([(0, h * 0.84), (w * 0.3, h * 0.70), (w * 0.55, h * 0.83), (w * 0.8, h * 0.74), (w, h * 0.86), (w, h), (0, h)], fill=(46, 22, 64, 255))
    return sky

def rounded_mask(w, h, r):
    m = Image.new('L', (w, h), 0)
    ImageDraw.Draw(m).rounded_rectangle([0, 0, w - 1, h - 1], r, fill=255)
    return m

def play(d, cx, cy, size, fill):
    pts = [(cx - size * 0.38, cy - size * 0.5), (cx - size * 0.38, cy + size * 0.5), (cx + size * 0.5, cy)]
    d.polygon(pts, fill=fill)

def rounded_triangle_mask(size, pts, radius):
    """A play triangle with soft corners: draw big, blur, threshold."""
    m = Image.new('L', size, 0)
    ImageDraw.Draw(m).polygon(pts, fill=255)
    m = m.filter(ImageFilter.GaussianBlur(radius))
    return m.point(lambda v: 255 if v > 128 else 0).filter(ImageFilter.GaussianBlur(2))

def foreground():
    """The play mark, large, with the fanart sunset inside it and a cream rim."""
    im = Image.new('RGBA', (S, S), (0, 0, 0, 0))
    c = S / 2
    h = S * 0.50           # triangle height (inside the 66/108 safe zone)
    w = h * 0.92
    cx = c + w * 0.10      # optical centre of a play mark sits a little right
    pts = [(cx - w / 2, c - h / 2), (cx - w / 2, c + h / 2), (cx + w / 2, c)]
    rim = rounded_triangle_mask((S, S), [(x + (x - cx) * 0.10, y + (y - c) * 0.10) for x, y in pts], 40)
    inner = rounded_triangle_mask((S, S), pts, 40)
    # Soft shadow.
    sh = Image.new('RGBA', (S, S), (0, 0, 0, 0))
    sh.putalpha(rim.point(lambda v: v * 0.5))
    sh = sh.filter(ImageFilter.GaussianBlur(40))
    shifted = Image.new('RGBA', (S, S), (0, 0, 0, 0)); shifted.paste(sh, (0, 36), sh)
    im.alpha_composite(shifted)
    # Cream rim.
    cream = Image.new('RGBA', (S, S), (250, 238, 214, 255))
    im.paste(cream, (0, 0), rim)
    # Sunset inside.
    x0, y0 = int(cx - w / 2), int(c - h / 2)
    pic = scene(int(w), int(h), sun=(0.42, 0.56), sun_r=0.15)
    canvas = Image.new('RGBA', (S, S), (0, 0, 0, 0)); canvas.paste(pic, (x0, y0))
    im.paste(canvas, (0, 0), inner)
    return im

def write_fg(img):
    for folder, px in [('mdpi', 108), ('hdpi', 162), ('xhdpi', 216), ('xxhdpi', 324), ('xxxhdpi', 432)]:
        img.resize((px, px), Image.LANCZOS).save(f'{RES}/mipmap-{folder}/owntv_icon_eggshell_foreground.png')

def banner(fg):
    W, H = 1280, 720
    bg = vgrad(W, H, [(0, (24, 20, 52, 255)), (1, (10, 10, 22, 255))])
    # A wash of the sunset behind the words.
    glow = Image.new('RGBA', (W, H), (0, 0, 0, 0))
    ImageDraw.Draw(glow).ellipse([200, 160, 900, 760], fill=(214, 76, 120, 70))
    bg.alpha_composite(glow.filter(ImageFilter.GaussianBlur(140)))
    icon = fg.resize((620, 620), Image.LANCZOS)
    bg.alpha_composite(icon, (-30, 50))
    d = ImageDraw.Draw(bg)
    font_dir = '../owntv_glcn/app/src/main/res/font'
    big = ImageFont.truetype(f'{font_dir}/poppins_bold.ttf', 150)
    small = ImageFont.truetype(f'{font_dir}/poppins_semibold.ttf', 104)
    d.text((560, 200), 'OwnTV', font=big, fill=(250, 238, 214, 255))
    d.text((566, 380), 'Fanart', font=small, fill=(255, 150, 110, 255))
    bg.resize((320, 180), Image.LANCZOS).save(f'{RES}/drawable-xhdpi/owntv_banner_eggshell.png')
    bg.save('extras_glcn_banner.png')

if __name__ == '__main__':
    fg = foreground()
    write_fg(fg)
    banner(fg)
    # A flat preview of the full adaptive icon (with its background) for the README.
    full = vgrad(S, S, [(0, (34, 28, 74, 255)), (1, (12, 12, 28, 255))])
    full.alpha_composite(fg)
    full.resize((512, 512), Image.LANCZOS).save('extras_glcn_icon.png')
    print('done')
