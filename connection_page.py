"""Generate a PRIVATE local setup page, never exposed by the HTTP server."""
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



def write_page(path, ca_url, phone_url, fingerprint, pairing):
    origin = urlsplit(phone_url)
    download_url = f'{origin.scheme}://{origin.netloc}/android.apk?' + urlencode({'token': origin.fragment})
    pairing_json = json.dumps(pairing, separators=(',', ':'))
    path.write_text(f'''<!doctype html>
<html lang="en"><meta charset="utf-8"><title>Phone Mic setup</title>
<style>body {{font:18px system-ui;max-width:64rem;margin:2rem auto;padding:1rem}}
section {{display:inline-block;width:46%;vertical-align:top;margin:1%}} svg {{width:100%;max-width:420px;height:auto;background:white}}
code {{overflow-wrap:anywhere}} small {{display:block;overflow-wrap:anywhere}}</style>
<h1>Phone Mic — install once, scan to pair</h1>
<p><strong>Keep this page private.</strong> Its QR codes contain your receiver credential.
No CA installation is needed for the native app.</p>
<section><h2>1. Install the Android app</h2>{qr_svg(download_url)}
<p>Scan with the phone's normal camera/browser to download the reusable APK.
If Chrome warns about this development HTTPS certificate, verify it is your PC.
Alternatively transfer <code>.local/phone-mic-debug.apk</code> over USB.</p>
<p>You may need to allow Chrome to install this app; revoke that installer permission afterward.
The APK contains no PC address, pairing token or certificate pin.</p>
<p><a href="{escape(download_url, quote=True)}">Download Android APK</a></p></section>
<section><h2>2. Pair inside Phone Mic</h2>{qr_svg(pairing_json)}
<p>Open <strong>Phone Mic</strong> → <strong>Scan pairing QR</strong> and scan this code.
Compare the address/fingerprint below, then confirm <strong>Pair</strong>.
Pairing does not start the microphone; tap <strong>Start</strong> when ready.</p>
<p>Receiver: <code>{escape(pairing['serverUrl'])}</code></p>
<small>Server certificate SHA-256: {escape(pairing['certificateSha256'])}</small>
<p>Select <strong>CABLE Output</strong> as the microphone in Windows.</p>
<p>Pairing survives restarts. If the address or certificate changes, scan the new code;
no APK rebuild or phone-wide CA trust is required.</p></section>
<details><summary>Legacy browser prototype / optional CA information</summary>
<p>The browser prototype still works: <a href="{escape(phone_url, quote=True)}">open microphone page</a>.
Keep that page visible while capturing. Stop it before starting the native app.</p>
<p>Installing a development CA broadens phone trust and is <strong>not required or recommended
for the native app</strong>. Existing browser-only certificate download:
<code>{escape(ca_url)}</code>.</p>
<small>CA SHA-256 (not the server pin): {escape(fingerprint)}</small></details>
</html>''', encoding='utf-8')
