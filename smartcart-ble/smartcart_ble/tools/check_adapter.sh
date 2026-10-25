#!/usr/bin/env bash
# SmartCart BLE: check that this machine (Jetson Orin Nano, Jetson Linux R36 / Ubuntu 22.04) can be a
# Bluetooth LE peripheral.  Usage:  bash hardware/smartcart_ble/tools/check_adapter.sh [hci0]
# Read-only: it changes nothing. Each line is OK / WARN / FAIL with the fix.
ADAPTER="${1:-hci0}"
fail=0
ok()   { printf '  OK    %s\n' "$*"; }
warn() { printf '  WARN  %s\n' "$*"; }
bad()  { printf '  FAIL  %s\n' "$*"; fail=1; }
have() { command -v "$1" >/dev/null 2>&1; }

echo "SmartCart BLE adapter check ($ADAPTER)"

echo "- BlueZ"
if have bluetoothd || [ -x /usr/libexec/bluetooth/bluetoothd ] || [ -x /usr/lib/bluetooth/bluetoothd ]; then
  BD=$(command -v bluetoothd || ls /usr/libexec/bluetooth/bluetoothd /usr/lib/bluetooth/bluetoothd 2>/dev/null | head -1)
  V=$("$BD" -v 2>/dev/null)
  ok "bluetoothd $V ($BD)"
  case "$V" in 5.[0-4][0-9]) warn "BlueZ $V is old; 5.50+ recommended (Jetson R36 ships 5.64)";; esac
else
  bad "bluetoothd not found -> sudo apt install bluez"
fi
if have systemctl; then
  if systemctl is-active --quiet bluetooth 2>/dev/null; then ok "bluetooth.service active"
  else bad "bluetooth.service not running -> sudo systemctl enable --now bluetooth"; fi
  if systemctl cat bluetooth 2>/dev/null | grep -q 'ExecStart=.*\(-E\|--experimental\)'; then
    ok "bluetoothd runs with --experimental (not required for SmartCart)"
  fi
fi

echo "- Radio"
if have rfkill; then
  if rfkill list bluetooth 2>/dev/null | grep -q 'blocked: yes'; then
    bad "Bluetooth is rfkill-blocked -> sudo rfkill unblock bluetooth"
  elif rfkill list bluetooth 2>/dev/null | grep -q 'Bluetooth'; then ok "not rfkill-blocked"
  else warn "rfkill lists no Bluetooth device (no adapter? USB dongle unplugged? M.2 card firmware?)"; fi
fi

echo "- Adapter"
if have bluetoothctl; then
  SHOW=$(timeout 5 bluetoothctl show 2>&1)
  if echo "$SHOW" | grep -q '^Controller'; then
    echo "$SHOW" | sed -n '1p;/Name:/p;/Alias:/p;/Powered:/p;/Roles:/p;/Discoverable:/p' | sed 's/^/        /'
    echo "$SHOW" | grep -q 'Powered: yes' && ok "powered" || warn "not powered (SmartCartBLE powers it on; or: bluetoothctl power on)"
    if echo "$SHOW" | grep -q 'Advertising Features'; then
      ok "LE advertising supported ($(echo "$SHOW" | grep -m1 'SupportedInstances' | sed 's/.*SupportedInstances: *//') instances)"
    else
      warn "bluetoothctl shows no 'Advertising Features' (LE off, or this bluetoothctl is too old to show it)"
    fi
    echo "$SHOW" | grep -q 'Roles:.*peripheral' && ok "peripheral role" || true
  else
    bad "no controller: $(echo "$SHOW" | head -1)  -> is the adapter present? (lsusb / dmesg | grep -i blue)"
  fi
else
  bad "bluetoothctl missing -> sudo apt install bluez"
fi
if have btmgmt; then
  INFO=$(timeout 5 btmgmt --index "${ADAPTER#hci}" info 2>&1)
  if echo "$INFO" | grep -q 'supported settings'; then
    echo "$INFO" | grep -q 'supported settings:.* le' && ok "controller supports LE" || bad "controller has no LE support"
    echo "$INFO" | grep -q 'current settings:.* le' && ok "LE enabled" || warn "LE not enabled -> sudo btmgmt --index ${ADAPTER#hci} le on"
    echo "$INFO" | grep -q 'supported settings:.*advertising' && ok "advertising supported" || warn "no 'advertising' in supported settings"
  else
    warn "btmgmt info needs root for details (sudo $0)"
  fi
else
  warn "btmgmt not installed (optional; part of bluez on most distros)"
fi

echo "- Permissions (D-Bus policy for org.bluez)"
if [ "$(id -u)" = 0 ]; then ok "running as root"
elif id -nG | tr ' ' '\n' | grep -qx bluetooth; then ok "user $(id -un) is in group 'bluetooth'"
else warn "not root and not in group 'bluetooth' -> run with sudo, or: sudo usermod -aG bluetooth $(id -un) && re-login"; fi

echo "- Python (system python3, as on the Jetson)"
PY=${PYTHON:-/usr/bin/python3}
"$PY" -c 'import sys; print("        " + sys.version.split()[0])'
"$PY" -c 'import dbus' 2>/dev/null && ok "python3-dbus" || bad "python3-dbus missing -> sudo apt install python3-dbus"
"$PY" -c 'import gi; gi.require_version("GLib", "2.0"); from gi.repository import GLib' 2>/dev/null \
  && ok "python3-gi" || bad "python3-gi missing -> sudo apt install python3-gi"
if [ "$(id -u)" = 0 ] || id -nG | tr ' ' '\n' | grep -qx bluetooth; then
  "$PY" - <<'EOF' && ok "org.bluez reachable over D-Bus" || bad "cannot talk to org.bluez over the system bus"
import dbus, sys
try:
    objs = dbus.Interface(dbus.SystemBus().get_object("org.bluez", "/"), "org.freedesktop.DBus.ObjectManager").GetManagedObjects()
except Exception as e:
    print("        " + str(e)); sys.exit(1)
for path, ifaces in sorted(objs.items()):
    if "org.bluez.Adapter1" in ifaces:
        print("        %s  GattManager1=%s  LEAdvertisingManager1=%s" % (path, "org.bluez.GattManager1" in ifaces, "org.bluez.LEAdvertisingManager1" in ifaces))
EOF
fi

echo
if [ $fail = 0 ]; then echo "Looks good. Next: sudo python3 -m hardware.smartcart_ble.tools.fake_cart --demo"
else echo "Fix the FAIL lines above, then run this again."; fi
exit $fail
