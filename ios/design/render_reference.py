#!/usr/bin/env python3
"""Reference renders of the Bag It iPhone UI (NOT a build). 1.2 adds accounts, setup, pairing, wallet, trips, tabs.

SwiftUI cannot be compiled or rendered on Linux, so this script mirrors the SwiftUI layout in HTML/SVG at
390x844 pt (iPhone 14/15 size) with the same type scale (34/22/17/13, Regular + Semibold), spacing (8-pt grid,
24 pt margin), colours and copy as SmartBag/Views/*.swift, and screenshots it with headless Chromium.
SF Pro is approximated with Inter, SF Mono with JetBrains Mono. Usage: python3 design/render_reference.py OUTDIR
"""
import html, sys, os

L = dict(bg="#FAFAFA", surface="#FFFFFF", hair="#E4E4E7", unlit="#D4D4D8", ink="#18181B", muted="#52525B",
         faint="#71717A", onInk="#FFFFFF", good="#157A4A", bad="#B42318")
D = dict(bg="#0B0B0C", surface="#18181B", hair="#27272A", unlit="#3F3F46", ink="#F4F4F5", muted="#A1A1AA",
         faint="#8E8E97", onInk="#18181B", good="#34C47C", bad="#F07171")
CORAL = "#E8654A"
E = html.escape

TOTE = ("M10.5 12h27a2.5 2.5 0 0 1 2.5 2.6l-1 26.6a2.8 2.8 0 0 1-2.8 2.8H12.8a2.8 2.8 0 0 1-2.8-2.8l-1-26.6"
        "A2.5 2.5 0 0 1 10.5 12z")
SLOT = "M18.9 17.5h10.2a2.4 2.4 0 0 1 0 4.8H18.9a2.4 2.4 0 0 1 0-4.8z"

def mark(size, tone="var(--ink)", lit=1.0):
    return (f'<svg width="{size}" height="{size}" viewBox="4 8 40 40" aria-hidden="true">'
            f'<path fill="{tone}" fill-rule="evenodd" d="{TOTE}{SLOT}"/>'
            f'<path fill="{CORAL}" opacity="{lit}" d="{SLOT}"/></svg>')

def wordmark(h, color="var(--ink)"):
    w = h * 104 / 44
    return (f'<svg width="{w:.1f}" height="{h}" viewBox="0 10 104 44" aria-label="Bag It">'
            f'<g fill="none" stroke="{color}" stroke-width="4" stroke-linecap="round" stroke-linejoin="round">'
            '<path d="M2 12V40"/><circle cx="10" cy="32" r="8"/><circle cx="33.5" cy="32" r="8"/><path d="M41.5 24V40"/>'
            '<circle cx="57.5" cy="32" r="8"/><path d="M65.5 24V43a8 8 0 0 1-8 8h-4"/><path d="M83 24V40"/>'
            '<path d="M95 14V35a5 5 0 0 0 5 5h1.5M90 24H100"/></g>'
            f'<circle cx="83" cy="15.2" r="3.1" fill="{color}"/></svg>')

SYM = {  # SF Symbols stand-ins (outline, 1.6 stroke) for the reference only
 "location": '<path d="M12 21s-6.5-6.2-6.5-11.2a6.5 6.5 0 0 1 13 0C18.5 14.8 12 21 12 21z"/><circle cx="12" cy="9.8" r="2.3"/>',
 "location.slash": '<path d="M12 21s-6.5-6.2-6.5-11.2a6.5 6.5 0 0 1 13 0C18.5 14.8 12 21 12 21z"/><path d="M4 4l16 16"/>',
 "bell": '<path d="M6 16V11a6 6 0 0 1 12 0v5l1.5 2h-15z"/><path d="M10 20.5a2 2 0 0 0 4 0"/>',
 "exclamationmark.circle": '<circle cx="12" cy="12" r="8.5"/><path d="M12 7.5v5.5"/><circle cx="12" cy="16.3" r=".6" fill="currentColor"/>',
 "chevron.right": '<path d="M9 5l7 7-7 7"/>',
}
def sym(name, size=17, color="var(--ink)"):
    return (f'<svg width="{size}" height="{size}" viewBox="0 0 24 24" fill="none" stroke="{color}" stroke-width="1.7" '
            f'stroke-linecap="round" stroke-linejoin="round" style="color:{color};flex:none">{SYM[name]}</svg>')

def check_circle(color, size=17, bang=False):
    inner = ('<path d="M12 7.5v5.5" stroke="white" stroke-width="2" stroke-linecap="round"/><circle cx="12" cy="16.4" r="1.1" fill="white"/>'
             if bang else '<path d="M7.8 12.3l2.8 2.8 5.6-5.8" stroke="white" stroke-width="2" fill="none" stroke-linecap="round" stroke-linejoin="round"/>')
    return f'<svg width="{size}" height="{size}" viewBox="0 0 24 24" style="flex:none;transform:translateY(2px)"><circle cx="12" cy="12" r="10" fill="{color}"/>{inner}</svg>'

CSS = """
@import url('https://fonts.googleapis.com/css2?family=Inter:wght@400;600&family=JetBrains+Mono:wght@400;600&display=block');
*{box-sizing:border-box;margin:0;padding:0}
body{background:#d4d4d8;font-family:Inter,system-ui,sans-serif;-webkit-font-smoothing:antialiased}
.phone{width:390px;height:844px;position:relative;overflow:hidden;background:var(--bg);color:var(--ink);display:flex;flex-direction:column}
.status{height:47px;flex:none;position:relative;font:600 17px/22px Inter;display:flex;align-items:center;justify-content:space-between;padding:12px 32px 0 40px}
.island{position:absolute;left:50%;top:11px;width:126px;height:37px;margin-left:-63px;border-radius:20px;background:#000}
.home{position:absolute;left:50%;bottom:8px;width:134px;height:5px;margin-left:-67px;border-radius:3px;background:var(--ink);z-index:9}
.scroll{flex:1;overflow:hidden;padding:8px 24px 32px}
.display{font:600 34px/41px Inter;letter-spacing:.2px}
.dmono{font:400 34px/41px 'JetBrains Mono';letter-spacing:-.5px}
.tmono{font:400 22px/28px 'JetBrains Mono'}
.body{font:400 17px/22px Inter;letter-spacing:-.2px}
.bodys{font:600 17px/22px Inter;letter-spacing:-.2px}
.bmono{font:400 17px/22px 'JetBrains Mono';letter-spacing:-.3px}
.cap{font:400 13px/18px Inter}
.caps{font:600 13px/18px Inter}
.cmono{font:400 13px/18px 'JetBrains Mono'}
.cnum{font:400 13px/18px Inter;font-variant-numeric:tabular-nums}
.ink{color:var(--ink)}.muted{color:var(--muted)}.faint{color:var(--faint)}.good{color:var(--good)}.bad{color:var(--bad)}
.hair{height:1px;background:var(--hair)}
.row{display:flex;align-items:center;gap:12px;min-height:56px;padding:12px 0;position:relative;isolation:isolate}
.grow{flex:1;min-width:0}
.pill{display:flex;align-items:center;justify-content:center;min-height:52px;border-radius:999px;background:var(--ink);color:var(--onInk);font:600 17px/22px Inter;padding:12px 24px}
.pill.sec{background:var(--surface);color:var(--ink);box-shadow:inset 0 0 0 1px var(--hair)}
.pill.fit{display:inline-flex;min-height:44px}
.chip{display:inline-flex;align-items:center;gap:8px;height:32px;padding:0 12px;border-radius:999px;background:var(--surface);box-shadow:inset 0 0 0 1px var(--hair);font:400 13px/18px Inter}
.dot{width:8px;height:8px;border-radius:50%;flex:none}
.spin{width:12px;height:12px;border-radius:50%;border:2px solid var(--hair);border-top-color:var(--muted);flex:none}
.act{font:600 13px/18px Inter;text-decoration:underline;color:var(--ink);min-width:44px;min-height:44px;display:flex;align-items:center;justify-content:center;padding:0 8px}
.dock{flex:none;background:var(--surface);position:relative;padding:16px 24px 50px}
.bottom{flex:none;padding:12px 24px 42px}
.label{position:absolute;top:0;left:0;right:0;z-index:20;display:none}
.wash{position:absolute;top:0;bottom:0;left:-24px;right:-24px;background:rgba(232,101,74,.10);z-index:-1}
.dark .wash{background:rgba(232,101,74,.14)}
.tag{font:600 10px/12px Inter;letter-spacing:.06em;text-transform:uppercase;color:#71717A;text-align:center;padding:6px 0 0}
"""

def page(theme, status_time="9:41"):
    vars_ = ";".join(f"--{k}:{v}" for k, v in theme.items())
    return vars_

AX = """.display{font-size:44px;line-height:52px}.dmono{font-size:44px;line-height:52px}.tmono{font-size:34px;line-height:41px}
.body,.bodys,.bmono,.pill{font-size:28px;line-height:34px}.cap,.caps,.cmono,.cnum{font-size:23px;line-height:29px}.chip{height:auto;padding:6px 12px}"""

STATUS_ICONS = ('<svg width="72" height="13" viewBox="0 0 72 13" fill="var(--ink)"><rect x="0" y="8" width="3" height="4" rx="1"/>'
    '<rect x="5" y="6" width="3" height="6" rx="1"/><rect x="10" y="3" width="3" height="9" rx="1"/><rect x="15" y="0" width="3" height="12" rx="1"/>'
    '<path d="M30 4.5a9 9 0 0 1 12 0l-1.4 1.5a7 7 0 0 0-9.2 0zM32.8 7.5a5 5 0 0 1 6.4 0L36 11z"/>'
    '<rect x="47.5" y="0.5" width="21" height="11" rx="3.5" fill="none" stroke="var(--ink)" opacity=".45"/><rect x="49.5" y="2.5" width="17" height="7" rx="2"/>'
    '<rect x="70" y="4" width="1.5" height="4" rx=".7" opacity=".45"/></svg>')

def frame(inner, theme=L, scale=1.0, ax=False):
    vars_ = page(theme)
    return (f'<!doctype html><html><head><meta charset="utf-8"><style>{CSS}{AX if ax else ""}</style></head><body>'
            f'<div class="phone{" dark" if theme is D else ""}" style="{vars_};{"zoom:%s" % scale if scale != 1 else ""}">'
            f'<div class="status"><span>9:41</span>{STATUS_ICONS}<div class="island"></div></div>'
            f'{inner}<div class="home"></div></div></body></html>')

def topbar(chip):
    return f'<div style="display:flex;align-items:center;justify-content:space-between;min-height:44px">{wordmark(20)}{chip}</div>'

def chip(state):
    if state == "connected": lead, label = '<span class="dot" style="background:var(--good)"></span>', "Bag connected"
    elif state == "looking": lead, label = '<span class="spin"></span>', "Looking for your bag"
    elif state == "problem": lead, label = '<span class="dot" style="background:var(--bad)"></span>', "Bag not connected"
    else: lead, label = '<span class="dot" style="background:var(--faint)"></span>', "Connect bag"
    return f'<span class="chip">{lead}<span class="ink">{label}</span></span>'

def notice(symbol, msg, action):
    return (f'<div style="display:flex;align-items:center;gap:12px;padding:4px 0">{sym(symbol, 17, "var(--bad)")}'
            f'<span class="cap ink grow">{E(msg)}</span><span class="act" style="margin-right:-8px">{E(action)}</span></div><div class="hair"></div>')

def hero_title(t, spinner=False):
    sp = '<span class="spin" style="width:18px;height:18px;margin-left:12px"></span>' if spinner else ""
    return f'<div style="display:flex;align-items:center"><div class="display">{E(t)}</div>{sp}</div>'

def lead(t): return f'<div class="body muted" style="margin-top:12px">{E(t)}</div>'

def bag_rows(lines, flash=None):
    out = ['<div style="display:flex;justify-content:space-between;padding-bottom:8px"><span class="cap muted">In your bag</span>'
           f'<span class="cnum muted">{sum(l[2] for l in lines if l[4] != "missing")} items</span></div><div class="hair"></div>']
    for name, price, qty, total, kind in lines:
        w = '<div class="wash"></div>' if flash == name else ""
        if kind == "sold":
            out.append(f'<div class="row">{w}<div class="grow"><div class="body ink">{E(name)}</div>'
                       f'<div class="cnum muted" style="margin-top:4px">{qty} × <span class="cmono">{price}</span></div></div><div class="bmono ink">{total}</div></div><div class="hair"></div>')
        elif kind == "missing":
            out.append(f'<div class="row"><div class="grow"><div class="body faint">Unknown item&nbsp; <span class="cmono faint">{E(name)}</span></div>'
                       f'<div class="cap muted" style="margin-top:4px">Not sold here · won’t be charged</div></div><span class="act" style="margin-right:-8px">Dismiss</span></div><div class="hair"></div>')
        else:
            out.append(f'<div class="row"><div class="grow"><div class="body ink">{E(name)}</div>'
                       f'<div class="cap muted" style="margin-top:4px">Priced when you’re in a store</div></div></div><div class="hair"></div>')
    return "".join(out)

def dock(title, line1=None, card=None, total=None, count=None, lit=0.0, leaving=False, err=None, ax=False, fix=None, problem=None, tone="var(--ink)"):
    line = (f'<div style="position:absolute;top:0;left:0;right:0;height:2px;background:var(--hair)">'
            f'<div style="width:50%;height:2px;background:{CORAL}"></div></div>') if leaving else \
           '<div style="position:absolute;top:0;left:0;right:0;height:1px;background:var(--hair)"></div>'
    cnt = "" if ax else f'<div class="cnum muted" style="margin-top:4px">{count}</div>'
    tot = (f'<div style="text-align:{"left" if ax else "right"};flex:none"><div class="tmono ink">{total}</div>{cnt}</div>') if total else ""
    l1 = f'<div class="cap muted" style="margin-top:4px;{"" if ax else "white-space:nowrap"}">{E(line1)}</div>' if line1 else ""
    l2 = f'<div class="cap faint" style="margin-top:4px;white-space:nowrap">{card}</div>' if card and not ax else ""
    e = f'<div class="cap bad" style="margin-top:8px">{E(err)}</div>' if err else ""
    if fix:
        e += ((f'<div class="cap bad" style="margin-top:16px">{E(problem)}</div>' if problem else "") +
              f'<div class="pill" style="margin-top:8px">{E(fix)}</div>')
    direction = "column;align-items:flex-start;gap:12px" if ax else "row;align-items:center;gap:16px"
    return (f'<div class="dock" style="{"padding-bottom:42px" if fix else ""}">{line}<div style="display:flex;flex-direction:{direction}">'
            f'<div style="display:flex;align-items:center;gap:16px;flex:1;min-width:0">{mark(32, tone, lit=lit)}'
            f'<div class="grow"><div class="bodys ink">{E(title)}</div>{l1}{l2}</div></div>'
            f'{tot}</div>{e}</div>')

def action_dock(title):
    return f'<div class="bottom" style="padding-top:12px;padding-bottom:42px"><div class="pill">{E(title)}</div></div>'

def scroll(inner): return f'<div class="scroll">{inner}</div>'

# ---------------------------------------------------------------- screens
CARD = "Demo Visa •••• 4242".replace(" ", "\u00a0")
FILL = {"location.fill": '<path fill="currentColor" stroke="none" fill-rule="evenodd" d="M12 21.5s-7-6.6-7-11.8a7 7 0 0 1 14 0c0 5.2-7 11.8-7 11.8zm0-9a2.6 2.6 0 1 0 0-5.2 2.6 2.6 0 0 0 0 5.2z"/>',
        "bell.fill": '<path fill="currentColor" stroke="none" d="M12 2.5a6.5 6.5 0 0 0-6.5 6.5v5.2L3.6 17a.8.8 0 0 0 .7 1.2h15.4a.8.8 0 0 0 .7-1.2l-1.9-2.8V9A6.5 6.5 0 0 0 12 2.5zM9.3 19.5a2.7 2.7 0 0 0 5.4 0z"/>'}
SYM.update(FILL)
# newest first; bananas just added (flash)
LINES = [("Organic bananas", "$1.29", 2, "$2.58", "sold"), ("9999", "", 1, "", "missing"),
         ("Sourdough loaf", "$6.49", 1, "$6.49", "sold"), ("Whole milk", "$4.69", 1, "$4.69", "sold")]

def welcome():
    beats = "".join(f'<div style="display:flex;gap:16px;align-items:baseline"><span class="body faint" style="width:16px;font-variant-numeric:tabular-nums">{n}</span>'
                    f'<div><div class="bodys ink">{t}</div><div class="cap muted" style="margin-top:4px">{w}</div></div></div>'
                    for n, t, w in [(1, "Drop it in", "Put groceries in your bag as you shop."),
                                    (2, "It knows", "Each item shows up here with the store’s price."),
                                    (3, "Walk out", "Your card is charged as you leave the store.")])
    lock = (f'<div style="display:flex;align-items:flex-start;gap:4px"><div style="margin-left:-4px">{mark(32)}</div>'
            f'<div style="margin-top:3.2px">{wordmark(35.2)}</div></div>')
    inner = (f'<div class="scroll" style="padding-top:48px">{lock}<div class="display" style="margin-top:32px">The bag that<br>checks you out.</div>'
             f'<div class="body muted" style="margin-top:12px">No lines. No scanning.</div>'
             f'<div style="display:flex;flex-direction:column;gap:16px;margin-top:32px">{beats}</div>'
             f'<div class="cap faint" style="margin-top:32px">Demo: payments are simulated with {CARD}.</div></div>'
             f'<div class="bottom" style="padding-top:16px"><div class="pill">Get started</div></div>')
    return frame(inner)

def prime(step, art, title, why, tip, buttons):
    bar = "".join(f'<span style="width:24px;height:4px;border-radius:2px;background:{"var(--ink)" if i <= step else "var(--hair)"}"></span>' for i in (1, 2, 3))
    t = f'<div class="cap muted" style="margin-top:16px">{E(tip)}</div>' if tip else ""
    inner = (f'<div style="padding:16px 24px 0;display:flex;gap:4px">{bar}</div>'
             f'<div class="scroll" style="padding-top:48px">{art}<div class="display" style="margin-top:32px">{E(title)}</div>'
             f'<div class="body muted" style="margin-top:12px">{E(why)}</div>{t}</div>'
             f'<div class="bottom" style="padding-top:16px;display:flex;flex-direction:column;gap:8px">{buttons}</div>')
    return frame(inner)

def perm_bt(): return prime(1, f'<div style="margin-left:-6px">{mark(48, lit=0)}</div>', "Connect your bag", "Bag It uses Bluetooth to hear what you drop in your bag.", None, '<div class="pill">Allow Bluetooth</div>')
def perm_loc(): return prime(2, sym("location.fill", 40), "Find your store",
    "Your location tells us which store you’re in, and when you leave so we can charge your bag.",
    "Choose Allow While Using App with Precise Location on. Later, choose Change to Always Allow.",
    '<div class="pill">Allow Location</div>')
def perm_notif(): return prime(3, sym("bell.fill", 40), "Get your receipt", "We’ll send it the moment you walk out.", None,
    '<div class="pill">Allow Notifications</div><div class="pill sec">Not now</div>')

def shop(chipstate, hero, body="", dk="", notices="", theme=L, ax=False):
    return frame(scroll(topbar(chip(chipstate)) + notices + f'<div style="margin-top:32px">{hero}</div>' + body) + dk, theme, ax=ax)

def instore_hero(name="Bayside Grocer", leaving=False):
    extra = lead("Step back inside to keep shopping.") if leaving else ""
    eb = '<div class="caps ink">Leaving</div>' if leaving else '<div class="cap muted">You’re at</div>'
    return (f'{eb}<div class="display" style="margin-top:4px">{name}</div>'
            f'<div class="cap muted" style="margin-top:4px">Market St, San Francisco</div>{extra}')

def finding(): return shop("looking", hero_title("Finding your store", True) + lead("Checking your location."))

def away():
    last = ('<div style="margin-top:48px"><div class="cap muted" style="padding-bottom:8px">Last trip</div><div class="hair"></div>'
            f'<div class="row"><div class="grow"><div class="body ink">Bayside Grocer</div><div class="cap muted" style="margin-top:4px">Today, 2:14 PM</div></div>'
            f'<div class="bmono ink">$13.76</div>{sym("chevron.right", 13, "var(--faint)")}</div><div class="hair"></div></div>')
    hero = (hero_title("Not in a Bag It store") + lead("Walk into one and your bag starts pricing on its own.") +
            f'<div style="display:flex;gap:8px;align-items:center;margin-top:16px">{sym("location", 13, "var(--faint)")}'
            '<span class="cnum muted">Nearest: Bayside Grocer · 1.2 km</span></div>')
    return shop("connected", hero, last)

def locoff(): return shop("connected", hero_title("Location is off") + lead("Bag It needs your location to find your store, and to charge your bag when you leave."),
                          dk=action_dock("Open Settings"))
def offline(): return shop("connected", hero_title("Can’t reach Bag It") + lead("The connection was lost. Check your signal."), dk=action_dock("Try again"))

def empty_state(title, why):
    return (f'<div style="margin-top:32px;padding:48px 0;text-align:center;display:flex;flex-direction:column;align-items:center">{mark(48, "var(--unlit)", 0)}'
            f'<div class="bodys ink" style="margin-top:16px">{E(title)}</div><div class="cap muted" style="margin-top:4px">{E(why)}</div></div>')

def empty_bag():
    return shop("connected", instore_hero(), empty_state("Drop something in", "Items show up here with Bayside Grocer’s prices."),
                dock("Fill your bag, then just walk out", "Paying with", CARD))

def bag_problem():
    n = notice("location.slash", "Precise Location is off, so we may miss when you leave.", "Turn on")
    return shop("problem", instore_hero(), empty_state("Your bag is empty", "Items show up here once your bag is connected."),
                dock("Connect your bag to start", fix="Connect bag", problem="No bag found nearby. Make sure it’s switched on.", tone="var(--unlit)"), notices=n)

def filling(theme=L, ax=False):
    rows = f'<div style="margin-top:32px">{bag_rows(LINES, flash="Organic bananas")}</div>'
    return shop("connected", instore_hero(), rows,
                dock("Just walk out", "Charged as you leave", CARD, "$13.76", "4 items", lit=0.7, ax=ax), theme=theme, ax=ax)

def leaving():
    rows = f'<div style="margin-top:32px">{bag_rows([l for l in LINES if l[4] == "sold"])}</div>'
    return shop("connected", instore_hero(leaving=True), rows,
                dock("Charging as you leave", None, CARD, "$13.76", "4 items", lit=0.75, leaving=True))

END = '<div class="cap muted">Thanks for shopping at Bayside Grocer. Your bag is ready for next time.</div>'
END_DUE = '<div class="cap muted">You’ll find this pass under Last trip.</div>'

def receipt(due=False, theme=L, live=True):
    st = (check_circle("var(--bad)", bang=True) + '<span>Payment due at Bayside Grocer</span>') if due else \
         (check_circle("var(--good)") + '<span>Paid as you left Bayside Grocer</span>')
    dueline = '<div class="cap bad" style="margin-top:4px">No card on file, so nothing was charged.</div>' if due else ""
    qr = qr_svg("SMARTBAG-EXIT:K7Q2X9:1041:1376")
    items = "".join(f'<div style="display:flex;gap:12px;align-items:center;padding:12px 0"><div class="grow"><div class="body ink">{n}</div>'
                    f'<div class="cnum muted" style="margin-top:4px">{q} × <span class="cmono">{p}</span></div></div><span class="bmono ink">{t}</span></div><div class="hair"></div>'
                    for n, q, p, t in [("Whole milk", 1, "$4.69", "$4.69"), ("Sourdough loaf", 1, "$6.49", "$6.49"), ("Organic bananas", 2, "$1.29", "$2.58")])
    inner = (f'<div class="scroll" style="padding-top:0;text-align:center"><div style="padding-top:48px;display:flex;justify-content:center">{mark(56, lit=0 if due else 1)}</div>'
             f'<div style="margin-top:24px;display:flex;flex-direction:column;align-items:center;gap:8px">'
             f'<div class="dmono ink">$13.76</div><div class="body ink" style="display:flex;gap:8px;align-items:baseline">{st}</div>'
             f'<div class="cap muted">Today, 2:14 PM{"" if due else " · " + CARD}</div>{dueline}</div>'
             f'<div style="margin-top:32px;background:var(--surface);border-radius:16px;box-shadow:inset 0 0 0 1px var(--hair);padding:24px;display:flex;flex-direction:column;align-items:center">'
             f'<div class="cap muted">Exit pass</div><div style="margin-top:16px;background:#fff;border-radius:12px;padding:12px">{qr}</div>'
             f'<div class="bmono ink" style="margin-top:16px;letter-spacing:4px">K7Q2X9</div>'
             f'<div class="cap muted" style="margin-top:4px">{"Show this to staff to pay." if due else "Show this if staff ask to check your bag."}</div></div>'
             f'<div style="margin-top:32px;text-align:left"><div class="cnum muted" style="padding-bottom:8px">4 items</div><div class="hair"></div>{items}'
             f'<div style="display:flex;padding:12px 0"><span class="bodys ink grow">Total</span><span class="bmono ink" style="font-weight:600">$13.76</span></div>'
             f'<div class="cap muted">1 item not sold there, not charged.</div></div></div>'
             f'<div style="flex:none;background:var(--bg)"><div class="hair"></div><div class="bottom" style="display:flex;flex-direction:column;gap:8px;text-align:center"><div class="pill">Done</div>'
             f'{(END_DUE if due else END) if (live or due) else ""}</div></div>')
    return frame(inner, theme)

def qr_svg(text, size=160):
    try:
        import qrcode
        q = qrcode.QRCode(error_correction=qrcode.constants.ERROR_CORRECT_M, border=0)
        q.add_data(text); q.make()
        m = q.get_matrix(); n = len(m)
        rects = "".join(f'<rect x="{x}" y="{y}" width="1.02" height="1.02"/>' for y in range(n) for x in range(n) if m[y][x])
        return f'<svg width="{size}" height="{size}" viewBox="0 0 {n} {n}" shape-rendering="crispEdges"><g fill="#18181B">{rects}</g></svg>'
    except Exception:
        return f'<div style="width:{size}px;height:{size}px;background:repeating-conic-gradient(#18181B 0 25%,#fff 0 50%) 0 0/16px 16px"></div>'

def due_pass_in_store():
    rows = f'<div style="margin-top:32px">{bag_rows([l for l in LINES if l[4] == "sold"][:2])}</div>'
    last = ('<div style="margin-top:32px"><div class="cap muted" style="padding-bottom:8px">Last trip</div><div class="hair"></div>'
            f'<div class="row"><div class="grow"><div class="body ink">Bayside Grocer</div><div class="cap bad" style="margin-top:4px">Payment due · Show to staff</div></div>'
            f'<div class="bmono ink">$13.76</div>{sym("chevron.right", 13, "var(--faint)")}</div><div class="hair"></div></div>')
    return shop("connected", instore_hero(), last + rows, dock("Just walk out", "Charged as you leave", CARD, "$9.07", "3 items"))

SCREENS = [
    ("01-welcome", "Welcome (first run)", welcome),
    ("02-permission-bluetooth", "Permission 1 of 3: Bluetooth", perm_bt),
    ("03-permission-location", "Permission 2 of 3: Location", perm_loc),
    ("04-permission-notifications", "Permission 3 of 3: Notifications", perm_notif),
    ("05-finding-store", "Finding your store", finding),
    ("06-not-in-store", "Not in a store (nearest + last trip)", away),
    ("07-location-off", "Location off", locoff),
    ("08-offline", "Offline", offline),
    ("09-in-store-empty", "In store, empty bag", empty_bag),
    ("10-bag-problem", "Bag problem + Precise Location off", bag_problem),
    ("11-filling", "Filling: item just added (flash), item not sold here", filling),
    ("12-leaving", "Leaving: walk-out in progress", leaving),
    ("13-receipt-paid", "Receipt: paid as you left (peak + end)", receipt),
    ("14-receipt-due", "Receipt: payment due", lambda: receipt(due=True)),
    ("15-filling-dark", "Filling, dark mode", lambda: filling(D)),
    ("16-receipt-dark", "Receipt, dark mode", lambda: receipt(theme=D)),
    ("17-filling-ax", "Filling, accessibility text size (dock: title, line, total)", lambda: filling(ax=True)),
    ("18-due-pass-in-store", "Back in the store with a payment-due pass", due_pass_in_store),
]

# ---------------------------------------------------------------- 1.2: accounts, setup, pairing, wallet, trips, tabs
SYM.update({
 "chevron.left": '<path d="M15 5l-7 7 7 7"/>',
 "checkmark": '<path d="M5 12.5l4.5 4.5L19 7.5"/>',
 "tab.bag": '<path d="M5.2 8.5h13.6l-.9 11.2a1.3 1.3 0 0 1-1.3 1.2H7.4a1.3 1.3 0 0 1-1.3-1.2z"/><path d="M9 8.5V7a3 3 0 0 1 6 0v1.5"/>',
 "tab.trips": '<rect x="5" y="3" width="14" height="18" rx="2.5"/><path d="M9.5 8.5h6M9.5 12h6M9.5 15.5h6"/><circle cx="7.6" cy="8.5" r=".3"/><circle cx="7.6" cy="12" r=".3"/><circle cx="7.6" cy="15.5" r=".3"/>',
 "tab.card": '<rect x="2.5" y="5.5" width="19" height="13" rx="2.5"/><path d="M2.5 9.5h19M6 15h4"/>',
 "tab.person": '<circle cx="12" cy="8" r="3.6"/><path d="M4.8 20.2a7.2 7.2 0 0 1 14.4 0"/>',
})

def tabbar(sel):
    items = [("tab.bag", "Shop"), ("tab.trips", "Trips"), ("tab.card", "Wallet"), ("tab.person", "Account")]
    cells = "".join(f'<div style="flex:1;display:flex;flex-direction:column;align-items:center;gap:2px;padding-top:6px;color:{"var(--ink)" if i == sel else "var(--faint)"}">'
                    f'{sym(icon, 26, "currentColor")}<span style="font:500 10px/12px Inter">{label}</span></div>' for i, (icon, label) in enumerate(items))
    return (f'<div style="flex:none;background:var(--bg);box-shadow:inset 0 1px 0 var(--hair);height:83px;display:flex">{cells}</div>')

def flowbar(back=False, close=None, step=None):
    left = ""
    bar = lambda s: '<div style="display:flex;gap:4px">' + "".join(
        f'<span style="width:24px;height:4px;border-radius:2px;background:{"var(--ink)" if i <= s[0] else "var(--hair)"}"></span>' for i in range(1, s[1] + 1)) + '</div>'
    if back and step and not close:
        close_html = bar(step)
    else:
        close_html = None
    if back:
        left = f'<div style="display:flex;align-items:center;gap:4px;margin-left:-4px">{sym("chevron.left", 17, "var(--ink)")}<span class="body ink">Back</span></div>'
    elif step:
        left = "".join(f'<span style="width:24px;height:4px;border-radius:2px;background:{"var(--ink)" if i <= step[0] else "var(--hair)"}"></span>'
                       for i in range(1, step[1] + 1))
        left = f'<div style="display:flex;gap:4px">{left}</div>'
    right = f'<span class="body ink" style="min-width:44px;text-align:right">{E(close)}</span>' if close else (close_html or "")
    left = left or "<span></span>"
    return f'<div style="display:flex;align-items:center;justify-content:space-between;min-height:44px;padding:8px 24px 0">{left}{right}</div>'

def field(label, value="", placeholder="", hint=None, error=None, kind="text", trailing=None, focused=False, show=False):
    num = ' style="font-variant-numeric:tabular-nums"' if kind == "number" else ""
    val = (f'<span class="body ink"{num}>{E(value)}</span>' if value
           else f'<span class="body" style="color:var(--faint);opacity:.75">{E(placeholder)}</span>')
    if kind == "password" and value and not show:
        val = '<span class="body ink" style="letter-spacing:2px">' + "•" * len(value) + '</span>'
    tr = f'<span class="cap faint">{E(trailing)}</span>' if trailing else ""
    sw = '<span class="act" style="margin-right:-8px">Show</span>' if kind == "password" else ""
    line = "var(--bad)" if error else ("var(--ink)" if focused else "var(--hair)")
    under = (f'<div class="cap bad" style="margin-top:4px">{E(error)}</div>' if error else
             (f'<div class="cap faint" style="margin-top:4px">{E(hint)}</div>' if hint else ""))
    return (f'<div><div class="cap muted">{E(label)}</div><div style="display:flex;align-items:center;gap:8px;min-height:44px;margin-top:4px">'
            f'<div class="grow">{val}</div>{tr}{sw}</div><div style="height:1px;background:{line}"></div>{under}</div>')

def form_dock(title, error=None, extra="", busy=False):
    err = f'<div class="cap bad" style="text-align:left">{E(error)}</div>' if error else ""
    pill = '<div class="pill"><span class="spin" style="border-color:rgba(255,255,255,.35);border-top-color:var(--onInk)"></span></div>' if busy else f'<div class="pill">{E(title)}</div>'
    return f'<div class="bottom" style="padding-top:12px;padding-bottom:42px;display:flex;flex-direction:column;gap:8px">{err}{pill}{extra}</div>'

def text_action(t): return f'<div class="bodys ink" style="min-height:44px;display:flex;align-items:center;justify-content:center;text-align:center">{E(t)}</div>'

def title_block(t, lead_=None, top=16):
    l = f'<div class="body muted" style="margin-top:12px">{E(lead_)}</div>' if lead_ else ""
    return f'<div style="margin-top:{top}px"><div class="display">{E(t)}</div>{l}</div>'

def section(label, trailing=None, top=48):
    tr = f'<span class="cnum muted">{E(trailing)}</span>' if trailing else ""
    return (f'<div style="margin-top:{top}px;display:flex;justify-content:space-between;padding-bottom:8px"><span class="cap muted">{E(label)}</span>{tr}</div>'
            '<div class="hair"></div>')

def row(inner, chevron=True, extra_pad=0):
    ch = sym("chevron.right", 13, "var(--faint)") if chevron else ""
    return f'<div class="row" style="padding:{12 + extra_pad}px 0">{inner}{ch}</div><div class="hair"></div>'

def two(top, bottom, top_cls="body ink", bottom_cls="cap muted"):
    return f'<div class="grow"><div class="{top_cls}">{top}</div><div class="{bottom_cls}" style="margin-top:4px">{bottom}</div></div>'

def screen(inner_scroll, bottom="", theme=L, top="", ax=False, pad_top=0):
    return frame(f'{top}<div class="scroll" style="padding-top:{pad_top}px">{inner_scroll}</div>{bottom}', theme, ax=ax)

# -- signed out
def welcome_out(theme=L):
    beats = "".join(f'<div style="display:flex;gap:16px;align-items:baseline"><span class="body faint" style="width:16px;font-variant-numeric:tabular-nums">{n}</span>'
                    f'<div><div class="bodys ink">{t}</div><div class="cap muted" style="margin-top:4px">{w}</div></div></div>'
                    for n, t, w in [(1, "Drop it in", "Put groceries in your bag as you shop."),
                                    (2, "It knows", "Each item shows up here with the store’s price."),
                                    (3, "Walk out", "Your card is charged as you leave the store.")])
    lock = (f'<div style="display:flex;align-items:flex-start;gap:4px"><div style="margin-left:-4px">{mark(32)}</div>'
            f'<div style="margin-top:3.2px">{wordmark(35.2)}</div></div>')
    inner = (f'<div style="padding-top:40px">{lock}</div><div class="display" style="margin-top:32px">The bag that<br>checks you out.</div>'
             f'<div class="body muted" style="margin-top:12px">No lines. No scanning.</div>'
             f'<div style="display:flex;flex-direction:column;gap:16px;margin-top:32px">{beats}</div>'
             f'<div class="cap faint" style="margin-top:32px">Demo: payments are simulated. No money moves.</div>')
    bottom = f'<div class="bottom" style="padding-top:16px;padding-bottom:42px;display:flex;flex-direction:column;gap:4px"><div class="pill">Create account</div>{text_action("I already have an account")}</div>'
    return screen(inner, bottom, theme)

def create_account(theme=L, error=False, ax=False):
    fields = (field("Name", "Scott Chiang") + field("Email", "scott@bagitwith.us", error=None) +
              field("Password", "short" if error else "", hint="At least 8 characters", kind="password",
                    error="Use at least 8 characters for your password." if error else None, focused=not error))
    inner = title_block("Create account") + f'<div style="display:flex;flex-direction:column;gap:24px;margin-top:24px">{fields}</div>'
    return screen(inner, form_dock("Create account", "That email already has an account. Log in instead." if error else None,
                                   text_action("Log in instead")), theme, flowbar(back=True), ax=ax)

def log_in(theme=L):
    fields = field("Email", "scott@bagitwith.us") + field("Password", "", kind="password", focused=True)
    inner = title_block("Log in") + f'<div style="display:flex;flex-direction:column;gap:24px;margin-top:24px">{fields}</div>'
    return screen(inner, form_dock("Log in", "Email or password is wrong.", text_action("Create an account")), theme, flowbar(back=True))

# -- setup
def setup_perm(theme=L):
    inner = (f'<div style="padding-top:48px;margin-left:-6px">{mark(48, lit=0)}</div><div class="display" style="margin-top:32px">Connect your bag</div>'
             f'<div class="body muted" style="margin-top:12px">Bag It uses Bluetooth to hear what you drop in your bag.</div>')
    return screen(inner, '<div class="bottom" style="padding-top:16px;padding-bottom:42px"><div class="pill">Allow Bluetooth</div></div>', theme, flowbar(step=(1, 3)))

NEAR = [("Bag 3F2A", "Right here", ""), ("Bag 91C0", "Nearby", "Yours"), ("Bag", "Farther away", "")]

def pair_dock(setup=True, problem=False):
    parts = ""
    if problem: parts += '<div class="pill">Open Settings</div>'
    if setup: parts += text_action("Set up later")
    parts += text_action("No bag with you? Use a demo bag")
    return f'<div class="bottom" style="padding-top:12px;padding-bottom:42px;display:flex;flex-direction:column;gap:4px">{parts}</div>'

def pair_scan(theme=L, state="list", setup=True):
    head = title_block("Pair your bag", "Turn your bag on and hold it near your phone.", top=24)
    if state == "off":
        body = '<div class="body bad" style="margin-top:32px">Turn on Bluetooth to connect your bag.</div>'
    else:
        body = section("Bags nearby", top=32)
        if state == "list":
            for n, s, y in NEAR:
                tail = f'<span class="cap faint">{y}</span>' if y else ""
                body += row(f'{mark(24, lit=0)}{two(n, s)}{tail}')
        elif state == "none":
            body += ('<div style="margin-top:16px"><div class="bodys ink">No bags found</div>'
                     '<div class="cap muted" style="margin-top:4px">Make sure it’s switched on and close to your phone.</div>'
                     '<span class="act" style="justify-content:flex-start;padding-left:0;margin-left:0">Scan again</span></div>')
    top = flowbar(step=(2, 3)) if setup else flowbar(close="Cancel")
    return screen(head + body, pair_dock(setup, False), theme, top)   # "Turn on Bluetooth": Settings can't, so no pill

def pair_check(theme=L):
    inner = (f'<div style="padding-top:48px;margin-left:-12px">{mark(96, lit=1)}</div>'
             + title_block("Is the light on your bag blinking?", "Bag 3F2A blinks its light three times.", top=32))
    bottom = '<div class="bottom" style="padding-top:12px;padding-bottom:42px;display:flex;flex-direction:column;gap:8px"><div class="pill">Yes, that’s my bag</div><div class="pill sec">No, try another</div></div>'
    return screen(inner, bottom, theme, flowbar(back=True, step=(2, 3)))

def pair_name(theme=L):
    inner = title_block("Name your bag", top=24) + f'<div style="margin-top:32px">{field("Name", "Scott’s bag", hint="Bag 3F2A", focused=True)}</div>'
    return screen(inner, form_dock("Pair bag"), theme, flowbar(back=True, step=(2, 3)))

def pair_done(theme=L):
    inner = (f'<div style="padding-top:48px;margin-left:-12px">{mark(96, lit=1)}</div>'
             + title_block("Bag paired", "Your bag is ready. Drop something in and it shows up here.", top=32))
    return screen(inner, form_dock("Continue"), theme, flowbar(step=(2, 3)))

def add_card(theme=L, errors=False, ax=False):
    if errors:
        num = field("Card number", "4242 4242 4242 4241", kind="number", trailing="Visa", error="Check the card number.")
        exp = field("Expiry", "09/26", kind="number", error="This card has expired.")
        cvc = field("Security code", "12", kind="number", error="Enter the 3-digit security code.")
    else:
        num = field("Card number", "4242 4242 4242 4242", kind="number", trailing="Visa")
        exp = field("Expiry", "12/29", kind="number")
        cvc = field("Security code", "123", kind="number", focused=True)
    pair = f'<div style="display:flex;{"flex-direction:column;" if ax else ""}gap:24px;align-items:flex-start"><div style="flex:1;width:100%">{exp}</div><div style="flex:1;width:100%">{cvc}</div></div>'
    nick = field("Nickname", "Demo", placeholder="Optional")
    toggle = ('<div style="display:flex;align-items:center;gap:12px"><div class="grow"><div class="body ink">Pay with this card</div>'
              '<div class="cap muted" style="margin-top:4px">Your first card pays when you walk out.</div></div>'
              '<span style="width:51px;height:31px;border-radius:16px;background:var(--ink);opacity:.4;position:relative;flex:none">'
              '<span style="position:absolute;right:2px;top:2px;width:27px;height:27px;border-radius:50%;background:var(--bg)"></span></span></div>')
    foot = ('<div class="cap faint">Payments are simulated. Only the card type and last four digits are saved; the full number never leaves your phone.</div>'
            '<span class="act" style="justify-content:flex-start;padding-left:0;margin-top:-12px">Use demo card</span>')
    inner = title_block("Add a card") + f'<div style="display:flex;flex-direction:column;gap:24px;margin-top:24px">{num}{pair}{nick}{toggle}{foot}</div>'
    return screen(inner, form_dock("Add card", extra=text_action("Add later")), theme, flowbar(step=(3, 3)), ax=ax)

# -- tabs
def dock_tabbed(html_):
    return html_.replace('class="dock" style="', 'class="dock" style="padding-bottom:16px;').replace('class="dock">', 'class="dock" style="padding-bottom:16px">')

CARD_CHEV = CARD + '&nbsp;' + sym("chevron.right", 11, "var(--faint)").replace('style="', 'style="vertical-align:-1px;')

def shop_tab(theme=L, variant="card"):
    if variant == "card":
        rows = f'<div style="margin-top:32px">{bag_rows(LINES, flash="Organic bananas")}</div>'
        d = dock("Just walk out", "Charged as you leave", CARD_CHEV, "$13.76", "4 items", lit=0.7)
        body = shop_body("connected", instore_hero(), rows)
    elif variant == "nocard":
        rows = f'<div style="margin-top:32px">{bag_rows([l for l in LINES if l[4] == "sold"])}</div>'
        d = dock("Add a card to just walk out", None, None, "$13.76", "4 items", err="No card on file. If you leave now, you’ll pay at the store.", fix="Add card")
        body = shop_body("connected", instore_hero(), rows)
    else:  # unpaired, empty bag in store
        d = dock("Pair your bag to start", fix="Pair your bag", tone="var(--unlit)")
        body = shop_body("pair", instore_hero(), empty_state("No bag paired yet", "Pair your bag and items show up here as you drop them in."))
    return frame(body + dock_tabbed(d) + tabbar(0), theme)

def shop_body(chipstate, hero, body=""):
    c = chip(chipstate) if chipstate != "pair" else '<span class="chip"><span class="dot" style="background:var(--faint)"></span><span class="ink">Pair your bag</span></span>'
    return scroll(topbar(c) + f'<div style="margin-top:32px">{hero}</div>' + body)

def sheet(under, inner, height=520, theme=L):
    """A .sheet with detents [.medium, .large]: the shop/tab dimmed behind, the system grabber on top."""
    grab = '<div style="position:absolute;top:5px;left:50%;margin-left:-18px;width:36px;height:5px;border-radius:3px;background:var(--unlit)"></div>'
    return frame(f'<div style="position:absolute;inset:0;top:47px;display:flex;flex-direction:column;background:var(--bg)">{under}</div>'
                 '<div style="position:absolute;inset:0;background:rgba(0,0,0,.4);z-index:4"></div>'
                 f'<div style="position:absolute;left:0;right:0;bottom:0;height:{height}px;background:var(--bg);border-radius:12px 12px 0 0;display:flex;flex-direction:column;z-index:5">{grab}{inner}</div>', theme)

def card_row(brand, last4, sub, check=False, large=False, chevron=False):
    ck = sym("checkmark", 17, "var(--ink)") if check else ""
    inner = (f'<div class="grow"><div style="display:flex;gap:8px;align-items:baseline"><span class="{"bodys" if large else "body"} ink">{brand}</span>'
             f'<span class="body ink" style="font-variant-numeric:tabular-nums">•••• {last4}</span></div><div class="cnum muted" style="margin-top:4px">{sub}</div></div>{ck}')
    return row(inner, chevron=chevron, extra_pad=4 if large else 0)

def card_picker(theme=L):
    under = shop_body("connected", instore_hero(), f'<div style="margin-top:32px">{bag_rows(LINES)}</div>')
    inner = (flowbar(close="Done") + '<div style="padding:0 24px">' + title_block("Pay with") + section("Your cards", top=32)
             + card_row("Visa", "4242", "Demo · Expires 12/29", check=True) + card_row("Mastercard", "4444", "Expires 01/31")
             + '<div class="pill sec" style="margin-top:24px">Add a card</div></div>')
    return sheet(under, inner, 560, theme)

TRIPS = [("Today", [("Bayside Grocer", "2:14 PM · 4 items", "$13.76", False), ("Mission Fresh", "11:02 AM · 1 item", "$6.49", True)]),
         ("Yesterday", [("Bayside Grocer", "6:40 PM · 3 items", "$9.07", False)]),
         ("Thursday, Oct 22", [("Bayside Grocer", "5:55 PM · 6 items", "$24.31", False)])]

def trips(theme=L, empty=False):
    inner = title_block("Trips", top=24)
    if empty:
        inner += f'<div style="margin-top:24px">{empty_state("No trips yet", "Your receipts show up here after you walk out.")}</div>'
    else:
        for day, rs in TRIPS:
            inner += section(day, top=32)
            for store, sub, total, due in rs:
                s = f'{sub}<span class="bad"> · Payment due</span>' if due else sub
                inner += row(f'<div class="grow"><div class="body ink">{store}</div><div class="cnum muted" style="margin-top:4px">{s}</div></div><span class="bmono ink">{total}</span>')
    return frame(scroll(inner) + tabbar(1), theme)

def wallet(theme=L, empty=False, ax=False):
    inner = title_block("Wallet", top=24)
    if empty:
        inner += f'<div style="margin-top:24px">{empty_state("No cards yet", "Add a card and leaving the store pays for you.")}</div><div class="pill" style="margin-top:32px">Add a card</div>'
    else:
        inner += (section("Pays when you walk out", top=32) + card_row("Visa", "4242", "Demo · Expires 12/29", large=True, chevron=True)
                  + section("Other cards", top=32) + card_row("Mastercard", "4444", "Expires 01/31", chevron=True)
                  + card_row("Amex", "0005", "Work · Expires 03/28", chevron=True) + '<div class="pill sec" style="margin-top:32px">Add a card</div>')
    inner += '<div class="cap faint" style="margin-top:16px">Payments are simulated. No money moves.</div>'
    return frame(scroll(inner) + tabbar(2), theme, ax=ax)

def card_sheet(theme=L):
    under = wallet(theme).split('<div class="phone')[1]
    under_inner = scroll(title_block("Wallet", top=24) + section("Pays when you walk out", top=32) + card_row("Visa", "4242", "Demo · Expires 12/29", large=True, chevron=True)) + tabbar(2)
    inner = (flowbar(close="Done") + '<div style="padding:0 24px;flex:1"><div style="margin-top:16px;display:flex;gap:8px;align-items:baseline"><span class="bodys ink">Visa</span><span class="bmono ink">•••• 4242</span></div>'
             '<div class="cnum muted" style="margin-top:4px">Demo · Expires 12/29</div><div class="cap muted" style="margin-top:12px">Pays when you walk out.</div>'
             '<div class="bodys ink" style="margin-top:32px">Remove Visa •••• 4242?</div>'
             '<div class="cap muted" style="margin-top:4px">Mastercard •••• 4444 will pay when you walk out.</div></div>'
             '<div style="padding:12px 24px 42px;display:flex;flex-direction:column;gap:8px"><div class="pill" style="background:var(--bad)">Remove card</div><div class="pill sec">Keep card</div></div>')
    return sheet(under_inner, inner, 470, theme)

def account(theme=L):
    bags = (row(f'{two("Scott’s bag", "Bag 3F2A")}<span style="display:flex;gap:8px;align-items:center"><span class="dot" style="background:var(--good)"></span><span class="cap muted">Connected</span></span>')
            + row(f'{two("Kitchen bag", "Bag 91C0")}<span class="cap muted">Not nearby</span>') + row('<div class="grow body ink">Pair a bag</div>'))
    perms = (row('<div class="grow body ink">Bluetooth</div><span class="cap muted">On</span>') + row('<div class="grow body ink">Location</div><span class="cap muted">While using</span>')
             + row('<div class="grow body ink">Notifications</div><span class="cap muted">Off</span>'))
    inner = ('<div style="margin-top:24px"><div class="display">Scott Chiang</div><div class="body muted" style="margin-top:4px">scott@bagitwith.us</div></div>'
             + section("Your bags", top=32) + bags + section("Permissions", top=32) + perms
             + '<div style="margin-top:32px"><div class="body ink" style="min-height:56px;display:flex;align-items:center">Sign out</div><div class="hair"></div>'
               '<div class="body bad" style="min-height:56px;display:flex;align-items:center">Delete account</div><div class="hair"></div></div>'
             + '<div class="cnum faint" style="margin-top:24px">Bag It 1.2 (3)</div>')
    return frame(scroll(inner) + tabbar(3), theme)

def bag_sheet(theme=L):
    under = scroll('<div style="margin-top:24px"><div class="display">Scott Chiang</div></div>' + section("Your bags", top=32)) + tabbar(3)
    inner = (flowbar(close="Done") + f'<div style="padding:0 24px;flex:1">{title_block("Scott’s bag", "Bag 3F2A")}<div style="margin-top:24px">{field("Name", "Scott’s bag")}</div></div>'
             '<div style="padding:12px 24px 42px;display:flex;flex-direction:column;gap:8px"><div class="pill" style="opacity:.4">Save name</div><div class="pill sec" style="color:var(--bad)">Unpair</div></div>')
    return sheet(under, inner, 520, theme)

def delete_account(theme=L):
    inner = title_block("Delete account", "This removes your account, your saved cards and your paired bags. Receipts stay with the stores you shopped at.") + \
            f'<div style="margin-top:24px">{field("Password", "correcthorse", kind="password")}</div>'
    bottom = '<div class="bottom" style="padding-top:12px;padding-bottom:42px;display:flex;flex-direction:column;gap:8px"><div class="cap bad">That password is wrong.</div><div class="pill" style="background:var(--bad)">Delete account</div></div>'
    return sheet(scroll('') , f'{flowbar(close="Cancel")}<div class="scroll" style="padding-top:0">{inner}</div>{bottom}', 784, theme)

SCREENS_12 = [
    ("20-welcome-signed-out", "Signed out: welcome", welcome_out),
    ("21-create-account", "Create account", create_account),
    ("22-create-account-errors", "Create account: field error + server error", lambda theme=L: create_account(theme, error=True)),
    ("23-log-in", "Log in: wrong password", log_in),
    ("24-setup-permissions", "Setup 1 of 3: permissions (Bluetooth)", setup_perm),
    ("25-pair-scan", "Setup 2 of 3: pair your bag, bags nearby", pair_scan),
    ("26-pair-none", "Pair: no bags found", lambda theme=L: pair_scan(theme, "none")),
    ("27-pair-bluetooth-off", "Pair: Bluetooth off (from Account)", lambda theme=L: pair_scan(theme, "off", setup=False)),
    ("28-pair-check-light", "Pair: is the light blinking?", pair_check),
    ("29-pair-name", "Pair: name your bag", pair_name),
    ("30-pair-done", "Pair: bag paired", pair_done),
    ("31-add-card", "Setup 3 of 3: add a card (demo card)", add_card),
    ("32-add-card-errors", "Add a card: local checks", lambda theme=L: add_card(theme, errors=True)),
    ("33-shop-tab", "Shop tab: paying with a card (tap to change)", shop_tab),
    ("34-shop-no-card", "Shop tab: no card on file", lambda theme=L: shop_tab(theme, "nocard")),
    ("35-shop-unpaired", "Shop tab: no bag paired", lambda theme=L: shop_tab(theme, "unpaired")),
    ("36-card-picker", "Card picker (from the dock)", card_picker),
    ("37-trips", "Trips", trips),
    ("38-trips-empty", "Trips: empty", lambda theme=L: trips(theme, empty=True)),
    ("39-wallet", "Wallet", wallet),
    ("40-wallet-empty", "Wallet: empty", lambda theme=L: wallet(theme, empty=True)),
    ("41-card-sheet-remove", "Card sheet: remove the default card", card_sheet),
    ("42-account", "Account", account),
    ("43-bag-sheet", "Bag sheet: rename / unpair", bag_sheet),
    ("44-delete-account", "Delete account", delete_account),
]
AX_12 = [("45-create-account-ax", "Create account, accessibility text size", lambda: create_account(L, ax=True)),
         ("46-wallet-ax", "Wallet, accessibility text size", lambda: wallet(L, ax=True))]

if __name__ == "__main__":
    out = sys.argv[1] if len(sys.argv) > 1 else "design/reference"
    os.makedirs(out, exist_ok=True)
    if "--1.2" in sys.argv:                     # 1.2 screens: light + dark, plus large text for sign-up and wallet
        made = []
        for slug, title, fn in SCREENS_12:
            for suffix, theme in (("", L), ("-dark", D)):
                open(os.path.join(out, slug + suffix + ".html"), "w").write(fn(theme=theme)); made.append(slug + suffix)
        for slug, title, fn in AX_12:
            open(os.path.join(out, slug + ".html"), "w").write(fn()); made.append(slug)
        print("\n".join(made))
    else:
        for slug, title, fn in SCREENS:
            open(os.path.join(out, slug + ".html"), "w").write(fn())
        print("\n".join(s for s, _, _ in SCREENS))
