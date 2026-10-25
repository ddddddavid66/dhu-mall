"""将已完成的本地验证整理为可交接的脱敏证据，不接触任何凭据文件。"""
from pathlib import Path
from datetime import datetime, timezone
import hashlib
import json
import re
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]
DEST = ROOT / 'docs/stages/evidence'


def main():
    results = json.loads((ROOT / '.artifacts/verification-results.json').read_text(encoding='utf-8-sig'))
    assert len(results) == 7 and all(r['exitCode'] == 0 for r in results)
    tests = []
    for path in sorted((ROOT / 'backend/target/surefire-reports').glob('TEST-*.xml')):
        suite = ET.parse(path).getroot()
        entry = {key: int(suite.get(key, '0')) for key in ('tests', 'failures', 'errors', 'skipped')}
        entry['suite'] = suite.get('name')
        entry['cases'] = [case.get('name') for case in suite.findall('testcase')]
        tests.append(entry)
    assert tests and all(t['failures'] == t['errors'] == t['skipped'] == 0 for t in tests)
    frontend_log = (ROOT / '.artifacts/frontend-tests.log').read_text(encoding='utf-8', errors='replace')
    frontend_count = re.search(r'Tests\s+(\d+) passed \((\d+)\)', frontend_log)
    assert frontend_count and frontend_count[1] == frontend_count[2]
    coverage = ET.parse(ROOT / 'backend/target/site/jacoco/jacoco.xml').getroot()
    missed = covered = 0
    packages = []
    for package in coverage.findall('package'):
        if not package.get('name').endswith('/service'):
            continue
        counter = next(c for c in package.findall('counter') if c.get('type') == 'BRANCH')
        m, c = int(counter.get('missed')), int(counter.get('covered'))
        missed += m
        covered += c
        packages.append({'package': package.get('name'), 'branchesCovered': c, 'branchesMissed': m})
    assert covered / (covered + missed) >= .80
    contract = json.loads((ROOT / 'contracts/openapi.json').read_text(encoding='utf-8'))
    operations = [op for item in contract['paths'].values() for method, op in item.items()
                  if method in ('get', 'post', 'put', 'delete')]
    statuses = {status: sum(op['x-implementation-status'] == status for op in operations)
                for status in ('IMPLEMENTED', 'NOT_IMPLEMENTED', 'DISABLED')}
    assert statuses == {'IMPLEMENTED': 23, 'NOT_IMPLEMENTED': 68, 'DISABLED': 1}
    paths = [ROOT / name for name in ('AGENTS.md', 'README.md', 'backend/pom.xml',
             'frontend/pnpm-lock.yaml', 'contracts/openapi.json', 'infra/versions.lock.json',
             'infra/versions.stage-b.lock.json', 'infra/backend-dependencies.lock.txt')]
    paths += sorted((ROOT / 'backend/src/main/resources/db/migration').glob('*.sql'))
    paths += sorted((ROOT / 'backend/src/main/java').rglob('*.java'))
    paths += sorted((ROOT / 'backend/src/test/java').rglob('*.java'))
    paths += sorted((ROOT / 'backend/src/main/resources').glob('application*.yml'))
    paths += sorted((ROOT / 'docs').glob('*.md'))
    paths += sorted((ROOT / 'docs/stages').glob('B-*.md'))
    paths += sorted((ROOT / 'scripts').glob('*.py'))
    paths += sorted((ROOT / 'scripts').glob('*.ps1'))
    report = {
        'verifiedAtUtc': datetime.now(timezone.utc).isoformat(),
        'git': 'No repository / commit / PR available',
        'stage': 'B', 'contractVersion': contract['info']['version'],
        'actualToolchainOutput': (ROOT / '.artifacts/B-toolchain.txt').read_text(encoding='utf-8-sig'),
        'verificationCommands': results, 'backendTestsTotal': sum(t['tests'] for t in tests),
        'backendTests': tests, 'frontendTests': int(frontend_count[1]), 'contractOperations': statuses,
        'serviceBranchCoverage': {'covered': covered, 'missed': missed, 'ratio': covered / (covered + missed), 'packages': packages},
        'migrationVersions': ['1', '2', '3', '4'],
        'buildArtifactSha256': {name: hashlib.sha256((ROOT / name).read_bytes()).hexdigest() for name in (
            'backend/target/yueji-mall-0.1.0-SNAPSHOT.jar', 'frontend/dist/index.html')},
        'limitations': ['No Docker daemon / Testcontainers run', 'No remote CI/CD or production rollout',
                        'No business browser E2E, performance or recovery exercise',
                        'TC-02 covers Stage B resources; later business objects remain unimplemented'],
        'sha256': {path.relative_to(ROOT).as_posix(): hashlib.sha256(path.read_bytes()).hexdigest() for path in paths}}
    DEST.mkdir(exist_ok=True)
    for source, name in [('smoke-results.json', 'B-smoke.json'), ('B-identity-smoke.json', 'B-identity-smoke.json'),
                         ('B-database-evidence.json', 'B-database.json')]:
        payload = json.loads((ROOT / '.artifacts' / source).read_text(encoding='utf-8-sig'))
        (DEST / name).write_text(json.dumps(payload, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
    (DEST / 'B-verification.json').write_text(json.dumps(report, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
    print(f"Evidence: {report['backendTestsTotal']} backend tests; service branches {covered}/{covered + missed} ({covered / (covered + missed):.2%})")


if __name__ == '__main__':
    main()
