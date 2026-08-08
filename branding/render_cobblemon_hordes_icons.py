# -*- coding: utf-8 -*-
"""Cobblemon Horde Battles icon — round 5.

Round 4 got the right method (real addon-icon template, subject cleared
back to true checkerboard, real Pidgey renders from MineTexture) but the
composition was wrong: 3 birds scattered at irregular sizes/angles, two of
them clipping the right edge. kura's fixes, in order of what changed here:

1. ALIGNMENT: kura's instruction was "3体並べる" (line the 3 up), not
   scatter them -- real horde-battle screenshots show the same species in
   a deliberate row/formation. Two formations are offered:
     - "row": 3 Pidgeys, equal size, single horizontal line, modest
       wingtip overlap only (not a merged blob, not scattered).
     - "spearhead": 2 in front (bigger, side by side) + 1 behind (smaller,
       centered, peeking over), a consistent front-bigger/back-smaller
       rule instead of round 4's irregular sizes.
   All coordinates below are computed in real pixels against measured
   bounds, not eyeballed fractions (round 4's clipping came from picking
   cx_frac/h_frac by hand without checking the resulting edge against the
   canvas) -- see place_row()/place_spearhead() and the assert in
   composite_flock() that checks every sprite's bbox stays inside the
   safe area before saving.

2. CONTAINMENT: safe area is computed from the actual template geometry
   (STRIP_W for the left edge, small margins elsewhere), and every
   placement is checked against it.

3. RED-STRIP TRANSPLANT (kura's request): kura's own description named a
   red "MOD" ribbon; the base this mod uses (tdmon.png) is the purple
   "PKMN" recolour. Round 4 tried using fight_or_flight.png (a real red
   variant) directly as the base and found its own subject (a wooden
   sign) bleeds into the strip near the ribbon -- but that bleed turns out
   to be small (132px, confined to x17-25/y24-45 of the 128px source, see
   _ref/_purple_to_red_lut_check.py in git history if needed) and
   everywhere else tdmon.png and fight_or_flight.png are pixel-identical
   in *shape*, just recoloured (0 structural/opacity mismatches over the
   full 26x128 strip). So instead of using either file's strip as-is, a
   colour lookup table is fit from same-shape pixel pairs in the CLEAN
   region of both files (929+ sample pairs per major colour), and that
   LUT is applied to tdmon's own (uncontaminated) strip shape. Every
   colour placed on the red strip is a colour actually sampled from
   fight_or_flight.png -- nothing invented -- just applied to tdmon's
   clean geometry instead of fight_or_flight's contaminated one.

Checkerboard-regeneration formula and Pidgey sourcing are unchanged from
round 4 (see git history for that reasoning) -- both already worked.
"""

import os

from PIL import Image

HERE = os.path.dirname(__file__)
ASSET_DIR = os.path.join(HERE, "_cobblemon_assets")
REF_DIR = os.path.join(HERE, "_ref")
CAND_DIR = os.path.join(HERE, "icon-candidates")

TDMON = os.path.join(REF_DIR, "tdmon.png")
FIGHT_OR_FLIGHT = os.path.join(REF_DIR, "fight_or_flight.png")
PIDGEY_A = os.path.join(ASSET_DIR, "pidgey_renders", "pidgey_single_a.png")
PIDGEY_B = os.path.join(ASSET_DIR, "pidgey_renders", "pidgey_single_b.png")

CELL = 4  # checkerboard cell size, measured from tdmon.png
BG_EVEN = (20, 16, 24, 241)
BG_ODD = (0, 0, 0, 230)
STRIP_W = 26  # left format strip (bar+ribbon+badge) in the 128px source
CONTAMINATED_BOX = (17, 24, 26, 46)  # x0,y0,x1,y1 -- fight_or_flight's own
# subject art overlapping its ribbon, excluded when building the colour LUT

SIZE = 512
SCALE = SIZE // 128  # =4, tdmon.png/fight_or_flight.png are 128px sources


def checker_color(x, y):
    return BG_EVEN if ((x // CELL) + (y // CELL)) % 2 == 0 else BG_ODD


def clear_subject(base: Image.Image) -> Image.Image:
    """Regenerate true checkerboard over the subject region (x >= STRIP_W).
    Left strip is never touched."""
    px = base.load()
    w, h = base.size
    for y in range(h):
        for x in range(STRIP_W, w):
            r, g, b, a = px[x, y]
            if a < 5:
                continue
            if (r, g, b, a) in (BG_EVEN, BG_ODD):
                continue
            px[x, y] = checker_color(x, y)
    return base


def build_purple_to_red_lut():
    """Colour lookup fit from pixel pairs that share the same opaque/
    transparent shape in tdmon.png (purple) vs fight_or_flight.png (red),
    excluding fight_or_flight's own contaminated patch. Every value in the
    LUT is a colour that really exists in fight_or_flight.png."""
    purple = Image.open(TDMON).convert("RGBA").load()
    red = Image.open(FIGHT_OR_FLIGHT).convert("RGBA").load()
    cx0, cy0, cx1, cy1 = CONTAMINATED_BOX
    votes = {}
    for y in range(128):
        for x in range(STRIP_W):
            if cx0 <= x <= cx1 and cy0 <= y <= cy1:
                continue
            pp, pr = purple[x, y], red[x, y]
            if pp[3] < 10 or pr[3] < 10:
                continue
            votes.setdefault(pp, {})
            votes[pp][pr] = votes[pp].get(pr, 0) + 1
    return {k: max(v.items(), key=lambda kv: kv[1])[0] for k, v in votes.items()}


def build_red_strip(base_purple_512: Image.Image) -> Image.Image:
    """Recolour tdmon's own strip shape (clean, no contamination) using
    colours sampled from fight_or_flight.png via the LUT above."""
    lut = build_purple_to_red_lut()

    def nearest(color):
        # a few antialiased edge pixels won't hit the LUT exactly; snap to
        # the closest sampled colour instead of leaving them purple
        best, best_d = None, None
        for k in lut:
            d = sum((a - b) ** 2 for a, b in zip(k, color))
            if best_d is None or d < best_d:
                best, best_d = k, d
        return lut[best]

    out = base_purple_512.copy()
    px = out.load()
    strip_px_512 = STRIP_W * SCALE
    for y in range(SIZE):
        for x in range(strip_px_512):
            c = px[x, y]
            if c[3] < 5:
                continue
            new_c = lut.get(c) or nearest(c)
            px[x, y] = new_c
    return out


_ASPECT_CACHE = {}


def sprite_aspect(path):
    """Width/height of the sprite's own trimmed bbox. The two renders use
    different yaw angles (a=3/4 view, b=wings-flat-frontal), so they do
    NOT share one aspect ratio -- round 5's first run asserted out because
    place_spearhead() was using PIDGEY_A's aspect (1.37) to size PIDGEY_B
    sprites, which are actually 1.87 (much wider/flatter wingspan) and so
    ended up far wider than the width the containment math expected."""
    if path not in _ASPECT_CACHE:
        im = Image.open(path).convert("RGBA")
        x0, y0, x1, y1 = im.getbbox()
        _ASPECT_CACHE[path] = (x1 - x0) / (y1 - y0)
    return _ASPECT_CACHE[path]


def load_pidgey(path, target_h):
    im = Image.open(path).convert("RGBA")
    im = im.crop(im.getbbox())
    scale = target_h / im.height
    return im.resize((max(1, round(im.width * scale)), target_h), Image.LANCZOS)


def paste_bottom_anchored(canvas, sprite, cx, baseline_y):
    x = round(cx - sprite.width / 2)
    y = round(baseline_y - sprite.height)
    canvas.alpha_composite(sprite, (x, y))
    return (x, y, x + sprite.width, y + sprite.height)


# Safe placement area on the 512 canvas: left of STRIP_W*SCALE + margin,
# inset from the other 3 edges too.
SAFE_LEFT = STRIP_W * SCALE + 8
SAFE_RIGHT = SIZE - 12
SAFE_TOP = 16
SAFE_BOTTOM = SIZE - 12


def place_row(pidgey_path, overlap_frac=0.35):
    """3 equal-size Pidgeys, single horizontal row, modest overlap only."""
    aspect = sprite_aspect(pidgey_path)
    avail_w = SAFE_RIGHT - SAFE_LEFT
    wb = avail_w / (3 - 2 * overlap_frac)
    hb = wb / aspect
    step = wb * (1 - overlap_frac)
    cx0 = SAFE_LEFT + wb / 2
    cy = (SAFE_TOP + SAFE_BOTTOM) / 2
    placements = []
    for i in range(3):
        placements.append((cx0 + i * step, cy + hb / 2, hb))  # (cx, baseline_y, h)
    return pidgey_path, placements


def place_spearhead(pidgey_path):
    """2 in front (bigger, side by side, low) + 1 behind (smaller,
    centred, peeking over the top) -- consistent front>back sizing."""
    aspect = sprite_aspect(pidgey_path)
    avail_w = SAFE_RIGHT - SAFE_LEFT
    overlap_frac = 0.20
    wf = avail_w / (2 - overlap_frac)
    hf = wf / aspect
    step = wf * (1 - overlap_frac)
    cx0 = SAFE_LEFT + wf / 2
    front_baseline = SAFE_BOTTOM
    hb = hf * 0.62
    cx_back = (SAFE_LEFT + SAFE_RIGHT) / 2
    back_baseline = (front_baseline - hf) + hb * 0.55

    # first pass fills the safe area bottom-up; re-centre the whole group
    # vertically afterwards so it doesn't end up stranded in the bottom
    # third of the icon with empty checkerboard above it
    group_top = back_baseline - hb
    group_bottom = front_baseline
    group_center = (group_top + group_bottom) / 2
    target_center = (SAFE_TOP + SAFE_BOTTOM) / 2
    shift = target_center - group_center
    front_baseline += shift
    back_baseline += shift

    front = [
        (cx0, front_baseline, hf),
        (cx0 + step, front_baseline, hf),
    ]
    back = [(cx_back, back_baseline, hb)]
    # draw back first (behind), then front (in front)
    return pidgey_path, back + front


def composite_flock(cleared_512, pidgey_path, name, placements):
    canvas = cleared_512.copy()
    for cx, baseline_y, h in placements:
        sprite = load_pidgey(pidgey_path, round(h))
        bbox = paste_bottom_anchored(canvas, sprite, cx, baseline_y)
        x0, y0, x1, y1 = bbox
        assert x0 >= SAFE_LEFT - 2, f"{name}: left overflow {bbox}"
        assert x1 <= SAFE_RIGHT + 2, f"{name}: right overflow {bbox}"
        assert y0 >= 0, f"{name}: top overflow {bbox}"
        assert y1 <= SIZE, f"{name}: bottom overflow {bbox}"
    for sz in (512, 256, 128, 64, 48):
        canvas.resize((sz, sz), Image.LANCZOS if sz != 512 else Image.NEAREST).save(
            os.path.join(CAND_DIR, f"{name}_{sz}.png")
        )
    return canvas


if __name__ == "__main__":
    os.makedirs(CAND_DIR, exist_ok=True)

    base = Image.open(TDMON).convert("RGBA")
    cleared = clear_subject(base)
    cleared_purple_512 = cleared.resize((SIZE, SIZE), Image.NEAREST)
    cleared_purple_512.save(os.path.join(ASSET_DIR, "tdmon_template_cleared_512.png"))

    cleared_red_512 = build_red_strip(cleared_purple_512)
    cleared_red_512.save(os.path.join(ASSET_DIR, "tdmon_template_cleared_red_512.png"))

    for base_name, cleared_512 in (
        ("purple", cleared_purple_512),
        ("red", cleared_red_512),
    ):
        _, placements_row = place_row(PIDGEY_A)
        composite_flock(
            cleared_512, PIDGEY_A, f"v5_pidgey_row_{base_name}", placements_row
        )
        _, placements_spear = place_spearhead(PIDGEY_B)
        composite_flock(
            cleared_512,
            PIDGEY_B,
            f"v5_pidgey_spearhead_{base_name}",
            placements_spear,
        )

    print("done")
