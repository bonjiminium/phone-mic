"""Independent reachability test: plain HTTP, no files, TLS or audio dependencies."""
import argparse
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer


class Hello(BaseHTTPRequestHandler):
    def do_GET(self):
        if self.path == '/':
            body = b'HELLO FROM YOUR WINDOWS PC\n\nPhone-to-PC HTTP works.\n'
            code = 200
        else:
            body = b'Not found\n'
            code = 404
        self.send_response(code)
        self.send_header('Content-Type', 'text/plain; charset=utf-8')
        self.send_header('Cache-Control', 'no-store')
        self.send_header('Content-Length', str(len(body)))
        self.end_headers()
        self.wfile.write(body)


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--host', required=True)
    args = parser.parse_args()
    server = ThreadingHTTPServer((args.host, 80), Hello)
    print(f'Hello server: http://{args.host}/', flush=True)
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        pass
    finally:
        server.server_close()
