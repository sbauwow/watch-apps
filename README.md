# Watch Apps

A collection of Wear OS apps for Galaxy Watch.

All apps target minSdk 30 / targetSdk 34, use Kotlin, Java 17, AGP 8.7.3, and are built with `./gradlew assembleDebug`.

---

## Apps

| App | Description | Connection |
|-----|-------------|------------|
| [watch-linux-input](#watch-linux-input) | Wireless D-pad + shortcut remote for Linux desktop | WiFi/TCP |

---

## watch-linux-input

Turns a Galaxy Watch into a wireless D-pad and shortcut remote for a Linux desktop. The watch sends input events over WiFi/TCP to a small Python server on the Linux machine, which injects them as real keyboard and scroll events via uinput. Works on X11, Wayland, and TTY.

### Watch UI

```
        [  UP  ]
  [LEFT] [ OK ] [RIGHT]
        [ DOWN ]

  [ESC] [TAB] [>||] [VOL]
```

- **D-pad** sends arrow keys + Enter
- **Rotary crown** sends scroll events (default) or volume up/down (when VOL toggled)
- **Long-press left/right** sends media previous/next
- **VOL button** toggles rotary between scroll and volume mode (orange tint when active)
- **Hardware back** sends ESC (long-press back exits the app)

### Wire Protocol

Fixed 4-byte TCP frames on port **9877**:

| Byte | Field | Values |
|------|-------|--------|
| 0 | Type | `0x00` heartbeat, `0x01` key, `0x02` gesture, `0x03` rotary |
| 1 | Value | Key/gesture/rotary ID |
| 2 | Action | `0x00` press |
| 3 | Reserved | `0x00` |

### Linux Server

```bash
cd watch-linux-input/server
pip install -r requirements.txt
sudo python3 linux_input_server.py
```

The server creates a virtual input device via uinput and injects keyboard/scroll events. Requires `sudo` or adding your user to the `input` group with a udev rule for `/dev/uinput`.

Test without the watch:

```bash
# Send Enter key
echo -ne '\x02\x01\x00\x00' | nc localhost 9877

# Send arrow down
echo -ne '\x02\x06\x00\x00' | nc localhost 9877
```

### Watch Setup

```bash
cd watch-linux-input && ./gradlew assembleDebug
adb connect <watch-ip>:5555
adb -s <watch-ip>:5555 install -r app/build/outputs/apk/debug/app-debug.apk
```

1. Launch on watch — enter your Linux machine's IP address
2. Tap **CONNECT**
3. Status shows connection state; use the D-pad to control your desktop

### Permissions

- `INTERNET`, `ACCESS_NETWORK_STATE`, `ACCESS_WIFI_STATE`, `CHANGE_WIFI_STATE` — TCP connection
- `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_CONNECTED_DEVICE` — keep alive in background
- `WAKE_LOCK` — keep WiFi active

### Dependencies

- `com.google.android.wearable:wearable:2.9.0` (compileOnly)

---

## Building

Each app is a standalone Gradle project:

```bash
cd <app-name>
./gradlew assembleDebug
```

APK output: `app/build/outputs/apk/debug/app-debug.apk`

Set your SDK path in `local.properties`:
```
sdk.dir=/path/to/your/android-sdk
```
