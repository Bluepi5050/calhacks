"""BlueZ GATT server + LE advertisement for the SmartCart service, and the public SmartCartBLE API.

    from hardware.smartcart_ble import SmartCartBLE
    cart = SmartCartBLE()            # first adapter, name SmartCart-XXXX from its address
    cart.start()                     # returns once BlueZ accepted the GATT service
    cart.publish("ADD", "apple", 0.93)    # -> seq, or None when no shopping session is active

D-Bus/GLib run in one background thread. publish(), status and stop() are thread-safe; every D-Bus
call and signal happens on that thread, in the order the session produced it.

Needs: BlueZ >= 5.50 (Jetson Linux R36 ships 5.64), python3-dbus, python3-gi, and permission to talk to
org.bluez on the system bus (root, or a user in the 'bluetooth' group; see README).
"""
from __future__ import annotations

import logging
import os
import threading
import time
from typing import Callable, Dict, List, Optional

import dbus
import dbus.exceptions
import dbus.service
from dbus.mainloop.glib import DBusGMainLoop
from gi.repository import GLib

from . import protocol as p
from .session import EVENTS, BUFFER_SIZE, STALE_AFTER_S, CartSession, Effects, Notice, SeqMemory

log = logging.getLogger("smartcart_ble")

BLUEZ = "org.bluez"
OBJ_MANAGER = "org.freedesktop.DBus.ObjectManager"
PROPS = "org.freedesktop.DBus.Properties"
ADAPTER = "org.bluez.Adapter1"
DEVICE = "org.bluez.Device1"
GATT_MANAGER = "org.bluez.GattManager1"
ADV_MANAGER = "org.bluez.LEAdvertisingManager1"
GATT_SERVICE = "org.bluez.GattService1"
GATT_CHRC = "org.bluez.GattCharacteristic1"
ADVERTISEMENT = "org.bluez.LEAdvertisement1"

TICK_S = 5
ADV_RETRY_S = (5, 10, 20, 30)          # backoff while advertising keeps failing
READVERTISE_DELAY_MS = 1500
DEFAULT_STATE_FILE = os.environ.get("SMARTCART_STATE_FILE") or os.path.expanduser(
    "~/.local/state/smartcart-ble/sessions.json")
_DEFAULT = object()

_mainloop_lock = threading.Lock()
_mainloop_ready = False


def _ensure_glib_mainloop() -> None:
    global _mainloop_ready
    with _mainloop_lock:
        if not _mainloop_ready:
            DBusGMainLoop(set_as_default=True)
            _mainloop_ready = True


from .peripheral_errors import SmartCartError  # noqa: E402  (re-exported)


class _BluezError(dbus.exceptions.DBusException):
    _dbus_error_name = "org.bluez.Error.Failed"


class _InvalidOffset(dbus.exceptions.DBusException):
    _dbus_error_name = "org.bluez.Error.InvalidOffset"


class _NotSupported(dbus.exceptions.DBusException):
    _dbus_error_name = "org.bluez.Error.NotSupported"


class _InvalidArgs(dbus.exceptions.DBusException):
    _dbus_error_name = "org.freedesktop.DBus.Error.InvalidArgs"


_NO_OPTIONS = dbus.Dictionary({}, signature="sv")   # typed: an untyped {} breaks when BlueZ's introspection lacks the method


def _bytes_arr(data: bytes) -> dbus.Array:
    return dbus.Array([dbus.Byte(b) for b in data], signature="y")


# ---- exported D-Bus objects ----------------------------------------------------------------------
class _App(dbus.service.Object):
    def __init__(self, bus, path):
        self.path = path
        self.services: List["_Service"] = []
        super().__init__(bus, path)

    @dbus.service.method(OBJ_MANAGER, out_signature="a{oa{sa{sv}}}")
    def GetManagedObjects(self):
        out = {}
        for svc in self.services:
            out[dbus.ObjectPath(svc.path)] = svc.properties()
            for ch in svc.chars:
                out[dbus.ObjectPath(ch.path)] = ch.properties()
        return out


class _Service(dbus.service.Object):
    def __init__(self, bus, path, uuid):
        self.path, self.uuid = path, uuid
        self.chars: List["_Characteristic"] = []
        super().__init__(bus, path)

    def properties(self):
        return {GATT_SERVICE: {
            "UUID": dbus.String(self.uuid),
            "Primary": dbus.Boolean(True),
            "Characteristics": dbus.Array([dbus.ObjectPath(c.path) for c in self.chars], signature="o"),
        }}

    @dbus.service.method(PROPS, in_signature="s", out_signature="a{sv}")
    def GetAll(self, interface):
        if interface != GATT_SERVICE:
            raise _InvalidArgs("unknown interface")
        return self.properties()[GATT_SERVICE]


class _Characteristic(dbus.service.Object):
    def __init__(self, bus, path, uuid, flags, service, *, on_read=None, on_write=None, on_notify=None):
        self.path, self.uuid, self.flags, self.service = path, uuid, flags, service
        self.notifying = False
        self._on_read, self._on_write, self._on_notify = on_read, on_write, on_notify
        super().__init__(bus, path)

    def properties(self):
        return {GATT_CHRC: {
            "UUID": dbus.String(self.uuid),
            "Service": dbus.ObjectPath(self.service.path),
            "Flags": dbus.Array(self.flags, signature="s"),
            "Notifying": dbus.Boolean(self.notifying),
        }}

    @dbus.service.method(PROPS, in_signature="s", out_signature="a{sv}")
    def GetAll(self, interface):
        if interface != GATT_CHRC:
            raise _InvalidArgs("unknown interface")
        return self.properties()[GATT_CHRC]

    @dbus.service.method(PROPS, in_signature="ss", out_signature="v")
    def Get(self, interface, name):
        props = self.GetAll(interface)
        if name not in props:
            raise _InvalidArgs("unknown property")
        return props[name]

    @dbus.service.signal(PROPS, signature="sa{sv}as")
    def PropertiesChanged(self, interface, changed, invalidated):
        pass

    @dbus.service.method(GATT_CHRC, in_signature="a{sv}", out_signature="ay")
    def ReadValue(self, options):
        if self._on_read is None:
            raise _NotSupported("read not supported")
        value = self._on_read(options)
        offset = int(options.get("offset", 0))
        if offset > len(value):
            raise _InvalidOffset("offset past end")
        return _bytes_arr(value[offset:])

    @dbus.service.method(GATT_CHRC, in_signature="aya{sv}")
    def WriteValue(self, value, options):
        if self._on_write is None:
            raise _NotSupported("write not supported")
        if int(options.get("offset", 0)) != 0:
            raise _InvalidOffset("CONTROL messages are written whole")
        self._on_write(bytes(int(b) for b in value), options)

    @dbus.service.method(GATT_CHRC)
    def StartNotify(self):
        if self.notifying:
            return
        self.notifying = True
        self.PropertiesChanged(GATT_CHRC, {"Notifying": dbus.Boolean(True)}, [])
        if self._on_notify:
            self._on_notify(self, True)

    @dbus.service.method(GATT_CHRC)
    def StopNotify(self):
        if not self.notifying:
            return
        self.notifying = False
        self.PropertiesChanged(GATT_CHRC, {"Notifying": dbus.Boolean(False)}, [])
        if self._on_notify:
            self._on_notify(self, False)

    def notify(self, payload: bytes) -> bool:
        """Send one notification (BlueZ turns a Value PropertiesChanged into an ATT notification)."""
        if not self.notifying:
            return False
        self.PropertiesChanged(GATT_CHRC, {"Value": _bytes_arr(payload)}, [])
        return True


class _Advertisement(dbus.service.Object):
    def __init__(self, bus, path, name):
        self.path, self.name = path, name
        self.variant = 0                     # 0: LocalName, 1: Includes local-name, 2: UUID only
        self.on_release = None
        super().__init__(bus, path)

    VARIANTS = ("local-name", "includes-local-name", "uuid-only")

    def properties(self):
        props = {
            "Type": dbus.String("peripheral"),
            "ServiceUUIDs": dbus.Array([p.SERVICE_UUID], signature="s"),
            "Discoverable": dbus.Boolean(True),
        }
        if self.variant == 0:
            props["LocalName"] = dbus.String(self.name)
        elif self.variant == 1:
            props["Includes"] = dbus.Array(["local-name"], signature="s")
        return {ADVERTISEMENT: props}

    @dbus.service.method(PROPS, in_signature="s", out_signature="a{sv}")
    def GetAll(self, interface):
        if interface != ADVERTISEMENT:
            raise _InvalidArgs("unknown interface")
        return self.properties()[ADVERTISEMENT]

    @dbus.service.method(ADVERTISEMENT)
    def Release(self):
        log.warning("BlueZ released the advertisement")
        if self.on_release:
            self.on_release()


# ---- public API ----------------------------------------------------------------------------------
class SmartCartBLE:
    """The SmartCart peripheral. See the module docstring and README for the contract."""

    def __init__(self, adapter: Optional[str] = None, name: Optional[str] = None, *,
                 set_alias: bool = True, stale_after: float = STALE_AFTER_S,
                 buffer_size: int = BUFFER_SIZE, readvertise_on_disconnect: bool = True,
                 on_session_start: Optional[Callable[[str, str, bool], None]] = None,
                 on_session_stop: Optional[Callable[[str, str], None]] = None,
                 strict: bool = False, object_path: str = "/org/smartcart",
                 state_file=_DEFAULT, disconnect_on_start: bool = True,
                 clock: Callable[[], float] = time.monotonic):
        if name is not None and not p.valid_device_name(name):
            raise ValueError(f"name must be 1-{p.MAX_DEVICE_NAME} printable chars without '|'")
        self._adapter_hint = adapter
        self._name_override = name
        self._set_alias = set_alias
        self._stale_after = stale_after
        self._buffer_size = buffer_size
        self._readvertise_on_disconnect = readvertise_on_disconnect
        # contract v1.2: a cart (re)start must look like a disconnect to the phone
        self._disconnect_on_start = disconnect_on_start
        self._stale_links: set = set()
        self._strict = strict
        self._root = object_path.rstrip("/")
        self._clock = clock
        # last seq per session id survives restarts (None: keep it in memory only)
        self._state_file = DEFAULT_STATE_FILE if state_file is _DEFAULT else state_file
        self._start_listeners: List[Callable[[str, str, bool], None]] = []
        self._stop_listeners: List[Callable[[str, str], None]] = []
        if on_session_start:
            self._start_listeners.append(on_session_start)
        if on_session_stop:
            self._stop_listeners.append(on_session_stop)

        self._lock = threading.RLock()
        self._session: Optional[CartSession] = None
        self._pending: List = []
        self._pending_notices: List[Notice] = []
        self._flush_scheduled = False

        self._thread: Optional[threading.Thread] = None
        self._loop = None
        self._ready = threading.Event()
        self._startup_error: Optional[BaseException] = None
        self._stopping = False

        self._bus = None
        self.adapter_path: Optional[str] = None
        self.address: Optional[str] = None
        self.name: Optional[str] = None
        self._app = self._svc = self._control = self._events = self._status_chr = self._adv = None
        self.gatt_registered = False
        self.advertising = False
        self._adv_attempt = 0
        self._adv_retry = None                     # GLib source id of the one pending advertising retry
        self._adv_gen = 0
        self._bluez_owner = None

        self._connected: set = set()      # Device1 paths connected to our adapter
        self._phones: set = set()         # connected devices that wrote CONTROL this connection
        self.mtu: Dict[str, int] = {}     # device path -> ATT MTU reported by BlueZ
        self._mtu_warned: set = set()
        self._read_cache: Dict[str, bytes] = {}   # STATUS value per device for long (offset) reads
        self._rereg_attempt = 0

    # -- listeners ----------------------------------------------------------------------------
    def add_session_start_listener(self, fn: Callable[[str, str, bool], None]) -> None:
        """fn(sid, store_id, resumed) runs on the Bluetooth thread. Keep it fast."""
        self._start_listeners.append(fn)

    def add_session_stop_listener(self, fn: Callable[[str, str], None]) -> None:
        """fn(sid, reason) with reason 'phone' | 'replaced' | 'stale' | 'local'."""
        self._stop_listeners.append(fn)

    # -- lifecycle ----------------------------------------------------------------------------
    def start(self, timeout: float = 20.0) -> "SmartCartBLE":
        """Find the adapter, export the GATT service, register it and start advertising.
        Blocks until BlueZ accepted the GATT application; raises SmartCartError on failure.
        Advertising failures are retried in the background (see logs)."""
        if self._thread is not None:
            return self
        _ensure_glib_mainloop()
        self._thread = threading.Thread(target=self._run, name="smartcart-ble", daemon=True)
        self._thread.start()
        if not self._ready.wait(timeout):
            raise SmartCartError("BlueZ did not answer within %.0f s (is bluetoothd running?)" % timeout)
        if self._startup_error is not None:
            err = self._startup_error
            self._thread.join(2)
            self._thread = None
            raise err
        return self

    def stop(self, timeout: float = 5.0) -> None:
        """End any session locally, unregister from BlueZ and stop the background thread."""
        if self._thread is None or self._loop is None:
            return
        if threading.current_thread() is self._thread:
            GLib.idle_add(self._shutdown, threading.Event())     # called from a listener: don't wait on ourselves
            return
        done = threading.Event()
        GLib.idle_add(self._shutdown, done)
        done.wait(timeout)
        self._thread.join(timeout)
        self._thread = None

    def __enter__(self):
        return self.start()

    def __exit__(self, *exc):
        self.stop()

    # -- scanner API --------------------------------------------------------------------------
    def publish(self, action, label, confidence=None) -> Optional[int]:
        """Send one confirmed crossing. action: ADD/REMOVE (or A/R); label: COCO class;
        confidence: 0..1 float or 0..100 int. Returns the event's seq, or None if it was dropped
        (no active session, not started, or invalid input when strict=False)."""
        with self._lock:
            if self._session is None:
                log.info("not started: dropped %s %s", action, label)
                return None
            try:
                seq, fx = self._session.publish(action, label, confidence)
            except ValueError as exc:
                if self._strict:
                    raise
                log.error("event not sent: %s", exc)
                return None
            self._queue(fx)
            self._schedule_flush()
        return seq

    @property
    def status(self) -> Optional[p.Status]:
        with self._lock:
            return self._session.status() if self._session else None

    @property
    def active(self) -> bool:
        with self._lock:
            return bool(self._session and self._session.active)

    @property
    def phone_connected(self) -> bool:
        with self._lock:
            return bool(self._phones)

    # -- background thread ----------------------------------------------------------------------
    def _run(self):
        try:
            self._loop = GLib.MainLoop()
            GLib.idle_add(self._setup)
            self._loop.run()
        except BaseException as exc:          # pragma: no cover - defensive
            self._fail(SmartCartError(f"Bluetooth thread crashed: {exc}"))

    def _fail(self, exc: BaseException):
        log.error("%s", exc)
        self._startup_error = exc
        self._ready.set()
        if self._loop is not None:
            self._loop.quit()

    def _setup(self):
        try:
            self._bus = dbus.SystemBus()
        except dbus.exceptions.DBusException as exc:
            self._fail(SmartCartError(f"cannot open the system D-Bus: {exc}"))
            return False
        try:
            self._find_adapter()
        except SmartCartError as exc:
            self._fail(exc)
            return False
        except dbus.exceptions.DBusException as exc:
            self._fail(SmartCartError(_explain(exc)))
            return False
        with self._lock:
            self._session = CartSession(self.name, clock=self._clock, buffer_size=self._buffer_size,
                                        stale_after=self._stale_after, memory=SeqMemory(self._state_file))
        self._export_objects()
        self._watch_devices()
        GLib.timeout_add_seconds(TICK_S, self._tick)
        self._register_with_bluez()
        self._bluez_owner = self._bus.get_name_owner(BLUEZ)
        self._bus.watch_name_owner(BLUEZ, self._on_bluez_owner)
        return False

    def _find_adapter(self):
        try:
            objs = dbus.Interface(self._bus.get_object(BLUEZ, "/"), OBJ_MANAGER).GetManagedObjects()
        except dbus.exceptions.DBusException as exc:
            if exc.get_dbus_name() == "org.freedesktop.DBus.Error.ServiceUnknown":
                raise SmartCartError("bluetoothd is not running (sudo systemctl start bluetooth)") from None
            raise
        adapters = sorted(str(path) for path, ifaces in objs.items() if ADAPTER in ifaces)
        if not adapters:
            raise SmartCartError("no Bluetooth adapter found (check `bluetoothctl list`, rfkill, firmware)")
        if self._adapter_hint:
            hint = self._adapter_hint
            match = [a for a in adapters if a == hint or a.endswith("/" + hint)]
            if not match:
                raise SmartCartError(f"adapter {hint} not found; available: {', '.join(adapters)}")
            path = match[0]
        else:
            capable = [a for a in adapters if GATT_MANAGER in objs[a] and ADV_MANAGER in objs[a]]
            path = (capable or adapters)[0]
        props = objs[path][ADAPTER]
        self.adapter_path = path
        self.address = str(props.get("Address", ""))
        self.name = self.name or self._name_override or p.device_name_from_address(self.address)
        adapter_props = dbus.Interface(self._bus.get_object(BLUEZ, path), PROPS)
        if not bool(props.get("Powered", False)):
            log.info("powering on %s", path)
            try:
                adapter_props.Set(ADAPTER, "Powered", dbus.Boolean(True))
            except dbus.exceptions.DBusException as exc:
                raise SmartCartError("adapter is off and could not be powered on: " + _explain(exc)) from None
        if self._set_alias and str(props.get("Alias", "")) != self.name:
            try:
                adapter_props.Set(ADAPTER, "Alias", dbus.String(self.name))
            except dbus.exceptions.DBusException as exc:
                log.warning("could not set adapter alias to %s (%s); phones may show another name",
                            self.name, _explain(exc))
        self._stale_links = set()
        for dev_path, ifaces in objs.items():
            dev = ifaces.get(DEVICE)
            if dev and str(dev_path).startswith(path + "/") and bool(dev.get("Connected", False)):
                self._connected.add(str(dev_path))
                self._stale_links.add(str(dev_path))      # connected before this registration
        log.info("adapter %s (%s), advertising as %s", path, self.address, self.name)

    def _export_objects(self):
        bus, root = self._bus, self._root
        self._app = _App(bus, root)
        self._svc = _Service(bus, root + "/service0", p.SERVICE_UUID)
        self._control = _Characteristic(bus, root + "/service0/char0", p.CONTROL_UUID, ["write"], self._svc,
                                        on_write=self._on_control_write)
        self._events = _Characteristic(bus, root + "/service0/char1", p.EVENTS_UUID, ["notify"], self._svc,
                                       on_notify=self._on_notify_change)
        self._status_chr = _Characteristic(bus, root + "/service0/char2", p.STATUS_UUID, ["read", "notify"],
                                           self._svc, on_read=self._on_status_read,
                                           on_notify=self._on_notify_change)
        self._svc.chars = [self._control, self._events, self._status_chr]
        self._app.services = [self._svc]
        self._adv = _Advertisement(bus, root + "/advertisement0", self.name)
        self._adv.on_release = self._on_adv_released

    def _register_with_bluez(self):
        adapter_obj = self._bus.get_object(BLUEZ, self.adapter_path)
        dbus.Interface(adapter_obj, GATT_MANAGER).RegisterApplication(
            dbus.ObjectPath(self._app.path), _NO_OPTIONS,
            reply_handler=self._on_app_registered, error_handler=self._on_app_error)

    def _on_app_registered(self):
        self.gatt_registered = True
        self._rereg_attempt = 0
        self._disconnect_stale_links()
        log.info("GATT service %s registered", p.SERVICE_UUID)
        self._ready.set()
        self._adv_attempt = 0
        self._register_adv()

    def _on_app_error(self, exc):
        self.gatt_registered = False
        if not self._ready.is_set():
            self._fail(SmartCartError("BlueZ refused the GATT service: " + _explain(exc)))
        else:
            log.error("BlueZ refused the GATT service: %s", _explain(exc))
            self._schedule_reregister()

    def _schedule_reregister(self):
        delay = ADV_RETRY_S[min(self._rereg_attempt, len(ADV_RETRY_S) - 1)]
        self._rereg_attempt += 1
        log.info("registering with BlueZ again in %d s", delay)
        GLib.timeout_add_seconds(delay, self._reregister)

    def _reregister(self):
        """After bluetoothd came back: find the adapter again (it may appear late), power it, register."""
        if self._stopping or not self._bluez_owner:
            return False
        try:
            self._find_adapter()
            self._register_with_bluez()
        except (SmartCartError, dbus.exceptions.DBusException) as exc:
            log.warning("BlueZ not ready yet (%s)", exc if isinstance(exc, SmartCartError) else _explain(exc))
            self._schedule_reregister()
        return False

    def _disconnect_stale_links(self):
        """Contract v1.2 section 3: at startup and after re-registering, drop links that predate this
        registration, so the phone runs its reconnect + reconcile (its subscriptions to the old GATT
        objects are gone). Devices that connect after registration are left alone."""
        links, self._stale_links = self._stale_links, set()
        if not self._disconnect_on_start:
            return
        for path in sorted(links):
            log.info("disconnecting %s (connected before this cart registered) so the phone reconnects", path)
            try:
                dbus.Interface(self._bus.get_object(BLUEZ, path), DEVICE).Disconnect(
                    reply_handler=lambda: None,
                    error_handler=lambda e, path=path: log.warning("could not disconnect %s: %s", path, _explain(e)))
            except dbus.exceptions.DBusException as exc:
                log.warning("could not disconnect %s: %s", path, _explain(exc))

    def _schedule_adv_retry(self, delay):
        """At most one advertising retry is pending: a failed register and BlueZ's Release of the same
        advertisement both ask for one, and each retry chain would otherwise fork on every failure."""
        self._cancel_adv_retry()
        self._adv_retry = GLib.timeout_add_seconds(delay, self._adv_retry_due)

    def _cancel_adv_retry(self):
        if self._adv_retry is not None:
            GLib.source_remove(self._adv_retry)
            self._adv_retry = None

    def _adv_retry_due(self):
        self._adv_retry = None
        return self._register_adv()

    def _register_adv(self):
        self._cancel_adv_retry()
        if self._stopping or self._bus is None:
            return False
        gen = self._adv_gen
        mgr = dbus.Interface(self._bus.get_object(BLUEZ, self.adapter_path), ADV_MANAGER)
        mgr.RegisterAdvertisement(dbus.ObjectPath(self._adv.path), _NO_OPTIONS,
                                  reply_handler=lambda: self._on_adv_registered(gen),
                                  error_handler=lambda e: self._on_adv_error(gen, e))
        return False

    def _on_adv_registered(self, gen):
        if gen != self._adv_gen:
            return
        self.advertising = True
        self._adv_attempt = 0
        self._cancel_adv_retry()
        log.info("advertising %s (service UUID%s)", self.name,
                 {0: " + local name", 1: " + adapter name", 2: " only"}[self._adv.variant])

    def _on_adv_error(self, gen, exc):
        if gen != self._adv_gen:
            return
        self.advertising = False
        name = exc.get_dbus_name() if isinstance(exc, dbus.exceptions.DBusException) else ""
        if name == "org.bluez.Error.AlreadyExists":
            self.advertising = True
            return
        if name == "org.bluez.Error.InvalidLength" and self._adv.variant < 2:
            self._adv.variant += 1
            log.warning("advertisement too long, retrying as '%s'", _Advertisement.VARIANTS[self._adv.variant])
            self._register_adv()
            return
        # Some controllers can't advertise while a phone is connected. Only one phone uses the cart, so wait:
        # the disconnect handler advertises again.
        if self._connected_devices():
            log.info("advertising paused while a phone is connected; it resumes when the phone disconnects")
            return
        delay = ADV_RETRY_S[min(self._adv_attempt, len(ADV_RETRY_S) - 1)]
        self._adv_attempt += 1
        log.error("advertising failed (%s); retrying in %d s", _explain(exc), delay)
        self._schedule_adv_retry(delay)

    def _on_adv_released(self):
        self.advertising = False
        # BlueZ also releases an advertisement it failed to add; the error handler's backoff retry covers that.
        if not self._stopping and self._adv_retry is None:
            self._schedule_adv_retry(ADV_RETRY_S[0])

    def _readvertise(self, gen):
        """Some controllers stop advertising after a connection; refresh it once the cart is free."""
        if gen != self._adv_gen or self._stopping or self._connected or self._connected_devices():
            return False
        self._adv_gen += 1
        new_gen = self._adv_gen
        mgr = dbus.Interface(self._bus.get_object(BLUEZ, self.adapter_path), ADV_MANAGER)

        def again(*_):
            if new_gen == self._adv_gen:
                self._register_adv()
        mgr.UnregisterAdvertisement(dbus.ObjectPath(self._adv.path), reply_handler=again, error_handler=again)
        return False

    def _on_bluez_owner(self, owner):
        prev, self._bluez_owner = self._bluez_owner, owner
        if self._stopping or owner == prev:
            return
        if not owner:
            log.error("bluetoothd went away; waiting for it to come back")
            self.gatt_registered = self.advertising = False
            for chrc in (self._events, self._status_chr):
                if chrc is not None:
                    chrc.notifying = False
            with self._lock:
                self._read_cache.clear()
                self._connected.clear()
                self._phones.clear()
                if self._session:
                    self._session.set_phone_present(False)
            return
        log.info("bluetoothd is back; registering again")
        self._adv_gen += 1
        self._rereg_attempt = 0
        GLib.timeout_add(500, self._reregister)

    # -- device tracking ------------------------------------------------------------------------
    def _watch_devices(self):
        self._bus.add_signal_receiver(self._on_props_changed, dbus_interface=PROPS,
                                      signal_name="PropertiesChanged", arg0=DEVICE, path_keyword="path")
        self._bus.add_signal_receiver(self._on_ifaces_removed, dbus_interface=OBJ_MANAGER,
                                      signal_name="InterfacesRemoved")
        # A phone BlueZ hasn't seen before (Android rotates its address) appears already connected, with no
        # PropertiesChanged: without this it never counts as connected.
        self._bus.add_signal_receiver(self._on_ifaces_added, dbus_interface=OBJ_MANAGER,
                                      signal_name="InterfacesAdded")

    def _is_ours(self, path) -> bool:
        return bool(self.adapter_path) and str(path).startswith(self.adapter_path + "/")

    def _on_props_changed(self, interface, changed, invalidated, path=None):
        if interface != DEVICE or not self._is_ours(path) or "Connected" not in changed:
            return
        self._set_connected(str(path), bool(changed["Connected"]))

    def _on_ifaces_added(self, path, interfaces):
        dev = interfaces.get(DEVICE)
        if dev is not None and self._is_ours(path) and bool(dev.get("Connected", False)):
            self._set_connected(str(path), True)

    def _on_ifaces_removed(self, path, interfaces):
        if DEVICE in interfaces and self._is_ours(path):
            self._set_connected(str(path), False)

    def _set_connected(self, path: str, connected: bool):
        with self._lock:
            if connected:
                self._connected.add(path)
                log.info("device connected: %s", path)
            else:
                was = path in self._connected
                self._connected.discard(path)
                self._phones.discard(path)
                self._phones.discard("?")          # unknown writer: forget it on any disconnect
                self.mtu.pop(path, None)
                self._mtu_warned.discard(path)
                self._read_cache.pop(path, None)
                if was:
                    log.info("device disconnected: %s", path)
            if self._session:
                self._session.set_phone_present(bool(self._phones))
            free = not self._connected
        if not connected and free and (self._readvertise_on_disconnect or not self.advertising) and self.gatt_registered:
            gen = self._adv_gen
            GLib.timeout_add(READVERTISE_DELAY_MS, lambda: self._readvertise(gen))

    def _note_device(self, options, wrote_control: bool):
        dev = options.get("device")
        mtu = options.get("mtu")
        key = str(dev) if dev is not None else "?"
        if mtu is not None:
            self.mtu[key] = int(mtu)
            if int(mtu) < p.MIN_MTU and key not in self._mtu_warned:
                self._mtu_warned.add(key)
                log.warning("phone %s negotiated ATT MTU %d (< %d): notifications longer than %d bytes are "
                            "cut; the phone must requestMtu(247) before subscribing", key, int(mtu), p.MIN_MTU,
                            int(mtu) - 3)
        if wrote_control:
            if dev is not None:
                self._connected.add(key)
                self._phones.add(key)
            else:
                self._phones.update(self._connected or {"?"})

    # -- GATT callbacks (BlueZ thread) -----------------------------------------------------------
    def _on_status_read(self, options) -> bytes:
        with self._lock:
            self._note_device(options, wrote_control=False)
            key = str(options.get("device", "?"))
            if int(options.get("offset", 0)) == 0 or key not in self._read_cache:
                # a long read (MTU < 64) continues from the value read at offset 0, never a newer one
                self._read_cache[key] = self._session.status_bytes()
            return self._read_cache[key]

    def _on_control_write(self, value: bytes, options):
        with self._lock:
            self._note_device(options, wrote_control=True)
            self._session.set_phone_present(bool(self._phones))
            log.info("CONTROL <- %s", value[:64].decode("utf-8", "replace"))
            self._queue(self._session.handle_control(value))
            # notify after BlueZ has sent the write response (idle runs once WriteValue returned)
            self._schedule_flush()

    def _on_notify_change(self, chrc, on):
        log.info("%s notifications %s", "EVENTS" if chrc is self._events else "STATUS", "on" if on else "off")

    # -- outbound queue -------------------------------------------------------------------------
    def _queue(self, fx: Effects):
        self._pending.extend(fx.out)
        self._pending_notices.extend(fx.notices)

    def _schedule_flush(self):
        if not self._flush_scheduled and self._loop is not None:
            self._flush_scheduled = True
            GLib.idle_add(self._flush)

    def _flush(self):
        with self._lock:
            items, self._pending = self._pending, []
            notices, self._pending_notices = self._pending_notices, []
            self._flush_scheduled = False
        for item in items:
            chrc = self._events if item.channel == EVENTS else self._status_chr
            text = item.payload.decode("utf-8", "replace")
            if chrc is not None and chrc.notify(item.payload):
                log.info("%s -> %s", item.channel.upper(), text)
            else:
                log.info("%s (no subscriber, kept for resend) %s", item.channel.upper(), text)
        for n in notices:
            fns = self._start_listeners if n.kind == "start" else self._stop_listeners
            for fn in list(fns):
                try:
                    fn(n.sid, n.store_id, n.resumed) if n.kind == "start" else fn(n.sid, n.reason)
                except Exception:
                    log.exception("session %s listener failed", n.kind)
        return False

    def _tick(self):
        if self._stopping:
            return False
        with self._lock:
            if self._session:
                self._queue(self._session.tick())
        self._flush()
        return True

    def _shutdown(self, done: threading.Event):
        self._stopping = True
        self._cancel_adv_retry()
        with self._lock:
            if self._session:
                self._session.stopping = True
                self._queue(self._session.stop_local("local"))
        self._flush()
        # The link belongs to bluetoothd, not to us: without this the phone stays connected to a cart that is
        # gone and keeps showing it as connected. Ask BlueZ itself, since _connected can miss new devices.
        links = self._connected_devices()
        pending = {"n": 2 + len(links)}

        def finish(*_):
            pending["n"] -= 1
            if pending["n"] <= 0:
                self._teardown(done)
        for path in links:
            log.info("disconnecting %s (cart stopping)", path)
            try:
                dbus.Interface(self._bus.get_object(BLUEZ, path), DEVICE).Disconnect(
                    reply_handler=finish, error_handler=finish)
            except dbus.exceptions.DBusException:
                finish()
        try:
            adapter_obj = self._bus.get_object(BLUEZ, self.adapter_path)
            dbus.Interface(adapter_obj, ADV_MANAGER).UnregisterAdvertisement(
                dbus.ObjectPath(self._adv.path), reply_handler=finish, error_handler=finish)
            dbus.Interface(adapter_obj, GATT_MANAGER).UnregisterApplication(
                dbus.ObjectPath(self._app.path), reply_handler=finish, error_handler=finish)
        except Exception:
            self._teardown(done)
            return False
        GLib.timeout_add(2000, lambda: (self._teardown(done), False)[1])
        return False

    def _connected_devices(self):
        """Device1 paths on our adapter that BlueZ reports as connected right now."""
        try:
            objs = dbus.Interface(self._bus.get_object(BLUEZ, "/"), OBJ_MANAGER).GetManagedObjects(timeout=2)
        except dbus.exceptions.DBusException:
            return sorted(self._connected)
        return sorted(str(p) for p, ifaces in objs.items()
                      if DEVICE in ifaces and self._is_ours(p) and bool(ifaces[DEVICE].get("Connected", False)))

    def _teardown(self, done: threading.Event):
        if done.is_set():
            return
        for obj in (self._adv, self._status_chr, self._events, self._control, self._svc, self._app):
            try:
                obj.remove_from_connection()
            except Exception:
                pass
        self.gatt_registered = self.advertising = False
        log.info("SmartCart BLE stopped")
        done.set()
        self._loop.quit()


def _explain(exc) -> str:
    """Turn a D-Bus error into a one-line hint."""
    name = exc.get_dbus_name() if isinstance(exc, dbus.exceptions.DBusException) else ""
    msg = exc.get_dbus_message() if isinstance(exc, dbus.exceptions.DBusException) else str(exc)
    hints = {
        "org.freedesktop.DBus.Error.AccessDenied": "permission denied: run as root or add the user to the "
                                                   "'bluetooth' group (see README)",
        "org.bluez.Error.NotPermitted": "BlueZ refused: adapter busy or LE peripheral role disabled",
        "org.bluez.Error.NotReady": "adapter not ready (powered off or blocked: rfkill unblock bluetooth)",
        "org.freedesktop.DBus.Error.ServiceUnknown": "bluetoothd is not running",
        "org.freedesktop.DBus.Error.UnknownMethod": "this BlueZ has no LE advertising/GATT manager on the "
                                                    "adapter (needs BlueZ >= 5.50, LE enabled)",
        "org.bluez.Error.AlreadyExists": "already registered (another SmartCart process is running?)",
    }
    hint = hints.get(name)
    return f"{name or type(exc).__name__}: {msg}" + (f" -> {hint}" if hint else "")
