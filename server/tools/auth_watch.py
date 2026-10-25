#!/workspace/.venv/bin/python
"""Waits for Scott to click "Use SpacetimeAuth" on the dashboard (spacetimedb.com -> smartcart-5dt35 -> SpacetimeAuth),
then runs setup_auth.py once, records the outcome in Firebase and removes itself (crontab line + .profile block).
Installed 2026-10-24 by the SmartBag backend chat. Log: /workspace/smartbag/tools/auth_watch.log"""
import os, re, subprocess, sys, time, datetime
ENV = {**os.environ, "PATH": "/workspace/.local/bin:/usr/bin:/bin", "HOME": "/workspace"}
LOG = "/workspace/smartbag/tools/auth_watch.log"
MARK = "# >>> smartbag-auth-watch >>>"; END = "# <<< smartbag-auth-watch <<<"
def log(m):
    with open(LOG, "a") as f: f.write(datetime.datetime.now().isoformat(timespec="seconds") + " " + m + "\n")
def uninstall():
    cur = subprocess.run(["crontab", "-l"], capture_output=True, text=True).stdout
    keep = "".join(l + "\n" for l in cur.splitlines() if "auth_watch.py" not in l)
    subprocess.run(["crontab", "-"], input=keep, text=True)
    p = "/workspace/.profile"
    if os.path.exists(p):
        s = open(p).read()
        s2 = re.sub(re.escape(MARK) + r".*?" + re.escape(END) + r"\n?", "", s, flags=re.S)
        if s2 != s: open(p, "w").write(s2)
r = subprocess.run(["spacetime", "auth", "client", "get", "smartcart-5dt35", "--name", "SmartBag Console"], capture_output=True, text=True, env=ENV, cwd="/tmp")
out = r.stdout + r.stderr
if "not enabled" in out: sys.exit(0)            # still waiting for the dashboard click
log("SpacetimeAuth looks enabled; running setup_auth.py")
s = subprocess.run(["/workspace/.venv/bin/python", "/workspace/smartbag/tools/setup_auth.py"], capture_output=True, text=True, env=ENV, cwd="/tmp")
res = (s.stdout + s.stderr)[-1500:]
log("setup_auth exit " + str(s.returncode) + ":\n" + res)
sys.path.insert(0, "/opt/sandbox_libs")
from firebase_helper import firebase
P = "JVe6HRQkCcQDCaSb9t5kMPKiY3j1/file-system/apps/smart-bag/app/pending-decisions"
now = int(time.time() * 1000)
if s.returncode == 0:
    firebase.update(P, {"outcomes/0": "DONE " + datetime.datetime.now().isoformat(timespec="minutes") + ": SpacetimeAuth enabled by Scott; auth_watch ran setup_auth.py automatically (" + res.strip().splitlines()[-1][:120] + "). Console Sign in is live.", "metadata/updatedAt": now})
    uninstall(); log("done, watcher removed")
else:
    firebase.update(P, {"outcomes/0": "setup_auth.py FAILED after SpacetimeAuth was enabled (see /workspace/smartbag/tools/auth_watch.log); ask Mana to 'finish SmartBag sign-in setup'.", "metadata/updatedAt": now})
    uninstall(); log("failed, watcher removed")
