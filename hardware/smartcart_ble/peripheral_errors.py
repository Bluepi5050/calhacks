"""Errors shared by peripheral.py and process.py (importable without dbus)."""


class SmartCartError(RuntimeError):
    """Bluetooth could not be set up (no adapter, no permission, bluetoothd not running, ...)."""
