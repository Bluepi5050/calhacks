#!/usr/bin/env python3
"""Phone screens for "Get the app": ONE page list -> images + the screen gallery in index.html.

SWAP POINT: edit PAGES below, then run
    python3 tools/make-screens.py
It (1) writes media/screens/<platform>-<id>.webp (+ -dark.webp when a dark render is given),
(2) rewrites both galleries (Android and iPhone) in index.html between <!-- GALLERY --> markers,
(3) deletes stale .webp files in media/screens.
Order of PAGES = order of the screens (left to right, then row by row). Every page needs a render
for BOTH platforms (the gallery always shows every page for the chosen platform). Wide screens show
one row of up to 6; more pages wrap into balanced rows (11 pages -> 6 + 5). Dark renders are optional per platform and page;
when one is missing, that screen stays light in dark mode. Use a platform's dark set only when
it covers every page, or the tour switches between light and dark screens.
Needs Pillow. Not deployed (hidden by _redirects, not in the deploy ZIP).
"""
import json, os, re
from PIL import Image

I = "/cloudspace/smartbag/ios-ui/final/"                 # iPhone 1.1 reference renders (HTML mirror, 390x844 @2x)
A = "/cloudspace/smartbag/android-ui/final/411-light/"   # Android 3.1 renders (Robolectric, 411 dp)
AD = "/cloudspace/smartbag/android-ui/final/411-dark/"

PAGES = [
    # id, label (caption under the screen), alt (platform-neutral; the page adds "Bag It on Android/iPhone"),
    # android/ios: light render, optional dark render (None = keep light)
    dict(id="welcome", label="Welcome",
         alt="Welcome screen: The bag that checks you out. It lists what the app needs (Bluetooth, location, notifications) and a Get started button.",
         android=(A + "01-welcome.png", AD + "01-welcome.png"), ios=(I + "01-welcome.png", None)),
    dict(id="in-store", label="In store",
         alt="In the store: You’re at Bayside Grocer, bag connected, the bag is empty: Drop something in.",
         android=(A + "08-in-store-empty.png", AD + "08-in-store-empty.png"), ios=(I + "09-in-store-empty.png", None)),
    dict(id="shopping", label="Shopping",
         alt="Shopping at Bayside Grocer: each item appears with its price as it goes in the bag, one item isn’t sold there and won’t be charged, and the total says Just walk out.",
         android=(A + "09-filling.png", AD + "09-filling.png"), ios=(I + "11-filling.png", None)),
    dict(id="leaving", label="Leaving",
         alt="Leaving Bayside Grocer: the bag is charged as you leave; step back inside to keep shopping.",
         android=(A + "10-leaving.png", AD + "10-leaving.png"), ios=(I + "12-leaving.png", None)),
    dict(id="receipt", label="Receipt",
         alt="Receipt: Paid as you left Bayside Grocer with a demo card, an exit code to show staff if asked, and Done.",
         android=(A + "11-receipt-paid.png", AD + "11-receipt-paid.png"), ios=(I + "13-receipt-paid.png", None)),
]
WIDTH = 560          # about 2x the largest display width (~250 px at 2 columns on a tablet)
HEIGHT = 1213        # display box ratio; renders are cropped/fit to WIDTH x HEIGHT
QUALITY = 80
# Android renders carry a placeholder status-bar clock in the top-left corner, which the phone frame's
# rounded corner cuts in half. Blank that strip (source pixels) to the background colour.
BLANK_TOP = {"android": 60, "ios": 0}

ROOT = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..")
OUT = os.path.join(ROOT, "media", "screens")
os.makedirs(OUT, exist_ok=True)

def make(src, name, plat):
    im = Image.open(src).convert("RGB")
    if BLANK_TOP.get(plat):
        top = BLANK_TOP[plat]
        im.paste(im.getpixel((im.width // 2, top // 2)), (0, 0, im.width, top))
    h = round(im.height * WIDTH / im.width)
    im = im.resize((WIDTH, h), Image.LANCZOS)
    if h != HEIGHT:   # pad/crop the bottom to the shared box (renders differ by ~1 px)
        box = Image.new("RGB", (WIDTH, HEIGHT), im.getpixel((WIDTH // 2, h - 1)))
        box.paste(im.crop((0, 0, WIDTH, min(h, HEIGHT))), (0, 0))
        im = box
    path = os.path.join(OUT, name)
    im.save(path, "WEBP", quality=QUALITY, method=6)
    print(f"{name:32s} {os.path.getsize(path) // 1024:3d} KB  <- {src}")
    return "media/screens/" + name

made, data = set(), []
for p in PAGES:
    row = dict(id=p["id"], label=p["label"], alt=p["alt"])
    for plat in ("android", "ios"):
        light, dark = p[plat]
        row[plat] = make(light, f"{plat}-{p['id']}.webp", plat)
        row[plat + "Dark"] = make(dark, f"{plat}-{p['id']}-dark.webp", plat) if dark else None
        made.update(x.split("/")[-1] for x in (row[plat], row[plat + "Dark"]) if x)
    data.append(row)
for f in os.listdir(OUT):
    if f.endswith(".webp") and f not in made:
        os.remove(os.path.join(OUT, f)); print("removed stale", f)

idx = os.path.join(ROOT, "index.html")
html = open(idx, encoding="utf-8").read()
n = len(data)
rows = -(-n // 6)
cols = -(-n // rows)          # balanced columns for wide screens, at most 6
NAME = {"android": ("Android", "Android app", "Screens show demo data."),
        "ios": ("iPhone", "iPhone app", "The iPhone screens are previews of the app in development, with demo data.")}
def gallery(plat):
    name, head, note = NAME[plat]
    items = []
    for i, r in enumerate(data):
        eager = plat == "android" and i < cols    # first row of the default gallery; everything else lazy
        src = (f'<source srcset="{r[plat + "Dark"]}" media="(prefers-color-scheme: dark)">' if r[plat + "Dark"] else "")
        img = (f'<img src="{r[plat]}" width="{WIDTH}" height="{HEIGHT}" decoding="async"'
               + ("" if eager else ' loading="lazy"') + f' alt="Bag It on {name}. {r["alt"]}">')
        items.append(f'<li><figure><div class="ph"><picture>{src}{img}</picture></div><figcaption>{r["label"]}</figcaption></figure></li>')
    hidden = " hidden" if plat == "ios" else ""
    return (f'<div class="gal" id="gal-{plat}"{hidden}><div class="gal-h"><h3>{head}</h3><p>{note}</p></div>\n'
            f'<ul class="shots" style="--cols:{cols}" aria-label="{head} screens">\n' + "\n".join(items) + "\n</ul></div>")
block = ("<!-- GALLERY (generated by tools/make-screens.py; edit PAGES there) -->\n"
         + gallery("android") + "\n" + gallery("ios") + "\n<!-- /GALLERY -->")
html, k = re.subn(r"<!-- GALLERY.*?<!-- /GALLERY -->", lambda m: block, html, flags=re.S)
if k != 1:
    raise SystemExit("index.html: <!-- GALLERY --> markers not found")
open(idx, "w", encoding="utf-8").write(html)
print(f"index.html: {n} pages, {cols} per row on wide screens")
