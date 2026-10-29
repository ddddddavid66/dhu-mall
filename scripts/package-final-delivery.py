"""从干净提交导出最终四项交付；脚本审阅副本与源码保持逐字节一致。"""
from pathlib import Path
from datetime import datetime, timezone
import hashlib
import io
import json
import re
import subprocess
import zipfile

ROOT = Path(__file__).resolve().parents[1]
OUT = ROOT / 'deliverables/最终提交'
DOCS = ['企业级需求文档.docx', '设计文档.docx', '测试报告.docx']


def git(*args):
    return subprocess.run(['git', *args], cwd=ROOT, check=True, capture_output=True).stdout


def sha(data): return hashlib.sha256(data).hexdigest()


def main():
    assert not git('status', '--porcelain').strip(), 'Commit reviewed changes first'
    commit = git('rev-parse', 'HEAD').decode().strip()
    for name in DOCS: assert (OUT / name).is_file(), name
    source = zipfile.ZipFile(io.BytesIO(git('archive', '--format=zip', commit)))
    migrations = [n for n in source.namelist() if re.fullmatch(r'backend/src/main/resources/db/migration/V\d+__[^/]+\.sql', n)]
    assert {int(re.search(r'/V(\d+)__', n)[1]) for n in migrations} == set(range(1, 12))
    required = ['backend/pom.xml', 'frontend/src/main.ts', 'contracts/openapi.json', 'infra/compose.yml', 'scripts/deploy-yueji-test-linux.sh']
    assert all(name in source.namelist() for name in required)
    manifest = dict(createdAt=datetime.now(timezone.utc).isoformat(), sourceCommit=commit, productionDeployment=False,
                    documents=[dict(name=n, sha256=sha((OUT/n).read_bytes())) for n in DOCS],
                    limitations=['Unpaid sandbox close not confirmed', '17 unresolved dependency findings accepted for local use',
                                 'Additional drills omitted by user; 24h probe cancelled', 'Teacher/member/signature details pending'])
    checks = []
    package = OUT / '源码数据库部署脚本.zip'
    with zipfile.ZipFile(package, 'w', compression=zipfile.ZIP_DEFLATED) as archive:
        for member in source.infolist():
            if member.is_dir(): continue
            name = member.filename
            assert not any(part in name.split('/') for part in ['.artifacts', '.tools', 'node_modules', 'target', 'deliverables', '.git'])
            assert not name.endswith(('.pem', '.key', '.age', '.sql.gz'))
            data = source.read(name)
            destination = '源码/yueji-mall/' + name
            info = zipfile.ZipInfo(destination, member.date_time)
            info.external_attr = member.external_attr; info.compress_type = zipfile.ZIP_DEFLATED
            archive.writestr(info, data); checks.append((destination, sha(data)))
            if name in migrations:
                destination = '数据库脚本/' + Path(name).name
                archive.writestr(destination, data); checks.append((destination, sha(data)))
            if name.startswith('infra/') or name in ['backend/Dockerfile', 'backend/docker-entrypoint.sh', 'frontend/Dockerfile', 'frontend/nginx.conf.template'] or name.startswith('scripts/') and (name.endswith('.sh') or re.match(r'scripts/(verify|bootstrap|use-)', name)):
                destination = '部署脚本/' + name
                archive.writestr(destination, data); checks.append((destination, sha(data)))
        readme = ('阅集商城工程交付\n源提交：'+commit+'\n\n'
                  '实际构建及运行目录：源码/yueji-mall。先读该目录README与docs/18。\n'
                  '数据库脚本、部署脚本是同一提交的审阅副本；不要在Flyway运行后重复手动导入SQL。\n'
                  '部署审阅副本不单独作为工作目录；在完整源码工程根目录执行scripts入口，保留相对路径。\n'
                  '无默认生产口令、密钥或本机账号；运行凭据须使用受限文件或secrets。\n'
                  '未发布生产；17项依赖发现未修复；沙箱付款、通知、退款已核实，未付款关单未确认。\n'
                  '其他演练不再追加，24小时探测已取消，教师资料及签名待补。\n')
        archive.writestr('交付说明.txt', readme)
        archive.writestr('manifest.json', json.dumps(manifest, ensure_ascii=False, indent=2))
        archive.writestr('SHA256SUMS.txt', ''.join(f'{value}  {name}\n' for name,value in checks))
    with zipfile.ZipFile(package) as archive:
        assert archive.testzip() is None
        for path, digest in checks: assert sha(archive.read(path)) == digest
    receipt = dict(sourceCommit=commit, sourceFiles=len([n for n in source.namelist() if not n.endswith('/')]), migrations=len(migrations),
                   zipCrcPassed=True, allEntryHashesPassed=True,
                   files=[dict(name=n, bytes=(OUT/n).stat().st_size, sha256=sha((OUT/n).read_bytes())) for n in [package.name,*DOCS]])
    (ROOT / '.artifacts/final-delivery-receipt.json').write_text(json.dumps(receipt,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
    print(json.dumps(receipt,ensure_ascii=False))


if __name__ == '__main__': main()
