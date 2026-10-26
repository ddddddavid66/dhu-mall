"""内网测试通知接收器：只保存白名单告警字段，不发送邮件/聊天消息。"""
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from datetime import datetime, timezone
import json
import os

destination = Path(os.environ.get('ALERT_EVIDENCE_FILE', '/evidence/alerts.jsonl'))
destination.parent.mkdir(parents=True, exist_ok=True)


class Receiver(BaseHTTPRequestHandler):
    def do_GET(self):
        self.send_response(200 if self.path == '/healthz' else 404)
        self.end_headers()

    def do_POST(self):
        size = int(self.headers.get('Content-Length', '0'))
        if self.path != '/alerts' or size < 1 or size > 262144:
            self.send_error(400)
            return
        try:
            body = json.loads(self.rfile.read(size))
            allowed = {'alertname', 'severity', 'service', 'environment', 'job'}
            record = {'receivedAt': datetime.now(timezone.utc).isoformat(), 'status': body.get('status'),
                      'alerts': [{'status': item.get('status'),
                                  'labels': {k: v for k, v in item.get('labels', {}).items() if k in allowed},
                                  'startsAt': item.get('startsAt'), 'endsAt': item.get('endsAt')}
                                 for item in body.get('alerts', [])]}
            with destination.open('a', encoding='utf-8') as output:
                output.write(json.dumps(record, ensure_ascii=False) + '\n')
            self.send_response(200)
            self.end_headers()
        except (ValueError, TypeError, OSError):
            self.send_error(400)

    def log_message(self, *_):
        pass


ThreadingHTTPServer(('0.0.0.0', 9099), Receiver).serve_forever()
