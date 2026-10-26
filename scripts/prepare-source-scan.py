"""复制Git允许提交的源码到新的只读扫描快照；使用NUL分隔保留中文路径。"""
from pathlib import Path
import argparse
import json
import shutil
import subprocess

root = Path(__file__).resolve().parents[1]
parser = argparse.ArgumentParser()
parser.add_argument('--name', required=True)
args = parser.parse_args()
if not args.name.replace('-', '').replace('_', '').isalnum(): parser.error('invalid snapshot name')
destination = root / '.artifacts' / args.name
destination.mkdir(exist_ok=False)
listing = subprocess.run(['git', 'ls-files', '-z', '--cached', '--others', '--exclude-standard'],
                         cwd=root, check=True, capture_output=True).stdout.decode('utf-8').split('\0')
files = []
for name in sorted(set(listing) - {''}):
    source = root / name
    if not source.is_file(): continue
    assert not name.startswith(('.artifacts/', '.tools/', 'deliverables/', '.git/'))
    target = destination / name
    target.parent.mkdir(parents=True, exist_ok=True)
    shutil.copyfile(source, target)
    files.append(name)
(destination.parent / (args.name + '-manifest.json')).write_text(json.dumps(files, ensure_ascii=False, indent=2), encoding='utf-8')
print(json.dumps(dict(files=len(files), snapshot=str(destination)), ensure_ascii=False))
