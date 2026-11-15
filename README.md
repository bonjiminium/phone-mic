# Phone Mic

Use your Android phone as a Windows microphone over local Wi-Fi:
**Phone Mic → Windows receiver → VB-CABLE → your calling/dictation app.**

## Everyday use

From WSL:

```sh
cd ~/phone-mic
./run.sh serve
```

The receiver detects your Windows LAN address and opens **`.local/connect.html`**
in your Windows browser. If it cannot open the page, open that file manually.
Leave the terminal open; **Ctrl+C** stops the receiver.

On your phone, open Phone Mic and tap **Start microphone**. If not paired yet,
follow the setup page: install once, scan its pairing QR **inside Phone Mic**,
then confirm **Pair & start microphone**. Allow microphone access; capture
continues automatically without a second Start tap. Scanning alone does not
start recording. Notifications are optional but recommended for the Stop action.

Speak: the phone meter should move, and the PC page should show audio arriving
and being sent to the virtual cable. In your Windows app, choose:

**CABLE Output (VB-Audio Virtual Cable)**

Check **that app’s** microphone meter too. The receiver cannot verify which
microphone another app selected. While streaming, you can lock your phone.
Tap **Stop microphone** on the phone or **Stop** in its notification to finish.

Running `./run.sh` without arguments (or with `--help`) shows instructions without
starting tests, opening audio devices, or changing certificates.

## First-time PC setup

Prerequisites: WSL Bash/Python 3/OpenSSL, Windows Python 3.10 and Windows `uv`,
and [VB-CABLE](https://vb-audio.com/Cable/). Windows audio runs under **Windows
Python**, not WSL Python. Android 11 or newer is required for the native app.

1. Install VB-CABLE on a **Windows-local disk**, run its x64 installer as
   administrator, and reboot if requested. Installing from the WSL share failed
   on the original test machine; a Windows-local copy worked.
2. Run `./setup.sh` to create the Windows environment and dependencies.
3. Run `./run.sh check` to verify the virtual cable with a generated tone.
4. Build the reusable app if not already present: `./android/build.sh`
   (first run: `./android/bootstrap-tools.sh`). See [Android docs](android/README.md).
5. Run `./run.sh serve`. Missing certificates are created automatically.
6. If the phone cannot reach the PC, run `./run.sh doctor`. If needed, run
   `allow-firewall.ps1 -Ip WINDOWS_LAN_IP` from Windows PowerShell using its full
   WSL path. It asks for administrator approval and permits TCP 8764/8765 only on
   that local IP from `LocalSubnet`, including Public network profiles.
   **Do not disable the firewall or open the dashboard port.**

The page’s install QR uses the phone’s **normal camera/browser**. Download the
APK, then open it and tap Install; downloading alone does not install it. Android
may require allowing the browser to install unknown apps; revoke that permission
afterward. A development HTTPS warning may appear; verify the address is your
PC, or transfer `.local/phone-mic-debug.apk` by USB instead.
The app is debug-signed, reusable, and contains no PC credentials.

## Networking and certificates

`serve` detects connected physical Windows Wi-Fi/Ethernet adapters with a default
gateway, excluding virtual WSL/Hyper-V/VPN adapters. With multiple choices it asks
which network your phone shares. Without an interactive terminal it lists the
choices and exits. Override at any time:

```sh
./run.sh serve WINDOWS_LAN_IP
```

The receiver binds only that address: HTTPS audio on **8765**, public legacy CA
download on **8764**. The PC dashboard listens only on **127.0.0.1:8766** and uses
authenticated status requests; it is not accessible from your phone. Its token
stays in the URL fragment and Bearer header, not access logs. No CORS is enabled.
The setup file embeds this local dashboard and explains what to do when offline.

Startup checks the certificate/key, address match, ports and both cable endpoints
before announcing readiness. A missing APK is a warning, not a blocker for phones
that already have the app installed.

**Certificate maintenance is automatic during `serve`:** a missing, expired, or
wrong-address server certificate is created/renewed with a **3-year lifetime**.
An existing local CA and pairing token are preserved. Valid matching certificates
are unchanged, so ordinary restarts do not require re-pairing. Renewal prints a
notice: **scan the updated pairing QR**. An incomplete/corrupt CA fails safely
instead of replacing it. Keep your PC/phone clocks correct.

The phone shows the saved certificate expiration even while disconnected. New
QRs carry an informational expiry date; a successful pinned TLS connection updates
it from the actual certificate. Older QRs remain supported. Expiry/pin/hostname
checks are never bypassed. The native app does not need a phone-wide CA install.
A legacy development CA may have a shorter lifetime than the new leaf; the native
app trusts the exact leaf, not that CA. Browser/CA-based clients may still reject
that chain and are not the recommended path.

After renewal/address changes, reopen the **current** `.local/connect.html`;
an already-open tab may show an older QR. Address changes can also require an
updated scoped firewall rule.

## When something goes wrong

The phone offers a recovery action, not just an exception name:

- **Certificate expired:** run `./run.sh serve`, reopen the setup file, re-scan.
- **PC identity changed:** verify this is your PC and scan its current QR.
- **Pairing no longer valid:** scan again; no APK reinstall needed.
- **Receiver busy:** stop another phone/browser capture, then retry.
- **PC unreachable:** leave the receiver running; check shared Wi-Fi, guest
  network isolation, VPN and firewall. Run `./run.sh doctor`.
- **Permission denied:** use the permission prompt or Phone Mic → Settings & help
  → Android app permissions.
- **Audio arrives but Windows app is silent:** select CABLE Output, unmute the
  app, check its permission/input meter. `./run.sh check` tests the cable alone.

Phone **Settings & help** contains re-pairing, Forget receiver, connection help
and technical diagnostics. Forget removes pairing only from that phone; it does
not revoke other devices.

## Privacy and pairing

Only one phone/browser may stream at once. No default microphone is changed, no
audio is written to disk, and no audio is uploaded to internet services. Pairing
survives phone/receiver restarts. The receiver and microphone must be started
manually; there is no boot autostart or automatic reconnect.

`.local/connect.html`, `connection.json`, `pairing.json`, and screenshots of the
QRs contain credentials. **Do not share them.** Never share `.local/ca.key`.
The APK itself contains no credentials (old pre-QR versions did).

To revoke **all** paired phones/browser links, stop the receiver and run:

```sh
./run.sh serve --reset-pairing
```

Then scan the new QR on retained phones. Normal startup reuses the secret and
adopts legacy `connection.json` credentials on upgrade. `--reuse-session` remains
a compatibility flag; persistence is the default. Corrupt saved credentials fail
closed instead of silently rotating. Do not delete `.local/` to troubleshoot.

## Browser alternative

The setup page has a collapsed alternative browser link. Keep the page visible
and screen awake; screen-off capture is **not supported**. Stop it before using
the native app. Browser TLS handling varies; prefer the native app rather than
installing a development CA, which broadens phone trust.

On the original phone, Chrome’s `ERR_TOO_MANY_RETRIES` was resolved by granting
Chrome Nearby devices / Local network permission. Labels vary by Android.

## Development and verification

```sh
./run.sh test                         # Windows Python tests; no physical mic
python3 -m unittest test_run_sh test_certificates -v  # WSL launcher/OpenSSL tests
node --test web/*.test.cjs            # worklet and dashboard tests
./android/build.sh                   # JVM/Robolectric tests, lint, APK + secret audit
./run.sh list                        # Windows audio endpoints
./run.sh check                       # actual cable hardware test
./run.sh network-test                # 8 s through a running receiver; stop phone first
```

Wire protocol: authenticated TLS WebSocket `/audio?token=...`; first frame
`{"type":"start","sampleRate":48000,"channels":1,"format":"f32le"}`; reply
`{"type":"ready"}`; then 960 mono f32le samples / 3,840 bytes every 20 ms. Mono
is duplicated to the two cable channels. Buffer primes at 60 ms, is bounded at
200 ms, and discards old samples rather than allowing unbounded latency. Invalid
packets/non-finite samples are rejected. Five seconds without audio closes the
session. Reconnection is explicit; Stop clears buffered audio.

Previously user-verified: live Android/browser dictation and native screen-off
capture. A 60-second simulated TLS/WebSocket → cable test passed with no drops,
underruns or callback errors. These are historical results, not proof that the
new UI has been verified on a real phone. Current automated checks and remaining
real-device acceptance are described in [Android docs](android/README.md).
Measured latency and longer Wi-Fi stability remain unverified.

## Environment and cleanup

Windows `uv` uses `Lib`; Python 3.10 looks in `lib`. `setup.sh` merges these on
the case-sensitive WSL share. Do not run setup while the receiver runs.
Certificates, tokens, environments, generated pages, logs and vendor binaries
are git-ignored. VB-CABLE is third-party donationware, not bundled project code.

To remove the firewall rule, use elevated Windows PowerShell:
`Remove-NetFirewallRule -Name 'PhoneMic-Dev-WINDOWS_LAN_IP'`.
If you previously installed a development CA on Android, remove it from user
credentials when done. Remove `.local/` only when intentionally discarding
pairing and certificates; do not silently recreate a CA still trusted by a phone.
