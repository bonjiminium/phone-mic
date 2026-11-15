# Phone Mic for Android

Native Android microphone → pinned TLS WebSocket → Windows receiver → VB-CABLE.
Android 11/API 30 or newer. A microphone foreground service and partial CPU wake
lock support screen-off capture; a notification offers Stop.

## Install, pair, speak

1. On the PC, run `./run.sh serve`. Its private `.local/connect.html` page opens.
2. Install/update the reusable APK using the page’s **install** QR with your
   normal camera/browser. Open the APK download and tap Install, or transfer
   `.local/phone-mic-debug.apk` by USB. It is debug-signed. Revoke the browser’s
   unknown-app installer permission afterward.
3. Open Phone Mic → **Scan pairing QR**. Scan the page’s **pairing** QR using this
   in-app scanner. Camera access is used only for scanning.
4. Verify the receiver is your own PC. The confirmation explains that audio will
   start after consent. Tap **Pair & start microphone**, then allow microphone
   permission. Connection continues automatically—no second Start tap.
5. Speak: the phone meter shows local capture; the main status distinguishes
   capture from acknowledgement by the PC. On Windows select **CABLE Output
   (VB-Audio Virtual Cable)** and check that app’s microphone meter too.

Scanning, cancelling or parsing a QR alone never starts recording. Pairing is
saved only after confirmation. Notification permission is optional but recommended.
If microphone access is denied, the app explains how to retry or open settings.
You can cancel connecting, stop streaming in the app, or stop from the notification.

Daily use: start the PC receiver, open Phone Mic, tap **Start microphone**.
You can lock the phone while live. No boot start, automatic reconnect, recordings
or telemetry. Audio never resumes just because the app process was restarted.

## Status, recovery and settings

The home screen has one main action for each state: Scan, Start, Cancel, Stop,
Retry or Scan updated QR. The input meter shows local microphone activity, **not**
proof that a destination Windows app is using it. PC acknowledgements are shown
separately; an old acknowledgement does not remain “live” indefinitely.

**Settings & help** holds re-pairing, Forget receiver, Android permissions, and
connection help/diagnostics. Counters and safe exception class names are kept out
of the main workflow; raw URLs/tokens/exception messages are not shown.

Failures distinguish expired certificates, changed/unverified identity, revoked
pairing, busy receivers, unreachable PCs and capture/permission problems. Recovery
never recommends disabling TLS or installing a phone-wide CA.

Pairing survives restarts. PC address/certificate/credential changes require
scanning the current QR—no rebuild or reinstall. `serve` automatically replaces
an expired/missing/wrong-address leaf with a three-year certificate, preserving
the existing CA/token, and tells the user to re-pair. Older 14-day certificates
are upgraded when renewal is needed.

New QRs include an informational certificate expiration (epoch milliseconds).
The phone can display it offline; a successful pinned TLS handshake saves the
actual peer certificate expiration. Old QRs without this field remain valid.
Neither a QR date nor a saved date substitutes for certificate validation.

**Forget receiver** removes pairing from this phone only. To revoke all devices,
stop the receiver and run `./run.sh serve --reset-pairing`; re-scan on retained
phones. Per-device revocation is not implemented.

## Security boundary

- Scan only a QR on your own PC. A malicious QR could send audio to someone
  else’s correctly pinned server. Confirmation shows receiver and full SHA-256;
  the PC page’s expandable Security details has the matching fingerprint.
- Exact leaf pinning, certificate validity and normal hostname verification
  remain enabled. No trust-all fallback, CA trust change or HTTP audio.
- `.local/connect.html`, `connection.json`, `pairing.json`, and QR screenshots
  contain secrets; don’t share them. The APK contains no pairing credentials.
  Old pre-QR APKs did contain secrets and must remain private.
- Pairing storage is atomic and app-private under `noBackupFilesDir`, excluded
  from cloud/device backups. Uninstall or clearing data forgets pairing.
- QR origins/types/version/lengths/pins/optional expiry are validated. Invalid
  or cancelled scans preserve existing pairing. Pairing changes require stopping
  capture. The scanner is offline; barcode images/beeps are disabled and its
  screenshots/recents are blocked.
- APK download uses authenticated HTTPS; the reusable APK can also travel over
  USB. The PC dashboard is loopback-only with authenticated status and no CORS.
- Rooted/compromised devices are outside this boundary. No Bluetooth, location or
  Wi-Fi scanning permission is added. Target SDK 36 currently uses INTERNET for
  LAN access; newer Android permission behavior needs review when targeting it.

## Build and test

```sh
./android/bootstrap-tools.sh       # first build only
./android/build.sh                 # tests, lint, APK, no-secret audit
PHONE_MIC_TEST_RECEIVER=1 ./android/build.sh  # optional running-receiver tests
```

Output: `.local/phone-mic-debug.apk`, version **0.3.0-guided-setup**, versionCode 4.
Same application ID/debug signing key allows an in-place upgrade.

Pinned tools in `~/.cache/phone-mic-android` (override `PHONE_MIC_TOOLS`): Azul JDK
21, Gradle 9.3.1, SDK 36/build-tools 36.0.0, AGP 9.1.0. Dependencies include OkHttp
4.12.0 and ZXing Embedded 4.3.0; explicit AndroidX Core/Fragment dependencies cover
classes exposed/used by ZXing. Tests exercise both scanner launch and result flow.

Automated tests cover packet format, strict pairing/expiry parsing, atomic
storage, positive/negative TLS pin/hostname/expiry cases, actionable error
classification, and Android UI states/consent/permission continuation under
Robolectric. Optional integration tests exercise PC-generated QR → Java parser →
pinned authenticated HTTPS. The build runs lint and audits the APK for assets or
known local credentials before publishing it.

Verified for this UX update: **30 Android tests passed**, including all three
real-receiver integration tests; lint, APK build and the no-secret audit passed.
Also passed: 35 Windows Python tests, 15 WSL launcher/OpenSSL tests and 7 Node
tests. A real Windows TLS/WebSocket → VB-CABLE test delivered 400 packets with
zero underruns/drops/device errors; the tone was detected in all 15 windows.

Historical user verification established live dictation and locked/background
capture with an earlier native version. **The new UX still needs phone acceptance:**

1. Update APK; deny/cancel scanning once—no unintended pairing or recording.
2. Scan install QR by mistake—clear explanation, existing pairing unchanged.
3. Pair QR → cancel—no recording. Pair & start → grant mic—automatic connection.
4. Deny mic → retry/settings recovery; deny notifications → capture still works.
5. Speak: phone meter, PC input/cable meters, then Windows app meter respond.
6. Stop, relaunch, Start; lock screen; test notification Stop and cancellation.
7. Restart PC, expired certificate renewal and address changes; re-scan without
   reinstalling. Verify expiry warning and identity-change recovery.
8. Interrupt Wi-Fi/stop receiver: honest disconnected status, explicit Retry,
   no automatic recording restart. Test a busy receiver and revoked pairing.
9. Longer 15–30 minute session: battery, OEM restrictions, gaps, latency.

JVM tests cannot prove camera decoding, microphone permission/lifecycle behavior
on every real device, or sustained Wi-Fi performance. Measured latency and longer
native stability remain open checks.
