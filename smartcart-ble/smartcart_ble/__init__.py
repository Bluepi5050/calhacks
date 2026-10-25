"""SmartCart BLE: the Jetson side of the SmartCart <-> Bag It Bluetooth contract (v1.2).

    from hardware.smartcart_ble import SmartCartBLE      # or: from smartcart_ble import SmartCartBLE
    cart = SmartCartBLE().start()
    cart.publish("ADD", "apple", 0.93)

`protocol` and `session` are pure Python and import anywhere. `SmartCartBLE` / `BleTransport` need
python3-dbus + python3-gi (BlueZ over D-Bus) and are imported lazily, so the rest works without them.
"""
__version__ = "1.1.0"
CONTRACT_VERSION = "1.2"

from . import protocol  # noqa: E402,F401
from .protocol import SERVICE_UUID, CONTROL_UUID, EVENTS_UUID, STATUS_UUID  # noqa: E402,F401

__all__ = ["SmartCartBLE", "SmartCartProcess", "SmartCartError", "BleTransport", "protocol", "SERVICE_UUID", "CONTROL_UUID",
           "EVENTS_UUID", "STATUS_UUID", "__version__", "CONTRACT_VERSION"]


def __getattr__(name):
    if name == "SmartCartBLE":
        from .peripheral import SmartCartBLE
        return SmartCartBLE
    if name == "SmartCartError":
        from .peripheral_errors import SmartCartError
        return SmartCartError
    if name == "SmartCartProcess":
        from .process import SmartCartProcess
        return SmartCartProcess
    if name == "BleTransport":
        from .transport_adapter import BleTransport
        return BleTransport
    raise AttributeError(name)
