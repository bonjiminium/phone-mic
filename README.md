# Phone mic

Android microphone → local Wi-Fi → Windows → **CABLE Output** microphone.
The browser prototype is verified. A minimal [native Android spike](android/README.md)
now has user-verified live dictation and continued capture with the phone locked
and app backgrounded. Extended native stability remains unverified. Project files live in `~/phone-mic`, including
the Windows Python environment.

## Verified so far

- Windows generated tone → VB-CABLE → microphone capture: **PASS**.
- 25 Python tests (persistent pairing, buffering, authentication, protocol, private APK download, cleanup, tone detection): **PASS**.
- 2 AudioWorklet packetization/downmix tests: **PASS**.
- 60-second simulated phone using real TLS/WebSocket → VB-CABLE capture:
  3,000 packets, zero buffer underruns, dropped samples, or playback callback errors;
  expected tone detected in all 119 analysis windows: **PASS**.
- Windows receiver reachable from WSL, with certificate verification enabled:
  authenticated status endpoint rejects anonymous access (401), public CA downloads (200).

- User verified actual Android Chrome capture over Wi-Fi and clear speech in a
  Windows recording app: **PASS**, without installing the development CA.

**Still needs verification:** measured end-to-end latency, longer-session Wi-Fi
stability, and screen-off/background operation (not supported by this prototype).
The simulated test runs on the PC and is not a substitute for phone testing.

## Setup / run

Prerequisites: WSL Bash/OpenSSL, Windows Python 3.10 and Windows `uv`, and VB-CABLE.
All Windows audio code runs under Windows Python, not WSL Python.

```sh
cd ~/phone-mic
./setup.sh                         # Windows uv environment + dependencies
./run.sh check                     # Windows cable-only hardware test
./make-cert.sh WINDOWS_LAN_IP      # once; private local CA and server certificate
./run.sh serve WINDOWS_LAN_IP      # foreground receiver; Ctrl+C to stop
```

The receiver binds only that LAN address: HTTPS on TCP 8765, public certificate
download on HTTP 8764. It does not change default microphones, record audio to
disk, or send it to internet services. Only one phone may stream at once.

### Windows Firewall

From Windows PowerShell, run `allow-firewall.ps1 -Ip WINDOWS_LAN_IP` using its
full WSL path if necessary. It requests administrator approval and permits only
TCP 8764/8765 on the selected local IP from `LocalSubnet`. The rule supports
Public Wi-Fi profiles but does **not** permit arbitrary internet sources.
No need to change the network profile or disable the firewall.

### Connect Android

**Permission gotcha found during phone testing:** Chrome's
`ERR_TOO_MANY_RETRIES` occurred before any request reached Windows. Granting
Chrome **Nearby devices / Local network** permission in Android app settings
resolved it. Check this before changing firewall rules. Menu labels depend on
Android version.

The user successfully streamed without installing the development CA. Do not
install it by default: it adds trust beyond this single server. Browser behavior
with self-signed TLS varies; microphone capture still requires a secure context.
A future native app should pin just the server certificate instead.

Open `.local/connect.html` on the Windows desktop. This private local page has
two QR codes. Skip the first (CA installation) unless you explicitly choose the
broader development trust described below; use the second for the mic page.

Optional CA-based setup, **not used in the verified phone test**:

1. Download the development CA from the first QR code. In Android Settings,
   search for **Install a certificate** → **CA certificate**, then select the
   downloaded `phone-mic-ca.crt`. Menu names vary by phone. Check its SHA-256
   fingerprint against the setup page when Android exposes it.
2. Scan the second QR code to open the full HTTPS phone URL, including its
   `#connection-token`. Use Chrome; tap **Start mic** and allow microphone access.
3. Keep the page visible. A screen wake lock is requested when supported, but
   Android may still suspend capture if you leave the page or lock the phone.
4. In the intended Windows app select **CABLE Output (VB-Audio Virtual Cable)**.
   Record a short spoken phrase to verify real speech end to end.

**Trust warning:** installing a CA is a security-sensitive action: it allows
certificates signed by that CA to be trusted by Chrome. This CA's private key
stays in `.local/ca.key` and must never be shared. Remove the CA from Android's
user credentials when finished. The CA lasts 30 days; the server certificate
lasts 14 days. Downloading the public CA uses unencrypted HTTP; the audio and
connection token use HTTPS/WSS. Use only on your trusted LAN.

Pairing persists in `.local/pairing.json` across receiver and PC restarts.
On upgrade, the receiver adopts the existing `connection.json` token so the
installed APK keeps working without rebuilding. `--reuse-session` is now an
optional compatibility flag; reuse is the default. Do not delete `.local/`.

To deliberately revoke old APKs/browser links, stop the receiver and run
`./run.sh serve WINDOWS_LAN_IP --reset-pairing`. This is the only normal startup
option that rotates the token; rebuilding/reinstalling the private APK is then
required. Corrupt saved credentials fail closed instead of silently rotating.
The receiver must still be started after a PC reboot, and the app started manually;
this change does not add autostart or reconnect.

`pairing.json`, `connection.json`, and `connect.html` contain secrets. Do not
share them or the private APK. Certificates, tokens, logs, generated setup pages,
environments and third-party binaries are all git-ignored.

## Testing

```sh
./run.sh test                     # 25 Python tests; no physical microphone needed
node --test web/capture.test.cjs  # AudioWorklet packetization tests
./run.sh list                     # Windows endpoints
./run.sh check                    # actual cable-only test
./run.sh network-test             # 8 seconds through running HTTPS receiver
```

The network test uses `.local/connection.json` and verifies TLS against the local
CA. It captures the virtual microphone in memory, not a physical microphone,
and writes no recording. Do not run it while the phone is connected.

## Wire protocol / buffering

- Secure WebSocket `/audio?token=...`; rejects invalid tokens and cross-origin
  browser requests. The page reads its token from the URL fragment.
- First text frame:
  `{"type":"start","sampleRate":48000,"channels":1,"format":"f32le"}`
- Server replies `{"type":"ready"}`. Each following binary frame contains
  exactly 960 mono little-endian float32 samples: 3,840 bytes / 20 ms.
- Server validates length and finite values, clips to [-1, 1], and duplicates
  mono to the cable's two output channels. The browser requests a 48 kHz
  AudioContext and explicitly errors if unsupported; Web Audio handles the
  physical microphone's input sample-rate conversion.
- Jitter buffer primes at 60 ms; maximum 200 ms. Old samples are discarded
  rather than allowing unbounded latency. On underflow it emits silence and
  re-primes. Browser send buffering is bounded too.
- Five seconds without audio closes the session. Disconnect clears buffered
  audio. Reconnection is explicit using Start; only one producer is accepted.
- Buffer/gap/drop/device-error counters appear on the phone once per second.
- Native Android can use the same protocol later. No custom audio driver needed.

## VB-CABLE

Third-party driver from VB-Audio, not code implemented here:
https://vb-audio.com/Cable/

Official package downloaded separately:
https://download.vb-audio.com/Download_CABLE/VBCABLE_Driver_Pack45.zip

Installer Authenticode check: Valid, signed by BUREL VINCENT Entrepreneur
individuel. Extract onto a **Windows-local disk**, run `VBCABLE_Setup_x64.exe`
as administrator, and reboot as requested. Installing directly from the WSL
share failed on this machine; the Windows-local copy succeeded.

Endpoint names are counterintuitive:
- **CABLE Input** is the playback endpoint the receiver writes into.
- **CABLE Output** is the recording endpoint Windows apps select as microphone.

VB-CABLE is donationware; keep it identifiable and respect vendor licensing.
`vendor/` is not committed or bundled as part of this project's installer.

## Environment notes / cleanup

`setup.sh` uses Windows `uv venv` / `uv pip install`. Windows uv uses `Lib` while
Python 3.10's site lookup uses `lib`; on the case-sensitive WSL share the setup
script merges generated `Lib` contents into lowercase `lib` so imports work.
Do not run setup while the receiver is running.

To clean up: stop the receiver, remove the CA from Android's user credentials,
and remove our firewall rule in elevated PowerShell:

```powershell
Remove-NetFirewallRule -Name 'PhoneMic-Dev-WINDOWS_LAN_IP'
```

Remove `.local/` only when done with its certificates. Do not delete/recreate
its CA while the phone still trusts the old one without removing that trust.
