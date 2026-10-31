# Native Android risk spike

Minimal vertical slice: **AudioRecord → pinned TLS WebSocket → existing Windows
receiver/VB-CABLE**, kept alive with the screen locked using a microphone
foreground service and partial CPU wake lock.

One Start/Stop screen and a persistent notification with Stop. No accounts,
pairing UI, automatic reconnect, boot start, background start, audio files,
telemetry, QR scanner, location permissions, or new Windows audio driver.

## Verification / acceptance

Built and APK signature verified. Nine JVM tests passed, including:
- exact 960-sample / 3,840-byte little-endian browser-compatible packet encoding;
- correct self-signed leaf certificate works without a system CA;
- incorrect pin, expired certificate and wrong hostname are rejected;
- actual Windows receiver accepts pinned HTTPS + its session token;
- actual Windows receiver is rejected with an incorrect pin.

Android lint: zero errors; warnings include intentionally custom leaf-pinning
trust manager, backup configuration and prototype-only untranslated strings.
Pinning implementation is small and negative cases are tested; normal OkHttp
hostname verification remains enabled. These JVM tests do **not** establish
Android lifecycle, permissions, microphone or screen-off behavior.

**Real-device results:** user verified native speech reaches the Windows app
for live dictation, and continues with the phone locked and app backgrounded.
Extended stability, counters, stop/restart and network-failure handling remain
unverified.

**Manual test checklist (including remaining checks):**
1. Stop the browser microphone. Install APK and explicitly start from its screen.
2. Grant microphone permission (notifications recommended); tap Start again.
3. Confirm Streaming and real speech through CABLE Output in a Windows recording.
4. Lock the phone for at least two minutes, speak again, and verify speech still
   reaches Windows. Then check gap/drop/device-error counters on reopening.
5. Stop using the notification. Verify Android microphone indicator disappears
   and the receiver disconnects. Start/stop again to check resources release.
6. Deliberately interrupt Wi-Fi. Verify a clear stopped/error state, rather than
   queued delayed speech or silent auto-recording. Reconnect manually.

Do not add pairing polish/reconnect or run as a workday microphone until this
remaining stability checks pass on the real phone. Short screen-off/background
capture is verified; workday reliability is not yet established.

## Build

Linux tooling is cached under `~/.cache/phone-mic-android` (override with
`PHONE_MIC_TOOLS`). JDK 21 from Azul, Gradle 9.3.1, Google Android SDK platform 36 /
build tools 36.0.0; Android Gradle plugin 9.1.0. No Android Studio needed.
Tool downloads are checked against vendor-published digests.

```sh
cd ~/phone-mic
./android/bootstrap-tools.sh  # fetch/cache tools and install SDK components
./android/build.sh            # unit tests, lint, APK
PHONE_MIC_TEST_RECEIVER=1 ./android/build.sh  # also real receiver TLS tests
```

Receiver must already have `.local/connection.json` and `.local/server.crt`.
`build.sh` generates private build assets with that session's WebSocket URL,
bearer token, and exact SHA-256 leaf-certificate pin. Output:
`.local/phone-mic-debug.apk` (debug-signed). The assets, build outputs, APK, token,
keys and local certificates are git-ignored. **Do not share this private APK**:
its pairing credential can be extracted from it. This is not a public release.

To install over the LAN, the existing receiver exposes `/android.apk?token=...`
over HTTPS, rejecting unauthenticated downloads. Refresh the browser mic page
using its full token-bearing URL, and use the Android download link. Android
may request permission for Chrome to install unknown apps; revoke that installer
permission afterward. The native app does **not** install any CA or alter the
phone's trust store. Alternatively transfer the APK over USB.

The phone must support Android 11+ (API 30). Target SDK is 36, the stable platform
available in the installed Google SDK feed. For apps targeting <=36, current
Android documentation says LAN access is implicit with INTERNET; no scan,
Bluetooth or location permission is requested. When targeting SDK 37+, add the
proper ACCESS_LOCAL_NETWORK runtime flow instead of assuming this remains true.

## Receiver restarts / security boundary

Normal receiver restart rotates its session token, invalidating this paired APK.
Rebuild/reinstall after rotation. For explicit development-only restarts that
must keep this spike paired, run Windows `receiver.py --host WINDOWS_LAN_IP
--reuse-session`; that opt-in reuses `.local/connection.json`'s previous token.
Host/port must match. Certificate replacement also requires rebuilding the APK;
expired certificates fail closed. There is no trust-all or HTTP fallback.

This preserves the current server and wire protocol. The app deliberately fails
rather than reconnecting automatically; return to the visible activity and press
Start. START_NOT_STICKY prevents OS-triggered automatic recording after a kill.
The send queue is bounded to roughly 200 ms, and a stalled network stops capture.

Permissions: microphone, internet, microphone foreground service, notifications
(optional at runtime), wake lock. CPU wake lock is held only during the explicit
foreground session and released on service teardown; it uses battery, so charge
for long sessions. No blanket battery-optimization exemption is requested in this
spike. OEM background restrictions still need real-device testing.

Sources:
- https://developer.android.com/develop/background-work/services/fgs/service-types
- https://developer.android.com/develop/background-work/services/fgs/restrictions-bg-start
- https://developer.android.com/privacy-and-security/local-network-permission
