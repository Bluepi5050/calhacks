#!/bin/sh
# Compiles the platform-independent SmartBag core with swiftc and runs the CLI checks
# (offline codec tests, then one live walk-out trip against smartcart-5dt35).
# Needs a Swift 5.9+ toolchain on PATH (on the Mana sandbox: /tmp/swift/swift-6.0.3-RELEASE-ubuntu24.04/usr/bin).
# Usage: tools/verify-linux.sh [--offline]
set -e
cd "$(dirname "$0")/.."
mkdir -p .build
# Reference bytes for CallProcedure(7, "shop_locate", f64 37.7897, f64 -122.3972, f64 15) built independently in Python.
EXPECT_FRAME=$(python3 -c '
import struct
args = struct.pack("<ddd", 37.7897, -122.3972, 15.0)
name = b"shop_locate"
print((bytes([4]) + struct.pack("<I", 7) + bytes([0]) + struct.pack("<I", len(name)) + name + struct.pack("<I", len(args)) + args).hex())')
export EXPECT_FRAME
swiftc -swift-version 5 -O -parse-as-library \
  SmartBag/Core/SpacetimeClient.swift SmartBag/Core/BagProtocol.swift SmartBag/Core/Cards.swift Tests/LinuxCLI/main.swift \
  -o .build/walkout-cli
# Linux libcurl must support ws/wss for URLSessionWebSocketTask (Ubuntu 24.04's 8.5 does not);
# point LD_LIBRARY_PATH at a websocket-enabled build if one exists (see README).
[ -d /tmp/curl-ws/lib ] && export LD_LIBRARY_PATH=/tmp/curl-ws/lib${LD_LIBRARY_PATH:+:$LD_LIBRARY_PATH}
.build/walkout-cli "$@"
[ "$1" = "--offline" ] && exit 0

# ShopModel.swift and AccountModel.swift, verbatim except for their Apple-only imports, against stubs for CoreLocation/UIKit/BLE.
for f in ShopModel AccountModel; do
  sed -e '/^import CoreLocation/d' -e '/^import UIKit/d' -e '/^import Combine/d' SmartBag/Model/$f.swift > .build/$f.swift
done
swiftc -swift-version 5 -parse-as-library \
  SmartBag/Core/SpacetimeClient.swift SmartBag/Core/BagProtocol.swift SmartBag/Core/Cards.swift .build/ShopModel.swift .build/AccountModel.swift \
  Tests/ModelHarness/Stubs.swift Tests/ModelHarness/main.swift -o .build/model-harness
.build/model-harness
