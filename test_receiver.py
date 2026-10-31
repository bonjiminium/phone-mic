import asyncio
import unittest
import tempfile
from pathlib import Path
from unittest.mock import patch

from aiohttp import WSServerHandshakeError, WSMsgType
from aiohttp.test_utils import TestClient, TestServer

from receiver import Receiver, application

HELLO = dict(type='start', sampleRate=48000, channels=1, format='f32le')


class ReceiverTest(unittest.IsolatedAsyncioTestCase):
    async def asyncSetUp(self):
        self.receiver = Receiver('test-secret')
        app = application(self.receiver)
        app.cleanup_ctx.clear()  # Protocol tests must not open real audio devices.
        self.client = TestClient(TestServer(app))
        await self.client.start_server()

    async def asyncTearDown(self):
        await self.client.close()

    async def test_status_requires_token(self):
        response = await self.client.get('/status')
        self.assertEqual(response.status, 401)
        response = await self.client.get('/status', headers={'Authorization': 'Bearer test-secret'})
        self.assertEqual(response.status, 200)

    async def test_apk_download_requires_token_and_serves_only_private_artifact(self):
        response = await self.client.get('/android.apk')
        self.assertEqual(response.status, 401)
        with tempfile.TemporaryDirectory() as folder:
            root = Path(folder)
            with patch('receiver.ROOT', root):
                response = await self.client.get('/android.apk?token=test-secret')
                self.assertEqual(response.status, 404)
                (root / '.local').mkdir()
                (root / '.local' / 'phone-mic-debug.apk').write_bytes(b'test-apk')
                response = await self.client.get('/android.apk?token=test-secret')
                self.assertEqual(response.status, 200)
                self.assertEqual(await response.read(), b'test-apk')
                self.assertEqual(response.headers['Cache-Control'], 'no-store')
                self.assertEqual(response.headers['Content-Type'], 'application/vnd.android.package-archive')

    async def test_websocket_requires_token(self):
        with self.assertRaises(WSServerHandshakeError) as error:
            await self.client.ws_connect('/audio?token=wrong')
        self.assertEqual(error.exception.status, 401)

    async def test_cross_origin_rejected(self):
        with self.assertRaises(WSServerHandshakeError) as error:
            await self.client.ws_connect('/audio?token=test-secret', origin='https://evil.invalid')
        self.assertEqual(error.exception.status, 403)

    async def test_single_sender_and_audio_cleanup(self):
        ws = await self.client.ws_connect('/audio?token=test-secret')
        await ws.send_json(HELLO)
        self.assertEqual((await ws.receive_json())['type'], 'ready')
        with self.assertRaises(WSServerHandshakeError) as error:
            await self.client.ws_connect('/audio?token=test-secret')
        self.assertEqual(error.exception.status, 409)
        await ws.send_bytes(bytes(3840))
        for _ in range(50):
            if self.receiver.buffer.stats()['packets']:
                break
            await asyncio.sleep(.01)
        self.assertEqual(self.receiver.buffer.stats()['packets'], 1)
        await ws.close()
        for _ in range(50):
            if self.receiver.active is None:
                break
            await asyncio.sleep(.01)
        self.assertIsNone(self.receiver.active)
        self.assertEqual(self.receiver.buffer.size, 0)
        # A second session can connect after the first leaves.
        ws2 = await self.client.ws_connect('/audio?token=test-secret')
        await ws2.send_json(HELLO)
        self.assertEqual((await ws2.receive_json())['type'], 'ready')
        await ws2.close()

    async def test_invalid_handshake_and_packet_close_session(self):
        for hello, packet in ((dict(HELLO, sampleRate=44100), None), (HELLO, b'bad')):
            ws = await self.client.ws_connect('/audio?token=test-secret')
            await ws.send_json(hello)
            if packet is not None:
                await ws.receive_json()
                await ws.send_bytes(packet)
            msg = await ws.receive()
            self.assertEqual(msg.type, WSMsgType.CLOSE)
            self.assertEqual(msg.data, 1008)
            await ws.close()

    async def test_only_explicit_public_files(self):
        for path in ('/', '/phone.js', '/capture.js'):
            response = await self.client.get(path)
            self.assertEqual(response.status, 200)
        for path in ('/.local/ca.key', '/.local/connection.json', '/receiver.py'):
            response = await self.client.get(path)
            self.assertEqual(response.status, 404)


if __name__ == '__main__':
    unittest.main()
