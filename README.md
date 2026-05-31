# GPSD OSM Viewer

Minimal Android/Kotlin app that connects to a remote `gpsd` server over TCP/IP, reads JSON `TPV` position reports, and displays the fix on an OpenStreetMap map using osmdroid.

## What it does

- Connects to `gpsd` via IP/host and TCP port, default `2947`.
- Sends:

```text
?WATCH={"enable":true,"json":true};
```

- Parses `TPV` JSON reports.
- Uses `lat`/`lon` when `mode >= 2`.
- Shows position as a marker on an osmdroid/OpenStreetMap map.
- Displays basic speed/track/error fields when present.

## Server-side gpsd notes

On the machine/router/Raspberry Pi where the GNSS receiver is attached, `gpsd` must be reachable from the Android device. On many Linux setups gpsd only listens locally unless explicitly configured for remote clients.

Typical quick test on the gpsd host:

```bash
gpspipe -w -n 10
```

Typical manual gpsd test mode, adjust device path:

```bash
sudo systemctl stop gpsd gpsd.socket
sudo gpsd -N -G -D 3 /dev/ttyUSB0
```

Then from another machine:

```bash
nc <gpsd-host-ip> 2947
?WATCH={"enable":true,"json":true};
```

You should see JSON objects including `TPV`.

## Android build

Open this folder in Android Studio and run the `app` configuration.

The project uses:

- Android Gradle Plugin 9.2.1
- Built-in Kotlin support from AGP 9.x
- osmdroid 6.1.20
- kotlinx-coroutines-android 1.10.2

If your Android Studio is older, reduce the Android Gradle Plugin version in `build.gradle.kts` or create a fresh Android Studio project and copy `MainActivity.kt` plus the dependencies.

## OpenStreetMap tile usage

This prototype uses the public OSM tile layer through osmdroid. For long-term/public use, do not bulk-download or pre-seed tiles. Use a proper tile provider, your own tile server, or an offline vector-map solution if the app is used heavily or offline.

## Security note

The gpsd TCP protocol is plain text. Do not expose gpsd directly to the public Internet. Use LAN/VPN/SSH tunnel or a small authenticated gateway if the receiver is remote.
