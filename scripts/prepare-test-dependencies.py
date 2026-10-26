"""CI本机隔离依赖凭据；不生成生产账号，不输出秘密。"""
from pathlib import Path
import json
import os
import secrets

root = Path(__file__).resolve().parents[1]
directory = root / '.artifacts'
directory.mkdir(exist_ok=True)
path = directory / 'R-dependencies.json'
if not path.exists():
    path.write_text(json.dumps(dict(redisPassword=secrets.token_urlsafe(32), rabbitUsername='yueji_test',
                                    rabbitPassword=secrets.token_urlsafe(32))), encoding='utf-8')
config = json.loads(path.read_text(encoding='utf-8-sig'))
password_file = directory / 'R-redis-password'
password_file.write_text(config['redisPassword'], encoding='utf-8')
env = dict(RABBITMQ_USERNAME=config['rabbitUsername'], RABBITMQ_PASSWORD=config['rabbitPassword'],
           REDIS_PASSWORD_FILE=password_file.as_posix())
(directory / 'R-dependencies.env').write_text(''.join(f'{k}={v}\n' for k, v in env.items()), encoding='utf-8')
for item in (path, password_file, directory / 'R-dependencies.env'):
    item.chmod(0o600)
if os.environ.get('GITHUB_ENV'):
    values = dict(REDIS_HOST='127.0.0.1', REDIS_PORT='16379', REDIS_PASSWORD=config['redisPassword'],
                  RABBITMQ_HOST='127.0.0.1', RABBITMQ_PORT='15673', RABBITMQ_USERNAME=config['rabbitUsername'],
                  RABBITMQ_PASSWORD=config['rabbitPassword'], RABBITMQ_VHOST='yueji_test')
    with open(os.environ['GITHUB_ENV'], 'a', encoding='utf-8') as output:
        for k, v in values.items():
            if k in {'REDIS_PASSWORD', 'RABBITMQ_PASSWORD'}:
                print(f'::add-mask::{v}')
            output.write(f'{k}={v}\n')
print('Isolated dependency files prepared; production credentials are not created.')
