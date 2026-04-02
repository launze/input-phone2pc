from PIL import Image, ImageDraw, ImageFilter
import os


BG_START = (15, 118, 110, 255)
BG_END = (22, 78, 99, 255)
GLOW = (204, 251, 241, 80)
BUBBLE = (248, 246, 240, 255)
SHADOW = (4, 47, 46, 75)
WAVE_TOP = (20, 184, 166, 255)
WAVE_BOTTOM = (15, 118, 110, 255)
ACCENT = (251, 146, 60, 255)


def lerp(a, b, t):
    return int(a + (b - a) * t)


def blend(c1, c2, t):
    return tuple(lerp(c1[i], c2[i], t) for i in range(4))


def create_background(size):
    base = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    pixels = base.load()

    denom = max((size - 1) * 2, 1)
    for y in range(size):
        for x in range(size):
            t = (x + y) / denom
            pixels[x, y] = blend(BG_START, BG_END, t)

    mask = Image.new("L", (size, size), 0)
    mask_draw = ImageDraw.Draw(mask)
    radius = int(size * 0.22)
    inset = int(size * 0.05)
    mask_draw.rounded_rectangle(
        [inset, inset, size - inset, size - inset],
        radius=radius,
        fill=255,
    )

    rounded = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    rounded.paste(base, (0, 0), mask)

    glow = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    glow_draw = ImageDraw.Draw(glow)
    glow_box = [
        int(size * 0.02),
        int(size * -0.08),
        int(size * 0.62),
        int(size * 0.52),
    ]
    glow_draw.ellipse(glow_box, fill=GLOW)
    glow = glow.filter(ImageFilter.GaussianBlur(radius=max(2, size // 18)))

    return Image.alpha_composite(rounded, glow)


def draw_shadow(draw, shape_fn):
    shadow = Image.new("RGBA", draw.im.size, (0, 0, 0, 0))
    shadow_draw = ImageDraw.Draw(shadow)
    shape_fn(shadow_draw, SHADOW)
    return shadow.filter(ImageFilter.GaussianBlur(radius=max(2, draw.im.size[0] // 30)))


def create_bubble_layer(size):
    layer = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    rect = [
        int(size * 0.18),
        int(size * 0.21),
        int(size * 0.82),
        int(size * 0.68),
    ]
    radius = int(size * 0.17)
    tail = [
        (int(size * 0.35), int(size * 0.64)),
        (int(size * 0.26), int(size * 0.80)),
        (int(size * 0.47), int(size * 0.73)),
    ]

    def bubble_shape(target_draw, color):
        target_draw.rounded_rectangle(rect, radius=radius, fill=color)
        target_draw.polygon(tail, fill=color)

    shadow = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    shadow_draw = ImageDraw.Draw(shadow)
    bubble_shape(shadow_draw, SHADOW)
    shadow = shadow.filter(ImageFilter.GaussianBlur(radius=max(2, size // 28)))

    base = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    base.alpha_composite(shadow, (0, int(size * 0.02)))

    bubble = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    bubble_draw = ImageDraw.Draw(bubble)
    bubble_shape(bubble_draw, BUBBLE)
    bubble_draw.line(
        [
            (int(size * 0.22), int(size * 0.24)),
            (int(size * 0.76), int(size * 0.24)),
        ],
        fill=(255, 255, 255, 110),
        width=max(1, size // 64),
    )

    return Image.alpha_composite(base, bubble)


def create_wave_bar(width, height):
    bar = Image.new("RGBA", (width, height), (0, 0, 0, 0))
    pixels = bar.load()
    denom = max(height - 1, 1)
    for y in range(height):
        t = y / denom
        color = blend(WAVE_TOP, WAVE_BOTTOM, t)
        for x in range(width):
            pixels[x, y] = color

    mask = Image.new("L", (width, height), 0)
    mask_draw = ImageDraw.Draw(mask)
    mask_draw.rounded_rectangle([0, 0, width, height], radius=width // 2, fill=255)

    rounded = Image.new("RGBA", (width, height), (0, 0, 0, 0))
    rounded.paste(bar, (0, 0), mask)
    return rounded


def create_icon(size):
    icon = create_background(size)
    icon.alpha_composite(create_bubble_layer(size))

    bars = [
        (0.305, 0.38, 0.066, 0.215),
        (0.425, 0.33, 0.066, 0.315),
        (0.545, 0.405, 0.066, 0.19),
    ]
    for x, y, w, h in bars:
        bar = create_wave_bar(int(size * w), int(size * h))
        icon.alpha_composite(bar, (int(size * x), int(size * y)))

    accent = ImageDraw.Draw(icon)
    accent.ellipse(
        [
            int(size * 0.64),
            int(size * 0.29),
            int(size * 0.74),
            int(size * 0.39),
        ],
        fill=ACCENT,
    )

    return icon


sizes = [32, 128, 256, 512]
icons_dir = os.path.dirname(os.path.abspath(__file__))

for size in sizes:
    icon = create_icon(size)
    if size == 128:
        icon.save(os.path.join(icons_dir, "128x128.png"))
        create_icon(256).save(os.path.join(icons_dir, "128x128@2x.png"))
    else:
        icon.save(os.path.join(icons_dir, f"{size}x{size}.png"))

create_icon(512).save(os.path.join(icons_dir, "icon.png"))

print("Icons generated successfully!")
