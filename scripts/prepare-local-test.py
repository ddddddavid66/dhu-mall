"""只准备本机模拟容器验证输入；不会生成生产密钥或支付宝应用身份。"""
from pathlib import Path
from datetime import datetime, timedelta, timezone
import json
import secrets

root = Path(__file__).resolve().parents[1]
directory = root / '.artifacts/local-test'
directory.mkdir(parents=True, exist_ok=True)
for name in ('secrets', 'tls', 'files', 'records', 'backups', 'restore'):
    (directory / name).mkdir(exist_ok=True)
dependency = json.loads((root / '.artifacts/R-dependencies.json').read_text(encoding='utf-8-sig'))


def secret(name, value=None):
    path = directory / 'secrets' / name
    if not path.exists():
        path.write_text(value if value is not None else secrets.token_urlsafe(32), encoding='utf-8')
        path.chmod(0o600)
    return path.as_posix()


environment = dict(DEPLOY_ENV='test', APP_HOSTNAME='localhost', APP_BASE_URL='https://localhost:18443',
                   ALLOWED_ORIGINS='http://127.0.0.1:5173,https://localhost:18443', MYSQL_DATABASE='yueji_mall_test', DB_USERNAME='yueji_test',
                   BACKEND_IMAGE='yueji-local-backend:r', FRONTEND_IMAGE='yueji-local-frontend:r',
                   IMAGE_REPOSITORY='local-validation-only', PAYMENT_PROVIDER='LOCAL_MOCK',
                   ALIPAY_APP_ID='LOCAL_MOCK_NO_CHANNEL_APP', ALIPAY_SELLER_ID='LOCAL_MOCK_NO_SELLER',
                   ALIPAY_NOTIFY_URL='https://localhost:18443/unused-mock-notify', ALIPAY_RETURN_URL='https://localhost:18443/payments/result',
                   FILE_STORAGE_PATH=(directory / 'files').as_posix(), TLS_CERTIFICATE_PATH=(directory / 'tls').as_posix(),
                   DB_PASSWORD_FILE=secret('database-password'), MYSQL_ROOT_PASSWORD_FILE=secret('mysql-root-password'),
                   JWT_SIGNING_KEY_FILE=secret('jwt-signing-key', __import__('base64').b64encode(secrets.token_bytes(32)).decode()),
                   ALIPAY_APP_PRIVATE_KEY_FILE=secret('mock-unused-private', 'LOCAL_MOCK_NO_CHANNEL_KEY'),
                   ALIPAY_PUBLIC_KEY_FILE=secret('mock-unused-public', 'LOCAL_MOCK_NO_CHANNEL_KEY'),
                   REDIS_PASSWORD_FILE=secret('redis-password', dependency['redisPassword']),
                   RABBITMQ_PASSWORD_FILE=secret('rabbitmq-password', dependency['rabbitPassword']),
                   RABBITMQ_USERNAME=dependency['rabbitUsername'], RABBITMQ_VHOST='yueji_test', GRAFANA_ADMIN_USER='test_ops')
# Grafana 使用独立随机口令，主配置只从受限文件注入。
grafana = directory / 'secrets/grafana-password'
secret('grafana-password')
environment['GRAFANA_ADMIN_PASSWORD'] = grafana.read_text(encoding='utf-8')
runtime = directory / 'runtime.env'
runtime.write_text(''.join(f'{key}={value}\n' for key, value in environment.items()), encoding='utf-8')
runtime.chmod(0o600)

try:
    from cryptography import x509
    from cryptography.hazmat.primitives import hashes, serialization
    from cryptography.hazmat.primitives.asymmetric import rsa
    from cryptography.x509.oid import NameOID
    import ipaddress
    key_path = directory / 'tls/privkey.pem'
    if not key_path.exists():
        key = rsa.generate_private_key(public_exponent=65537, key_size=2048)
        subject = x509.Name([x509.NameAttribute(NameOID.COMMON_NAME, 'Yueji LOCAL TEST ONLY')])
        cert = (x509.CertificateBuilder().subject_name(subject).issuer_name(subject).public_key(key.public_key())
                .serial_number(x509.random_serial_number()).not_valid_before(datetime.now(timezone.utc) - timedelta(minutes=1))
                .not_valid_after(datetime.now(timezone.utc) + timedelta(days=7))
                .add_extension(x509.SubjectAlternativeName([x509.DNSName('localhost'), x509.IPAddress(ipaddress.ip_address('127.0.0.1'))]), critical=False)
                .sign(key, hashes.SHA256()))
        key_path.write_bytes(key.private_bytes(serialization.Encoding.PEM, serialization.PrivateFormat.PKCS8, serialization.NoEncryption()))
        key_path.chmod(0o600)
        (directory / 'tls/fullchain.pem').write_bytes(cert.public_bytes(serialization.Encoding.PEM))
except ImportError:
    raise SystemExit('需要 cryptography 生成仅本机测试证书；不自动降低TLS验证。')
print('Local-only test runtime prepared; no production or Alipay keys generated.')
