"""从干净Git提交和明确允许的产物打包；不递归打包整个工作目录。"""
from pathlib import Path
from datetime import datetime, timezone
import hashlib
import json
import subprocess
import zipfile

ROOT = Path(__file__).resolve().parents[1]
OUT = ROOT / 'deliverables'
NAMES = ['01-阅集商城需求文档.docx', '02-阅集商城设计文档.docx', '03-阅集商城测试报告.docx',
         '04-阅集商城项目汇报.pptx', '05-阅集商城本地联调演示.mp4', '06-源码与设计.zip']


def git(*args):
    return subprocess.run(['git', *args], cwd=ROOT, check=True, capture_output=True).stdout.decode('utf-8').strip()


def digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def main():
    assert not git('status', '--porcelain'), 'Commit reviewed source and evidence before packaging'
    head = git('rev-parse', 'HEAD')
    source = OUT / NAMES[-1]
    git('archive', '--format=zip', '--prefix=yueji-mall/', '--output=' + str(source), head)
    with zipfile.ZipFile(source) as archive:
        assert archive.testzip() is None
        files = archive.namelist()
        assert 'yueji-mall/backend/pom.xml' in files and 'yueji-mall/frontend/src/main.ts' in files
        assert not any('/.artifacts/' in f or '/node_modules/' in f or '/.tools/' in f or '/target/' in f for f in files)
        assert not any(f.endswith(('.pem', '.key', '.age', '.sql.gz')) for f in files)
    for name in NAMES: assert (OUT / name).is_file(), name
    manifest = dict(createdAt=datetime.now(timezone.utc).isoformat(), sourceCommit=head,
                    productionDeployment=False, submissionStatus='LOCAL_ACCEPTANCE_PREVIEW',
                    files=[dict(name=n, bytes=(OUT / n).stat().st_size, sha256=digest(OUT / n)) for n in NAMES],
                    limitations=['Alipay buyer/payment/callback/refund not accepted', 'Independent backup and fixed-profile load test absent',
                                 '24-hour probe still running; maintenance failure retained', 'Teacher template/member signatures/original AI export missing'])
    (OUT / 'manifest.json').write_text(json.dumps(manifest, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
    (OUT / 'SHA256SUMS.txt').write_text(''.join(f"{row['sha256']}  {row['name']}\n" for row in manifest['files']), encoding='utf-8')
    (OUT / '交付说明.txt').write_text('阅集商城课程提交预览\n源提交：' + head + '\n先解压06源码包，阅读README与docs/17。账号与密钥不在包中。\n视频为123秒LOCAL_MOCK真实自动化录像摘录，无真人讲解，不代替沙箱验收。\n完整课程仍缺沙箱买家、独立备份、标准性能、完整24小时、教师模板及本人签名。\n', encoding='utf-8')
    package = OUT / '阅集商城课程提交预览.zip'
    with zipfile.ZipFile(package, 'w', compression=zipfile.ZIP_DEFLATED) as archive:
        for name in [*NAMES, 'manifest.json', 'SHA256SUMS.txt', '交付说明.txt']:
            archive.write(OUT / name, name)
        for name in ['R-desktop-refund.png', 'R-mobile-refund.png', 'R-settlement-mock-paid.png']:
            archive.write(ROOT / '.artifacts' / name, 'evidence/' + name)
    with zipfile.ZipFile(package) as archive: assert archive.testzip() is None
    receipt = dict(package=package.name, bytes=package.stat().st_size, sha256=digest(package), sourceCommit=head,
                   sourceEntries=len(files), zipCrcPassed=True)
    (OUT / 'package-receipt.json').write_text(json.dumps(receipt, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
    print(json.dumps(receipt, ensure_ascii=False))


if __name__ == '__main__': main()
