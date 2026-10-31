# Phone mic — incremental Wi-Fi microphone spike

Project files live in `~/phone-mic`. Windows Python runs the Windows audio
component directly from the WSL share. Environment/dependencies use Windows uv.

## Slice 1: Windows virtual microphone

Acceptance: a generated 997 Hz tone sent to **CABLE Input** is captured through
**CABLE Output**, with no stream errors. No phone/network involved yet.

VB-CABLE is a third-party virtual audio driver, not code we are implementing.
Downloaded separately from the official vendor:
https://download.vb-audio.com/Download_CABLE/VBCABLE_Driver_Pack45.zip

Installer: `vendor/vb-cable/VBCABLE_Setup_x64.exe`.
Windows Authenticode validation returned Valid, signed by BUREL VINCENT
Entrepreneur individuel. Run elevated, click Install Driver, and reboot if
required. This project does not install drivers or reboot automatically.
Do not redistribute the vendor directory as part of a software installer.

From WSL:

```sh
cd ~/phone-mic
./run.sh test   # tone detector tests; no driver required
./run.sh list   # Windows audio endpoints
./run.sh check  # actual playback -> virtual mic test
```

Expected device names are counterintuitive:
- **CABLE Input** is the playback endpoint our receiver writes into.
- **CABLE Output** is the microphone apps select.

The test targets those endpoints explicitly and does not change Windows defaults
or play through the speakers. It briefly opens the cable's recording endpoint.

## Environment notes

Windows Python 3.10, `.venv-win`, dependencies pinned in `requirements.txt`.
Created using Windows `uv venv` and `uv pip install`, called through PowerShell
from WSL. Windows uv initially creates `Lib/site-packages`; Python 3.10 looks
for lowercase `lib/site-packages` on this case-sensitive WSL share. The generated
environment's `Lib` directory was renamed to `lib` so imports work.

## Next slices

1. Pass the Windows audio path test.
2. Stream the Android browser microphone over Wi-Fi into that same endpoint.
   HTTPS / trusted certificate, permission handling, buffering and bounded queues.
3. Verify speech in the actual Windows app, then test a sustained work session.
4. Replace the browser with a small native Android app for screen-off/background
   microphone operation. Preserve the working Windows receiver/protocol.

Do not broaden the implementation until the preceding slice is verified.
