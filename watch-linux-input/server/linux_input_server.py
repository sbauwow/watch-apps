#!/usr/bin/env python3
"""TCP server that receives 4-byte input packets from a Wear OS watch
and injects them as keyboard/scroll events via Linux uinput."""

import socket
import struct
import sys
from evdev import UInput, ecodes

# Protocol constants
TYPE_HEARTBEAT = 0x00
TYPE_KEY = 0x01
TYPE_GESTURE = 0x02
TYPE_ROTARY = 0x03

# Key value -> evdev keycode
KEY_MAP = {
    1: ecodes.KEY_ESC,
    2: ecodes.KEY_TAB,
    3: ecodes.KEY_PLAYPAUSE,
    4: ecodes.KEY_PREVIOUSSONG,
    5: ecodes.KEY_NEXTSONG,
    6: ecodes.KEY_VOLUMEUP,
    7: ecodes.KEY_VOLUMEDOWN,
}

# Gesture value -> evdev keycode
GESTURE_MAP = {
    1: ecodes.KEY_ENTER,
    3: ecodes.KEY_LEFT,
    4: ecodes.KEY_RIGHT,
    5: ecodes.KEY_UP,
    6: ecodes.KEY_DOWN,
}

PORT = 9877
SCROLL_AMOUNT = 3  # lines per rotary tick


def create_uinput():
    keys = list(KEY_MAP.values()) + list(GESTURE_MAP.values())
    cap = {
        ecodes.EV_KEY: keys,
        ecodes.EV_REL: [ecodes.REL_WHEEL, ecodes.REL_HWHEEL],
    }
    return UInput(cap, name="watch-linux-input", vendor=0x1234, product=0x5678)


def press_key(ui, keycode):
    """Inject a key down + up event."""
    ui.write(ecodes.EV_KEY, keycode, 1)
    ui.syn()
    ui.write(ecodes.EV_KEY, keycode, 0)
    ui.syn()


def scroll(ui, amount):
    """Inject vertical scroll. Positive = up, negative = down."""
    ui.write(ecodes.EV_REL, ecodes.REL_WHEEL, amount)
    ui.syn()


def handle_packet(ui, data):
    pkt_type, value, action, _ = struct.unpack("BBBB", data)

    if pkt_type == TYPE_HEARTBEAT:
        return

    if pkt_type == TYPE_KEY:
        keycode = KEY_MAP.get(value)
        if keycode:
            press_key(ui, keycode)
            print(f"  key: {ecodes.KEY[keycode]}")

    elif pkt_type == TYPE_GESTURE:
        keycode = GESTURE_MAP.get(value)
        if keycode:
            press_key(ui, keycode)
            print(f"  gesture: {ecodes.KEY[keycode]}")

    elif pkt_type == TYPE_ROTARY:
        if value == 1:    # CW -> scroll down
            scroll(ui, -SCROLL_AMOUNT)
            print("  rotary: scroll down")
        elif value == 2:  # CCW -> scroll up
            scroll(ui, SCROLL_AMOUNT)
            print("  rotary: scroll up")


def serve(host="0.0.0.0", port=PORT):
    ui = create_uinput()
    print(f"uinput device created: {ui.name}")

    srv = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    srv.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    srv.bind((host, port))
    srv.listen(1)
    print(f"Listening on {host}:{port}")

    try:
        while True:
            conn, addr = srv.accept()
            print(f"Connected: {addr}")
            try:
                buf = b""
                while True:
                    data = conn.recv(1024)
                    if not data:
                        break
                    buf += data
                    while len(buf) >= 4:
                        handle_packet(ui, buf[:4])
                        buf = buf[4:]
            except (ConnectionResetError, BrokenPipeError):
                pass
            finally:
                conn.close()
                print(f"Disconnected: {addr}")
    except KeyboardInterrupt:
        print("\nShutting down...")
    finally:
        ui.close()
        srv.close()


if __name__ == "__main__":
    port = int(sys.argv[1]) if len(sys.argv) > 1 else PORT
    serve(port=port)
