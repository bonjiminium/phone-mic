"""Generate a PRIVATE local setup page, not exposed by the HTTP server."""
from html import escape
from io import BytesIO

import qrcode
from qrcode.image.svg import SvgPathImage


def qr_svg(value):
    stream = BytesIO()
    qrcode.make(value, image_factory=SvgPathImage, box_size=6, border=4).save(stream)
    return stream.getvalue().decode().split('?>')[-1]


def write_page(path, ca_url, phone_url, fingerprint):
    path.write_text(f'''<!doctype html>
<html lang="en"><meta charset="utf-8"><title>Phone Mic setup</title>
<style>body {{font:18px system-ui;max-width:64rem;margin:2rem auto;padding:1rem}}
section {{display:inline-block;width:46%;vertical-align:top;margin:1%}} svg {{width:100%;max-width:380px;height:auto;background:white}}
code {{overflow-wrap:anywhere}} small {{display:block;overflow-wrap:anywhere}}</style>
<h1>Phone Mic — connect Android</h1>
<p>Keep this page private: the second QR code contains the connection token.</p>
<section><h2>1. Trust the local CA</h2>{qr_svg(ca_url)}
<p>Scan to download the CA certificate. In Android Settings, search for
<strong>Install a certificate</strong> → <strong>CA certificate</strong> and select the download.
Menu names vary. This is a temporary development CA; remove it when finished.</p>
<small>CA SHA-256 fingerprint: {escape(fingerprint)}</small>
<p><code>{escape(ca_url)}</code></p></section>
<section><h2>2. Open microphone page</h2>{qr_svg(phone_url)}
<p>Scan after installing the certificate. In Chrome, tap <strong>Start mic</strong>
and allow microphone access. Keep the page visible.</p>
<p>In Windows select <strong>CABLE Output</strong> as microphone.</p>
<p><code>{escape(phone_url)}</code></p></section>
</html>''', encoding='utf-8')
