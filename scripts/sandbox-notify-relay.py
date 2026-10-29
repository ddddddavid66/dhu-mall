"""仅转发固定支付宝通知路径；不公开商城、管理端或文件，不记录通知正文。"""
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.request import Request, urlopen
from urllib.error import HTTPError, URLError
from pathlib import Path
from datetime import datetime, timezone
import json

PATH = '/api/v1/payments/alipay/notify'
LOG = Path(__file__).resolve().parents[1] / '.artifacts/alipay-sandbox/notifications.jsonl'


class Relay(BaseHTTPRequestHandler):
    def log_message(self, *args): pass

    def do_GET(self):
        self.send_error(404)

    def do_POST(self):
        if self.path != PATH:
            self.send_error(404); return
        try: size = int(self.headers.get('Content-Length', '0'))
        except ValueError: self.send_error(400); return
        if size < 1 or size > 65536 or self.headers.get('Transfer-Encoding'):
            self.send_error(413); return
        if self.headers.get_content_type() != 'application/x-www-form-urlencoded':
            self.send_error(415); return
        request = Request('http://127.0.0.1:28080' + PATH, self.rfile.read(size),
                          {'Content-Type': 'application/x-www-form-urlencoded'}, method='POST')
        try:
            with urlopen(request, timeout=15) as response: status, body = response.status, response.read(1024)
        except HTTPError as error: status, body = error.code, error.read(1024)
        except (URLError, OSError): status, body = 503, b'failure'
        LOG.parent.mkdir(parents=True, exist_ok=True)
        with LOG.open('a', encoding='utf-8') as stream:
            stream.write(json.dumps(dict(at=datetime.now(timezone.utc).isoformat(), status=status,
                                         acknowledged=body.strip() == b'success')) + '\n')
        self.send_response(status); self.send_header('Content-Type', 'text/plain; charset=utf-8')
        self.send_header('Content-Length', str(len(body))); self.end_headers(); self.wfile.write(body)


if __name__ == '__main__':
    ThreadingHTTPServer(('127.0.0.1', 28181), Relay).serve_forever()
