"""仅从实际测试、覆盖率、冒烟和数据库输出生成 C 交接证据，不读取凭据。"""
from pathlib import Path
from datetime import datetime,timezone
import hashlib
import json
import re
import xml.etree.ElementTree as ET

ROOT=Path(__file__).resolve().parents[1]
DEST=ROOT/'docs/stages/evidence'


def main():
    checks=json.loads((ROOT/'.artifacts/verification-results.json').read_text(encoding='utf-8-sig'))
    assert len(checks)==7 and all(c['exitCode']==0 for c in checks)
    suites=[]
    for path in sorted((ROOT/'backend/target/surefire-reports').glob('TEST-*.xml')):
        root=ET.parse(path).getroot();suite={key:int(root.get(key,'0')) for key in ['tests','failures','errors','skipped']}
        suite.update({'suite':root.get('name'),'cases':[case.get('name') for case in root.findall('testcase')]})
        assert suite['failures']==suite['errors']==suite['skipped']==0;suites.append(suite)
    assert any('StageCHttpTests' in suite['suite'] for suite in suites)
    coverage=ET.parse(ROOT/'backend/target/site/jacoco/jacoco.xml').getroot();packages=[]
    for package in coverage.findall('package'):
        if not package.get('name').endswith('/service'):continue
        counters=[c for c in package.findall('counter') if c.get('type')=='BRANCH']
        for counter in counters:packages.append({'package':package.get('name'),'covered':int(counter.get('covered')),'missed':int(counter.get('missed'))})
    covered=sum(p['covered'] for p in packages);missed=sum(p['missed'] for p in packages);assert covered/(covered+missed)>=.80
    frontend=re.search(r'Tests\s+(\d+) passed \((\d+)\)',(ROOT/'.artifacts/frontend-tests.log').read_text(encoding='utf-8',errors='replace'));assert frontend and frontend[1]==frontend[2]
    spec=json.loads((ROOT/'contracts/openapi.json').read_text(encoding='utf-8'))
    operations=[op for path in spec['paths'].values() for method,op in path.items() if method in ['get','post','put','delete']]
    statuses={status:sum(op['x-implementation-status']==status for op in operations) for status in ['IMPLEMENTED','NOT_IMPLEMENTED','DISABLED']}
    assert statuses=={'IMPLEMENTED':46,'NOT_IMPLEMENTED':45,'DISABLED':1}
    database=json.loads((ROOT/'.artifacts/C-database-evidence.json').read_text(encoding='utf-8-sig'))
    assert database['orders']==database['afterSales']==database['reviews']==0
    assert database['products']==database['skus']==30 and database['books']==20 and database['categories']==10
    paths=[ROOT/name for name in ['AGENTS.md','README.md','backend/pom.xml','frontend/pnpm-lock.yaml','contracts/openapi.json','infra/versions.lock.json','infra/versions.stage-b.lock.json','infra/versions.stage-c.lock.json','infra/backend-dependencies.lock.txt']]
    for folder,pattern in [('backend/src','*.java'),('backend/src/main/resources','*.yml'),('backend/src/main/resources/db/migration','*.sql'),('scripts','*.py'),('scripts','*.ps1'),('docs','*.md'),('frontend/src','*.ts'),('frontend/src','*.vue'),('frontend/tests','*.ts')]:paths+=sorted((ROOT/folder).rglob(pattern))
    paths+=sorted(path for path in (ROOT/'backend/src/test/resources').rglob('*') if path.is_file())
    # 证据文件自身不纳入递归哈希；避免自引用并且不读取 .tools / 凭据文件。
    for source,target in [('smoke-results.json','C-smoke.json'),('B-identity-smoke.json','C-identity-smoke.json'),('C-api-smoke.json','C-api-smoke.json'),('C-demo-data.json','C-demo-data.json'),('C-database-evidence.json','C-database.json')]:
        value=json.loads((ROOT/'.artifacts'/source).read_text(encoding='utf-8-sig'))
        if 'checks' in value:assert all(check['passed'] for check in value['checks'])
        if source=='C-demo-data.json':assert value['status']=='READY'
        (DEST/target).write_text(json.dumps(value,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
    report={'stage':'C','verifiedAtUtc':datetime.now(timezone.utc).isoformat(),'git':'No repository / commit / PR available',
            'contractVersion':spec['info']['version'],'contractOperations':statuses,'verificationCommands':checks,
            'backendTestsTotal':sum(s['tests'] for s in suites),'backendTests':suites,'frontendTests':int(frontend[1]),
            'serviceBranchCoverage':{'covered':covered,'missed':missed,'ratio':covered/(covered+missed),'packages':packages},
            'actualToolchainOutput':(ROOT/'.artifacts/C-toolchain.txt').read_text(encoding='utf-8-sig'),
            'migrationVersions':['1','2','3','4','5','6'],'databaseRuntime':'Real isolated MySQL 8.4.13 Windows; no H2',
            'limitations':['No Docker/Testcontainers/Linux run','No remote CI/CD, vulnerability scan, business browser E2E, load or recovery exercise','No Redis cache enabled','Order/after-sale/review writes remain unimplemented'],
            'sha256':{path.relative_to(ROOT).as_posix():hashlib.sha256(path.read_bytes()).hexdigest() for path in sorted(set(paths))},
            'artifactSha256':hashlib.sha256((ROOT/'backend/target/yueji-mall-0.1.0-SNAPSHOT.jar').read_bytes()).hexdigest()}
    (DEST/'C-verification.json').write_text(json.dumps(report,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
    print(f"Stage C evidence: {report['backendTestsTotal']} backend / {report['frontendTests']} frontend tests; service branches {covered}/{covered+missed} ({covered/(covered+missed):.2%})")


if __name__=='__main__':main()
