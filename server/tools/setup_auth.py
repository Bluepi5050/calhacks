#!/usr/bin/env python3
"""Finish SmartBag sign-in setup (run AFTER SpacetimeAuth is enabled for smartcart-5dt35 on the dashboard).

1. Creates (or reuses) a public web OAuth client "SmartBag Console".
2. Allows the console page as redirect / post-logout URI (www and bare mana.space).
3. Turns on email (magic link) login, sets display name and colours.
4. Tells the module which client id to accept (set_auth_clients, as the database owner).
5. Writes {authority, clientId} to Firebase app/spacetime/auth, which switches the console's Sign in on.
Idempotent. Usage: /workspace/.venv/bin/python /workspace/smartbag/tools/setup_auth.py
"""
import json, os, re, subprocess, sys
DB = "smartcart-5dt35"; NAME = "SmartBag Console"
OWNER = "JVe6HRQkCcQDCaSb9t5kMPKiY3j1"
PAGE = "/home/" + OWNER + "/apps/smart-bag"
URIS = ",".join(o + PAGE for o in ("https://www.mana.space", "https://mana.space"))
ENV = {**os.environ, "PATH": "/workspace/.local/bin:" + os.environ.get("PATH", ""), "HOME": "/workspace"}
def sh(*a, ok=False):
    p = subprocess.run(["spacetime", *a], capture_output=True, text=True, env=ENV)
    out = (p.stdout + p.stderr).strip()
    if p.returncode and not ok: sys.exit("FAILED: spacetime " + " ".join(a) + "\n" + out)
    return p.returncode, out
code, out = sh("auth", "client", "get", DB, "--name", NAME, ok=True)
if code:
    if "not enabled" in out: sys.exit("SpacetimeAuth is not enabled yet for " + DB + ": enable it on the dashboard first.")
    code, out = sh("auth", "client", "create", DB, "--name", NAME)
print(out)
client_id = ""
try:                                   # CLI prints {"success": true, "data": {"id": "client_...", ...}}
    j = json.loads(out[out.index("{"):])
    client_id = str((j.get("data") or {}).get("id") or "")
except Exception:
    pass
if not client_id:
    m = re.search(r'"id"\s*:\s*"(client_[A-Za-z0-9_\-]+)"', out) or re.search(r'client[_ ]?id["\s:=]+([A-Za-z0-9_\-]{6,})', out, re.I)
    client_id = m.group(1) if m else ""
if not client_id: sys.exit("Could not read the client id from the output above; set it manually.")
sh("auth", "client", "set", DB, "web", "true", "--name", NAME, ok=True)
sh("auth", "client", "set", DB, "redirect_uris", URIS, "--name", NAME)
sh("auth", "client", "set", DB, "post_logout_redirect_uris", URIS, "--name", NAME)
sh("auth", "config", "set", DB, "login.email", "true")
sh("auth", "config", "set", DB, "login.anonymous", "false", ok=True)   # email magic link only
sh("auth", "config", "set", DB, "display_name", "SmartBag")
sh("auth", "config", "set", DB, "color.primary", "#E8654A", ok=True)
sh("call", DB, "set_auth_clients", json.dumps(client_id))
sys.path.insert(0, "/opt/sandbox_libs")
from firebase_helper import firebase
firebase.update(OWNER + "/file-system/apps/smart-bag/app/spacetime/auth", {"authority": "https://auth.spacetimedb.com/oidc", "clientId": client_id,
                 "redirectUri": "https://www.mana.space" + PAGE})   # one canonical callback: always the www page URL
print("Sign-in is on. Client id:", client_id)
