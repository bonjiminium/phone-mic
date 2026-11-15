"""Local HTTPS + WebSocket phone microphone receiver. Run on Windows."""
import argparse
import asyncio
import hmac
import hashlib
import json
import logging
import ssl
import sys
import time
import webbrowser
from pathlib import Path

try:
    from aiohttp import web, WSMsgType
    import sounddevice as sd
    import numpy as np
    from audio_buffer import AudioBuffer, decode_packet, RATE
    from windows_audio import cable_device
    from connection_page import write_page
    from pairing import pairing_token, pairing_payload
except ImportError:
    sys.exit('Windows dependencies are missing. Run ./setup.sh, then retry ./run.sh serve.')
from startup import preflight, certificate_info, troubleshooting

ROOT = Path(__file__).resolve().parent
LOG = logging.getLogger('phone-mic')


class Receiver:
    def __init__(self, token):
        self.token = token
        self.buffer = AudioBuffer()
        self.active = None
        self.stream = None
        self.last_audio = 0
        self.level = 0
        self.playback_level = 0
        self.certificate_expires_at = 0

    def authorized(self, request):
        # Query tokens are used only for encrypted WS/APK downloads; never logged.
        value = request.query.get('token', '') if request.path in ('/audio', '/android.apk') else request.headers.get('Authorization', '').removeprefix('Bearer ')
        return hmac.compare_digest(value.encode('utf-8'), self.token.encode('utf-8'))

    async def status(self, request):
        if not self.authorized(request):
            raise web.HTTPUnauthorized()
        fresh = time.monotonic() - self.last_audio < 2
        return web.json_response(dict(connected=self.active is not None,
                                     receiving=self.active is not None and fresh,
                                     level=self.level if fresh else 0,
                                     playback_level=self.playback_level if fresh else 0,
                                     certificateExpiresAt=self.certificate_expires_at,
                                     **self.buffer.stats()), headers={'Cache-Control': 'no-store'})

    async def apk(self, request):
        if not self.authorized(request):
            raise web.HTTPUnauthorized()
        artifact = ROOT / '.local' / 'phone-mic-debug.apk'
        if not artifact.is_file():
            raise web.HTTPNotFound(text='Android spike has not been built yet')
        return web.FileResponse(artifact, headers={
            'Content-Type': 'application/vnd.android.package-archive',
            'Content-Disposition': 'attachment; filename="phone-mic-spike.apk"',
            'Cache-Control': 'no-store',
            'X-Content-Type-Options': 'nosniff',
        })

    async def audio(self, request):
        if not self.authorized(request):
            raise web.HTTPUnauthorized()
        # Reject cross-origin browser use. Native clients may omit Origin.
        origin = request.headers.get('Origin')
        if origin and origin != f'https://{request.host}':
            raise web.HTTPForbidden()
        if self.active is not None:
            raise web.HTTPConflict(text='Another phone is already connected')
        ws = web.WebSocketResponse(heartbeat=15, max_msg_size=8192)
        # Reserve before awaiting so simultaneous connections cannot both win.
        self.active = ws
        try:
            await ws.prepare(request)
            msg = await asyncio.wait_for(ws.receive(), timeout=5)
            if msg.type != WSMsgType.TEXT:
                raise ValueError('Send the start handshake first')
            hello = json.loads(msg.data)
            if hello != dict(type='start', sampleRate=RATE, channels=1, format='f32le'):
                raise ValueError('Expected mono 48000 Hz f32le start handshake')
            self.buffer.reset()
            LOG.info('Phone connected from %s', request.remote)
            await ws.send_json(dict(type='ready'))
            # Timeout ensures a stalled/disconnected sender cannot leave stale audio.
            while True:
                msg = await asyncio.wait_for(ws.receive(), timeout=5)
                if msg.type in (WSMsgType.CLOSE, WSMsgType.CLOSED, WSMsgType.ERROR):
                    break
                if msg.type != WSMsgType.BINARY:
                    raise ValueError('Expected binary audio packets')
                samples = decode_packet(msg.data)
                self.level = min(1.0, float(np.sqrt(np.mean(samples ** 2))) * 4)
                self.last_audio = time.monotonic()
                self.buffer.push(samples)
                # Send lightweight metrics once a second; no audio is saved.
                if self.buffer.stats()['packets'] % 50 == 0:
                    await ws.send_json(dict(type='stats', **self.buffer.stats()))
        except (ValueError, asyncio.TimeoutError) as exc:
            LOG.warning('Stream rejected/stalled: %s', str(exc) or 'audio timeout')
            if ws.prepared:
                await ws.close(code=1008, message=b'Invalid audio or stream timed out')
        finally:
            self.buffer.reset()
            self.level = self.playback_level = 0
            self.last_audio = 0
            self.active = None
            LOG.info('Phone disconnected; buffer cleared')
        return ws

    async def lifetime(self, app):
        device = cable_device('CABLE Input', 'output')
        LOG.info('Output: %s', sd.query_devices(device)['name'])

        def playback(outdata, frames, timing, status):
            mono = self.buffer.read(frames, status=bool(status))
            self.playback_level = min(1.0, float(np.sqrt(np.mean(mono ** 2))) * 4)
            outdata[:, 0] = mono
            outdata[:, 1] = mono

        with sd.OutputStream(device=device, samplerate=RATE, channels=2,
                             dtype='float32', blocksize=480, callback=playback):
            yield

    async def shutdown(self, app):
        if self.active is not None:
            await self.active.close(code=1001, message=b'Server shutting down')


@web.middleware
async def log_request(request, handler):
    # Do not log query strings: the WebSocket query contains the connection token.
    try:
        response = await handler(request)
    except web.HTTPException as exc:
        LOG.debug('%s %s from %s -> %s', request.method, request.path, request.remote, exc.status)
        raise
    LOG.debug('%s %s from %s -> %s', request.method, request.path, request.remote, response.status)
    return response


def application(receiver):
    app = web.Application(client_max_size=8192, middlewares=[log_request])

    async def page(request):
        allowed = {'/': 'index.html', '/phone.js': 'phone.js', '/capture.js': 'capture.js'}
        response = web.FileResponse(ROOT / 'web' / allowed[request.path])
        response.headers['Cache-Control'] = 'no-store'
        response.headers['Permissions-Policy'] = 'microphone=(self)'
        response.headers['X-Content-Type-Options'] = 'nosniff'
        response.headers['Content-Security-Policy'] = "default-src 'self'; script-src 'self'; connect-src 'self'; style-src 'self' 'unsafe-inline'; object-src 'none'; frame-ancestors 'none'"
        return response

    for path in ('/', '/phone.js', '/capture.js'):
        app.router.add_get(path, page)
    app.router.add_get('/audio', receiver.audio)
    app.router.add_get('/status', receiver.status)
    app.router.add_get('/android.apk', receiver.apk)
    app.cleanup_ctx.append(receiver.lifetime)
    app.on_shutdown.append(receiver.shutdown)
    return app


def dashboard_application(receiver):
    # Separate loopback-only dashboard: no CORS, no pairing endpoint, bearer auth.
    app = web.Application(middlewares=[log_request])

    async def page(request):
        name = 'dashboard.html' if request.path == '/' else 'dashboard.js'
        return web.FileResponse(ROOT / 'web' / name, headers={
            'Cache-Control': 'no-store', 'Referrer-Policy': 'no-referrer',
            'Content-Security-Policy': "default-src 'self'; script-src 'self'; style-src 'self' 'unsafe-inline'; connect-src 'self'; object-src 'none'",
        })
    app.router.add_get('/', page)
    app.router.add_get('/dashboard.js', page)
    app.router.add_get('/status', receiver.status)
    return app


def loop_error(loop, context):
    # Windows Python 3.10 Proactor may report this when a browser closes a socket.
    # It is a normal peer disconnect, not a receiver failure. Preserve other errors.
    error = context.get('exception')
    if isinstance(error, ConnectionResetError) and getattr(error, 'winerror', None) == 10054:
        LOG.debug('Peer closed its connection')
        return
    loop.default_exception_handler(context)


async def serve(args):
    asyncio.get_running_loop().set_exception_handler(loop_error)
    expiry = preflight(ROOT, args.host, args.port, cable_device)
    token = pairing_token(ROOT / '.local', reset=args.reset_pairing)
    tls = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
    tls.minimum_version = ssl.TLSVersion.TLSv1_2
    tls.load_cert_chain(ROOT / '.local' / 'server.crt', ROOT / '.local' / 'server.key')
    receiver = Receiver(token)
    receiver.certificate_expires_at = expiry
    runner = web.AppRunner(application(receiver), access_log=None)
    await runner.setup()
    try:
        await web.TCPSite(runner, args.host, args.port, ssl_context=tls).start()
        # Separate HTTP listener only serves the public CA, never audio/private keys.
        ca_app = web.Application(middlewares=[log_request])

        async def ca(request):
            return web.FileResponse(ROOT / '.local' / 'phone-mic-ca.crt',
                                    headers={'Content-Type': 'application/x-x509-ca-cert'})

        ca_app.router.add_get('/phone-mic-ca.crt', ca)
        ca_runner = web.AppRunner(ca_app, access_log=None)
        await ca_runner.setup()
        try:
            await web.TCPSite(ca_runner, args.host, args.port - 1).start()
            url = f'https://{args.host}:{args.port}/#{token}'
            (ROOT / '.local' / 'connection.json').write_text(json.dumps(dict(url=url, token=token, host=args.host, port=args.port)))
            ca_url = f'http://{args.host}:{args.port - 1}/phone-mic-ca.crt'
            ca_der = ssl.PEM_cert_to_DER_cert((ROOT / '.local' / 'phone-mic-ca.crt').read_text())
            fingerprint = ':'.join(f'{byte:02X}' for byte in hashlib.sha256(ca_der).digest())
            pairing = pairing_payload(args.host, args.port, token, (ROOT / '.local' / 'server.crt').read_text(), expiry)
            dashboard = web.AppRunner(dashboard_application(receiver), access_log=None)
            await dashboard.setup()
            try:
                await web.TCPSite(dashboard, '127.0.0.1', args.port + 1).start()
                target = ROOT / '.local' / 'connect.html'
                apk_available = (ROOT / '.local' / 'phone-mic-debug.apk').is_file()
                write_page(target, ca_url, url, fingerprint, pairing,
                           apk_available=apk_available, dashboard_port=args.port + 1)
                print('\nReady - waiting for your phone.', flush=True)
                print(f'Open {target} on this PC for installation, pairing QR and live status.', flush=True)
                if not apk_available:
                    print('App installer unavailable: build it with ./android/build.sh. Already-installed phones can connect.', flush=True)
                print('On phone: Pair & start microphone. In Windows: select CABLE Output (VB-Audio Virtual Cable).', flush=True)
                print('Leave this terminal open. Press Ctrl+C to stop. For connection help: ./run.sh doctor\n', flush=True)
                if args.open_page:
                    try:
                        if not webbrowser.open(target.as_uri()):
                            print('Could not open a browser automatically; open the setup file above manually.', flush=True)
                    except OSError:
                        print('Open the setup file above manually in your Windows browser.', flush=True)
                await asyncio.Event().wait()
            finally:
                await dashboard.cleanup()
        finally:
            await ca_runner.cleanup()
    finally:
        await runner.cleanup()


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--host', help='Windows LAN IPv4; binds only this interface')
    parser.add_argument('--open-page', action='store_true', help='Open the private setup page when ready')
    parser.add_argument('--doctor', action='store_true', help='Show setup and connection checks')
    parser.add_argument('--port', type=int, default=8765)
    pairing_options = parser.add_mutually_exclusive_group()
    pairing_options.add_argument('--reset-pairing', action='store_true', help='Generate a new secret, invalidating installed APKs and old browser links')
    pairing_options.add_argument('--reuse-session', action='store_true', help='Compatibility flag: pairing is now persistent by default')
    args = parser.parse_args()
    logging.basicConfig(level=logging.INFO, format='%(asctime)s %(message)s')
    try:
        if args.doctor:
            print(troubleshooting())
            for name, direction in (('CABLE Input', 'output'), ('CABLE Output', 'input')):
                cable_device(name, direction)
                print(f'OK: {name}')
            try:
                _, expiry = certificate_info(ROOT / '.local' / 'server.crt')
                print('Certificate: ' + ('expired — run ./run.sh serve to renew and re-pair' if expiry <= time.time() * 1000
                                        else f'valid until {time.strftime("%Y-%m-%d", time.localtime(expiry / 1000))}'))
            except (OSError, ssl.SSLError):
                print('Certificate missing or unreadable — run ./run.sh serve.')
        else:
            if not args.host:
                parser.error('Use ./run.sh serve to detect your Windows LAN address.')
            asyncio.run(serve(args))
    except KeyboardInterrupt:
        print('\nReceiver stopped. Phone audio is no longer being forwarded.')
    except (OSError, ValueError, RuntimeError, sd.PortAudioError) as error:
        print(f'Could not start receiver: {error}\nFor guided checks, run ./run.sh doctor.', file=sys.stderr)
        sys.exit(1)
