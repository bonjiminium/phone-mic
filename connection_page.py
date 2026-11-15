"""Generate a PRIVATE local setup page, never exposed by the HTTP server."""
from datetime import datetime, timezone
from html import escape
from io import BytesIO
import json
from urllib.parse import urlencode, urlsplit

import qrcode
from qrcode.image.svg import SvgPathImage


def qr_svg(value):
    stream = BytesIO()
    qrcode.make(value, image_factory=SvgPathImage, box_size=6, border=4).save(stream)
    return stream.getvalue().decode().split('?>')[-1]


def write_page(path, ca_url, phone_url, fingerprint, pairing, *, apk_available=True, dashboard_port=8766):
    origin = urlsplit(phone_url)
    download_url = f'{origin.scheme}://{origin.netloc}/android.apk?' + urlencode({'token': origin.fragment})
    pairing_json = json.dumps(pairing, separators=(',', ':'))
    install = (f'''{qr_svg(download_url)}
<p>Use your phone’s <strong>normal camera</strong> to scan this download QR.
Download the APK, then <strong>open the download and tap Install</strong>.</p>
<p>If Android asks, allow your browser to install this app. You can revoke that permission afterward.
A development-certificate warning may appear: check the address matches your PC.
Alternatively, copy <code>.local/phone-mic-debug.apk</code> to your phone over USB.</p>
<p><a href="{escape(download_url, quote=True)}">Download Android APK</a> (open on the phone)</p>'''
               if apk_available else '''<p role="alert"><strong>The app installer hasn’t been built yet.</strong>
On the PC, run <code>./android/build.sh</code>, then restart <code>./run.sh serve</code>.
Already have Phone Mic installed? Continue to step 2.</p>''')
    expiry = pairing.get('certificateExpiresAt')
    expires = datetime.fromtimestamp(expiry / 1000, timezone.utc).strftime('%Y-%m-%d UTC') if expiry else 'not included by this receiver'
    generated = datetime.now(timezone.utc).strftime('%Y-%m-%d %H:%M UTC')
    path.write_text(f'''<!doctype html>
<html lang="en"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<meta name="referrer" content="no-referrer"><title>Phone Mic — connect your phone</title>
<style>
body {{font:18px system-ui;max-width:54rem;margin:2rem auto;padding:1rem;background:#f7f8fa;color:#182330}}
section,details {{background:white;border:1px solid #ccd4dd;border-radius:12px;padding:1.2rem;margin:1rem 0}}
h1 {{margin-bottom:.4rem}} h2 {{font-size:1.3rem}} svg {{display:block;width:100%;max-width:340px;height:auto;background:white}}
code,small {{overflow-wrap:anywhere}} small {{display:block}} a {{color:#174b9b}}
iframe {{width:100%;height:340px;border:0}} summary {{cursor:pointer}} .hint {{color:#475769}}
</style></head><body>
<h1>Use your phone as a Windows microphone</h1>
<p>Keep your phone and PC on the same Wi-Fi/network. Leave the receiver terminal running.</p>
<p class="hint">Keep this page private: the QR codes grant access to your receiver. No CA installation is needed.</p>
<section><h2>1. Install Phone Mic — once</h2>
<p><strong>Already installed? Skip to step 2.</strong></p>
<details><summary>Show app download and installation</summary>{install}</details></section>
<section><h2>2. Pair &amp; start your microphone</h2>
<p>On your phone, open <strong>Phone Mic → Scan pairing QR</strong>.
Use the scanner <strong>inside Phone Mic</strong> for this QR — not your normal camera.</p>
{qr_svg(pairing_json)}
<p>Confirm this is your PC, then tap <strong>Pair &amp; start microphone</strong>.
Allow microphone access when asked. Your phone will connect and begin sending audio to this PC.</p>
<p>Receiver: <code>{escape(pairing['serverUrl'])}</code></p>
<p>Already paired? Just tap <strong>Start microphone</strong> on your phone.
If the PC address or certificate changed, scan this updated QR.</p></section>
<section><h2>3. Speak to test</h2>
<p>The phone’s microphone bar should move when you speak. The live PC panel below shows whether audio arrives and is sent to the virtual cable.</p>
<iframe title="Live receiver status" src="http://127.0.0.1:{dashboard_port}/#{escape(origin.fragment, quote=True)}"></iframe>
<p class="hint">Panel unavailable? Start <code>./run.sh serve</code> on this PC and reopen this file.
This panel only works on the receiver PC, not on your phone.</p></section>
<section><h2>4. Select the microphone in your Windows app</h2>
<p>In your calling, recording or dictation app, choose:</p>
<p><strong>CABLE Output (VB-Audio Virtual Cable)</strong></p>
<p>Speak and check <strong>that app’s microphone meter</strong>. Receiving audio here does not prove the app selected the right microphone.</p>
<p>Despite its name, CABLE Output is the microphone. CABLE Input is used internally by the receiver.</p>
<p>Once streaming, you can lock your phone. To finish, tap <strong>Stop microphone</strong> on the phone or its notification. Stop the PC receiver with <strong>Ctrl+C</strong>.</p></section>
<details><summary>Something isn’t working?</summary>
<ol><li>Is <code>./run.sh serve</code> still running on the PC?</li>
<li>Are both devices on the same network? Guest Wi-Fi or a VPN may block local connections.</li>
<li>Run <code>./run.sh doctor</code> for cable, certificate and firewall guidance.</li>
<li>“Receiver busy”? Stop capture on another phone or browser.</li>
<li>“Certificate changed/expired” or “Pairing no longer valid”? Restart the receiver, reopen this file, and scan its updated QR.</li>
<li>PC receives audio, but your app does not? Select CABLE Output and check the app’s mute/permission controls.</li></ol>
<p>Don’t disable your firewall or certificate checks. No app reinstall is needed to re-pair.</p></details>
<details><summary>Security and certificate details</summary>
<p>Scan only the QR on your own PC. The phone sends audio to the receiver you confirm; scanning alone never starts recording.</p>
<small>Server certificate SHA-256: {escape(pairing['certificateSha256'])}</small>
<p>Certificate expires: {escape(expires)}. The receiver renews an expired certificate at startup; scan the new QR afterward.</p>
<p>Pairing is saved privately on the phone. No phone-wide CA installation, disk audio recordings, or internet audio uploads.</p>
<p>This page was generated {generated}. Reopen the current file after restarting the receiver; an old open tab can contain an outdated QR.</p></details>
<details><summary>Alternative: browser microphone (limited)</summary>
<p><a href="{escape(phone_url, quote=True)}">Open browser microphone</a> on your phone.
Keep its screen on and page visible. Stop browser capture before using the native app.</p>
<p>The Android app is recommended and needs no CA installation. Do not install a development CA by default:
it broadens phone trust. Browser TLS behavior varies.</p>
<small>Legacy public CA download: {escape(ca_url)}<br>CA SHA-256 (not the server pin): {escape(fingerprint)}</small></details>
</body></html>''', encoding='utf-8')
