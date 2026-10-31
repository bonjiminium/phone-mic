# Android microphone with QR pairing

AudioRecord → certificate-pinned TLS WebSocket → Windows receiver/VB-CABLE.
A microphone foreground service and partial CPU wake lock support screen-off
capture. Start/Stop controls and a notification Stop action; no automatic
microphone start, boot start, automatic reconnect, recordings or telemetry.

## Install once, scan to pair

1. Start the Windows receiver; open its private `.local/connect.html` on the PC.
2. Install/update the reusable APK using the first QR/link (normal phone camera or
   browser), or transfer `.local/phone-mic-debug.apk` over USB. It is debug-signed.
   Revoke Chrome's permission to install unknown apps after installation.
3. Open Phone Mic → **Scan pairing QR**. Camera permission is requested only for
   scanning. Scan the second QR using this in-app scanner, not Chrome.
4. Compare receiver address and full certificate SHA-256 with the PC page. Confirm
   **Pair**. Parsing or scanning alone does not save pairing or start recording.
5. Tap **Start**, grant microphone permission (notifications recommended), then
   tap Start again. Stop the browser microphone first. Choose CABLE Output on PC.

The same APK works for different receivers: the URL, token and certificate pin
are scanned at runtime, not compiled into it. Upgrading from the old embedded-
credential APK requires scanning once. Same application ID/debug key permits an
in-place update. Pairing changes are disabled while streaming.

Pairing survives app/receiver/PC restarts. If the receiver address, certificate
or credential changes, stop, scan its updated QR, and confirm again—**no APK
rebuild or reinstall**. The development leaf certificate lasts 14 days; renew it
on the PC and re-pair when it expires. Certificate expiry/pin mismatch fail closed.
No CA installation, system trust changes, HTTP audio or trust-all fallback.

**Forget receiver** removes local pairing only. To revoke a lost phone's token,
stop the Windows receiver and run `./run.sh serve IP --reset-pairing`; then re-scan
on retained phones. This revokes all current phones/browser links together.
Credentials are not per-device yet.

## Security boundary

- Scan only the pairing QR displayed by your own PC. A malicious QR could point
  to an attacker's correctly pinned receiver. Confirmation shows address/pin,
  never the secret token. There is no remote pairing-QR endpoint or deep link.
- PC `.local/connect.html`, `connection.json`, `pairing.json` and QR screenshots
  contain secrets. Do not share them. The new APK contains **no** pairing secret;
  old APKs from versions before 0.2 did and must remain private.
- Phone credentials are stored atomically under app-private `noBackupFilesDir`,
  not assets, shared storage or cloud/device backups. Uninstall/clear app data
  forgets pairing. Rooted/compromised devices are outside this boundary.
- The bundled ZXing scanner works offline; it uses camera only in its scanning
  activity. Barcode images/beeps are disabled and scanner screenshots/recents
  are blocked. Cancelling, denying permission or scanning invalid input leaves
  current pairing unchanged. HTTPS origins, schema version, credential lengths
  and pins are validated before showing confirmation; no QR contents are logged.
- Normal hostname verification remains enabled alongside exact leaf pinning.
  Supported origins are IPv4/DNS + optional port, not IPv6 literals yet.
- APK download remains authenticated HTTPS via `/android.apk?token=...`, even
  though the new APK itself is reusable. Alternatively transfer via USB.

## Build / verification

```sh
cd ~/phone-mic
./android/bootstrap-tools.sh
./android/build.sh  # no running receiver or local pairing config required
PHONE_MIC_TEST_RECEIVER=1 ./android/build.sh  # opt-in real receiver tests
```

Tool cache: `~/.cache/phone-mic-android` (override `PHONE_MIC_TOOLS`), vendor-digest
checked downloads: Azul JDK 21, Gradle 9.3.1, Google SDK 36/build tools 36.0.0,
Android Gradle plugin 9.1.0. Minimum Android 11/API 30; target SDK 36.
Dependencies: OkHttp 4.12.0, bundled ZXing Android Embedded 4.3.0 and explicit
AndroidX Core 1.17.0 (required by the scanner but absent from its published POM).
Camera is the
only new permission beyond the earlier microphone/foreground/internet/wake-lock/
optional-notification permissions. No location/Bluetooth/Wi-Fi scanning.

Output: `.local/phone-mic-debug.apk`. Build verifies there are no bundled assets
or known local credentials in any decompressed APK member before publishing it.
Generated integration-test credentials are local/git-ignored, not APK inputs.

**Verified:** 18 JVM/Robolectric tests pass: packet format, positive/negative
pin/hostname/expiry TLS cases, strict QR parser, persistent/replaceable/forgettable
storage, real PC-generated QR → Java parser → pinned authenticated Windows HTTPS,
and scanner Activity launch/resource inflation/lifecycle on Android API 36.
The new framework-level scanner test reproduced a missing ContextCompat crash
before adding AndroidX Core and passes with it. Camera hardware still needs a
real phone; this test does not establish successful camera decoding.
APK signature verified; lint has zero errors (prototype/security-review warnings
remain). Backend has 27 passing Python tests; AudioWorklet has two passing tests.

Earlier native version has user-verified live dictation and locked-phone/
background capture. User confirmed the updated QR scanner no longer crashes on
the real phone. Successful QR decoding/confirmation, re-pairing and Android
no-backup storage persistence still require real-device verification. JVM tests
do not prove Android lifecycle.

Manual acceptance, in order:
1. Update APK, deny/cancel scanning once; verify no unintended pairing/recording.
2. Scan the PC pairing QR; inspect/confirm address/pin, then Start and dictate.
3. Stop/relaunch app: pairing retained. Start again; lock screen and verify speech.
4. Stop and Forget: Start disabled. Re-scan and confirm without reinstalling.
5. Later: PC restart, controlled credential reset/certificate renewal and re-scan,
   stop via notification, network interruption, 15–30 minute stability/counters.

No PC autostart, discovery, Windows installer, production APK release/signing,
automatic certificate renewal or automatic reconnect in this slice. The CPU wake
lock uses battery and is released on service teardown; OEM restrictions remain
subject to real-device testing. SDK 37+ LAN runtime permission flow is deferred
until targeting that platform; target <=36 currently inherits LAN access through
INTERNET per Android documentation.

References:
- https://developer.android.com/develop/background-work/services/fgs/service-types
- https://developer.android.com/privacy-and-security/local-network-permission
- https://github.com/journeyapps/zxing-android-embedded/tree/v4.3.0
