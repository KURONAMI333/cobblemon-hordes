# -*- coding: utf-8 -*-
"""Contact sheet for the Cobblemon Horde Battles logo candidates —
LOGO_PLAYBOOK ⓪/①: numbered cells, each with the main render plus 96px and
48px insets so kura can judge small-size legibility without opening files.
"""

import os

from PIL import Image, ImageDraw, ImageFont

HERE = os.path.dirname(__file__)
CAND_DIR = os.path.join(HERE, "icon-candidates")
OUT = os.path.join(HERE, "cobblemon_hordes_logo_candidates_sheet.png")

CANDIDATES = [
    ("1", "v5_pidgey_row_purple", "ポッポ×3・横一列・等大 / 紫PKMN帯（実物流用）"),
    ("2", "v5_pidgey_row_red", "ポッポ×3・横一列・等大 / 赤MOD帯（色移植）"),
    ("3", "v5_pidgey_spearhead_purple", "ポッポ×3・前2後1・前大後小 / 紫PKMN帯"),
    ("4", "v5_pidgey_spearhead_red", "ポッポ×3・前2後1・前大後小 / 赤MOD帯（色移植）"),
]

CELL_W = 620
MAIN_SZ = 320
INSET_SZ = 96
INSET_SZ2 = 48
PAD = 24
LABEL_H = 70
CHECKER = (60, 60, 66)
CHECKER2 = (46, 46, 51)


def checkerboard(size, cell=8):
    img = Image.new("RGB", (size, size), CHECKER)
    d = ImageDraw.Draw(img)
    for y in range(0, size, cell):
        for x in range(0, size, cell):
            if (x // cell + y // cell) % 2 == 0:
                d.rectangle([x, y, x + cell, y + cell], fill=CHECKER2)
    return img


def paste_on_checker(im, size):
    bg = checkerboard(size)
    im2 = im.resize((size, size), Image.LANCZOS).convert("RGBA")
    bg = bg.convert("RGBA")
    bg.alpha_composite(im2)
    return bg.convert("RGB")


def font(sz):
    for path in (
        "C:/Windows/Fonts/msgothic.ttc",
        "C:/Windows/Fonts/meiryo.ttc",
        "C:/Windows/Fonts/segoeui.ttf",
    ):
        if os.path.exists(path):
            try:
                return ImageFont.truetype(path, sz)
            except Exception:
                continue
    return ImageFont.load_default()


def font_bold(sz):
    for path in (
        "C:/Windows/Fonts/meiryo.ttc",
        "C:/Windows/Fonts/msgothic.ttc",
        "C:/Windows/Fonts/segoeuib.ttf",
    ):
        if os.path.exists(path):
            try:
                return ImageFont.truetype(path, sz)
            except Exception:
                continue
    return font(sz)


def build_cell(num, name, caption):
    im = Image.open(os.path.join(CAND_DIR, f"{name}_512.png"))
    cell_h = LABEL_H + PAD + MAIN_SZ + PAD + INSET_SZ + PAD * 2 + 30
    cell = Image.new("RGB", (CELL_W, cell_h), (250, 250, 248))
    d = ImageDraw.Draw(cell)

    d.rounded_rectangle(
        [4, 4, CELL_W - 4, cell_h - 4], radius=14, outline=(210, 210, 205), width=2
    )

    badge_r = 22
    d.ellipse([PAD, PAD, PAD + badge_r * 2, PAD + badge_r * 2], fill=(40, 40, 44))
    fnum = font_bold(24)
    bbox = d.textbbox((0, 0), num, font=fnum)
    tw, th = bbox[2] - bbox[0], bbox[3] - bbox[1]
    d.text(
        (PAD + badge_r - tw / 2 - bbox[0], PAD + badge_r - th / 2 - bbox[1]),
        num,
        font=fnum,
        fill=(255, 255, 255),
    )

    fcap = font(20)
    d.text((PAD + badge_r * 2 + 16, PAD + 10), caption, font=fcap, fill=(30, 30, 30))

    main_x = (CELL_W - MAIN_SZ) // 2
    main_y = LABEL_H + PAD
    main_img = paste_on_checker(im, MAIN_SZ)
    cell.paste(main_img, (main_x, main_y))
    d.rectangle(
        [main_x, main_y, main_x + MAIN_SZ, main_y + MAIN_SZ],
        outline=(200, 200, 195),
        width=1,
    )

    inset_y = main_y + MAIN_SZ + PAD
    total_inset_w = INSET_SZ + 16 + INSET_SZ2
    ix = (CELL_W - total_inset_w) // 2
    inset96 = paste_on_checker(im, INSET_SZ)
    cell.paste(inset96, (ix, inset_y))
    d.rectangle(
        [ix, inset_y, ix + INSET_SZ, inset_y + INSET_SZ],
        outline=(200, 200, 195),
        width=1,
    )
    flabel = font(14)
    d.text((ix, inset_y + INSET_SZ + 4), "96px", font=flabel, fill=(120, 120, 120))

    ix2 = ix + INSET_SZ + 16
    off2 = INSET_SZ - INSET_SZ2
    inset48 = paste_on_checker(im, INSET_SZ2)
    cell.paste(inset48, (ix2, inset_y + off2))
    d.rectangle(
        [ix2, inset_y + off2, ix2 + INSET_SZ2, inset_y + off2 + INSET_SZ2],
        outline=(200, 200, 195),
        width=1,
    )
    d.text((ix2, inset_y + INSET_SZ + 4), "48px", font=flabel, fill=(120, 120, 120))

    return cell


def main():
    cells = [build_cell(num, name, cap) for num, name, cap in CANDIDATES]
    cols = 2
    rows = (len(cells) + cols - 1) // cols
    cw, ch = cells[0].size
    title_h = 64
    sheet = Image.new("RGB", (cw * cols, title_h + ch * rows), (255, 255, 255))
    d = ImageDraw.Draw(sheet)
    d.text(
        (24, 18),
        "Cobblemon Horde Battles — logo candidates (mod-067)",
        font=font_bold(28),
        fill=(20, 20, 20),
    )
    for i, cell in enumerate(cells):
        r, c = divmod(i, cols)
        sheet.paste(cell, (c * cw, title_h + r * ch))
    sheet.save(OUT)
    print("saved", OUT)


if __name__ == "__main__":
    main()
