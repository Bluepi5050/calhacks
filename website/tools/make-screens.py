#!/usr/bin/env python3
"""Rebuild the phone screenshots in media/screens/ from app renders.

SWAP POINT: edit SCREENS below (source PNG per slot), then run
    python3 tools/make-screens.py
Output names are fixed (media/screens/<slot>.webp), so index.html needs no edit
unless a screen's aspect ratio changes (then update width/height on its <img>)
or its content changes (then update the alt text in the SCREENS block of index.html).
Needs Pillow. Not deployed (hidden by _redirects, not in the deploy ZIP).
"""
import os
from PIL import Image

R = "/cloudspace/smartbag"
SCREENS = {
    # slot                 source render (light / dark pairs; same app state on both platforms)
    "ios-shop":            R + "/ios-ui/final/11-filling.png",
    "ios-shop-dark":       R + "/ios-ui/final/15-filling-dark.png",
    "ios-receipt":         R + "/ios-ui/final/13-receipt-paid.png",
    "ios-receipt-dark":    R + "/ios-ui/final/16-receipt-dark.png",
    "android-shop":        R + "/android-ui/final/411-light/09-filling.png",
    "android-shop-dark":   R + "/android-ui/final/411-dark/09-filling.png",
    "android-receipt":     R + "/android-ui/final/411-light/11-receipt-paid.png",
    "android-receipt-dark": R + "/android-ui/final/411-dark/11-receipt-paid.png",
}
WIDTH = 480      # about 2x the largest display width (240 px)
QUALITY = 80

out = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "media", "screens")
os.makedirs(out, exist_ok=True)
for slot, src in SCREENS.items():
    im = Image.open(src).convert("RGB")
    h = round(im.height * WIDTH / im.width)
    im = im.resize((WIDTH, h), Image.LANCZOS)
    dst = os.path.join(out, slot + ".webp")
    im.save(dst, "WEBP", quality=QUALITY, method=6)
    print(f"{slot:22s} {WIDTH}x{h}  {os.path.getsize(dst)//1024} KB  <- {src}")
