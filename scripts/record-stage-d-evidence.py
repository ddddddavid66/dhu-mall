"""仅从实际测试、数据库和冒烟记录汇总 D 证据，不读取任何凭据。"""
from pathlib import Path
from datetime import datetime,timezone
import hashlib,json,re,xml.etree.ElementTree as ET

ROOT=Path(__file__).resolve().parents[1]
DEST=ROOT/'docs/stages/evidence'

def read(name):return json.loads((ROOT/'.artifacts'/name).read_text(encoding='utf-8-sig'))
def digest(path):return hashlib.sha256(path.read_bytes()).hexdigest()

def main():
    checks=read('verification-results.json');assert len(checks)==7 and all(c['exitCode']==0 for c in checks)
    suites=[]
    for path in sorted((ROOT/'backend/target/surefire-reports').glob('TEST-*.xml')):
        root=ET.parse(path).getroot();suite={key:int(root.get(key,'0')) for key in ['tests','failures','errors','skipped']}
        assert suite['failures']==suite['errors']==suite['skipped']==0
        suite.update({'suite':root.get('name'),'cases':[case.get('name') for case in root.findall('testcase')]});suites.append(suite)
    assert any('StageDHttpTests' in s['suite'] and 'tc09OneHundredIndependentBuyersCompeteForLastTen' in s['cases'] for s in suites)
    coverage=ET.parse(ROOT/'backend/target/site/jacoco/jacoco.xml').getroot();packages=[]
    for package in coverage.findall('package'):
        if not package.get('name').endswith('/service'):continue
        for c in package.findall('counter'):
            if c.get('type')=='BRANCH':packages.append({'package':package.get('name'),'covered':int(c.get('covered')),'missed':int(c.get('missed'))})
    covered=sum(p['covered'] for p in packages);missed=sum(p['missed'] for p in packages);assert covered/(covered+missed)>=.80
    frontend=re.search(r'Tests\s+(\d+) passed \((\d+)\)',(ROOT/'.artifacts/frontend-tests.log').read_text(encoding='utf-8',errors='replace'));assert frontend and frontend[1]==frontend[2]
    contract=json.loads((ROOT/'contracts/openapi.json').read_text(encoding='utf-8'))
    operations=[op for path in contract['paths'].values() for method,op in path.items() if method in ['get','post','put','delete']]
    statuses={s:sum(op['x-implementation-status']==s for op in operations) for s in ['IMPLEMENTED','NOT_IMPLEMENTED','DISABLED']}
    assert statuses=={'IMPLEMENTED':52,'NOT_IMPLEMENTED':39,'DISABLED':1}
    old=json.loads((DEST/'C-verification.json').read_text(encoding='utf-8'))
    unchanged={}
    for path in sorted((ROOT/'backend/src/main/resources/db/migration').glob('V[1-6]__*.sql')):
        name=path.relative_to(ROOT).as_posix();unchanged[name]=digest(path);assert unchanged[name]==old['sha256'][name]
    for name in ['frontend/pnpm-lock.yaml','infra/backend-dependencies.lock.txt']:
        assert digest(ROOT/name)==old['sha256'][name]
    database=read('D-database-evidence.json');assert database['migrationCount']==7 and database['tableCount']==30
    zero=['stockConstraintViolations','reservationMismatches','ledgerMismatches','batchAmountMismatches','orderAmountMismatches','missingReservations','movementMismatches','outboxMismatches','previewMismatches','missingIdempotencyResource']
    assert all(database[k]==0 for k in zero)
    assert database['batches']==1 and database['orders']==database['reservations']==database['reserved']==2 and database['cancelledOrders']==database['publishedOutbox']==0
    sources={'smoke-results.json':'D-smoke.json','B-identity-smoke.json':'D-identity-smoke.json','C-api-smoke.json':'D-catalog-smoke.json','D-api-smoke-created.json':'D-api-smoke.json','D-api-smoke.json':'D-api-smoke-repeat.json','D-database-evidence.json':'D-database.json','D-demo-data.json':'D-demo-data.json','D-query-plans.json':'D-query-plans.json'}
    for path in sorted((ROOT/'.artifacts').glob('D-tc*.json')):sources[path.name]=path.name
    sources.update({'D-closing-invariants.json':'D-closing-invariants.json','D-ordering-invariants.json':'D-ordering-invariants.json'})
    for source,target in sources.items():
        value=read(source)
        if 'checks' in value:assert all(c['passed'] for c in value['checks'])
        if 'ConstraintViolations' in str(value):
            assert all(v==0 for k,v in value.items() if k.endswith(('Mismatches','Violations')) or k.startswith('missing'))
        (DEST/target).write_text(json.dumps(value,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
    startup=(ROOT/'.artifacts/D-app-restart.log').read_text(encoding='utf-8',errors='replace')
    assert 'No migration necessary' in startup and 'Started YuejiApplication' in startup
    paths=[ROOT/p for p in ['AGENTS.md','README.md','backend/pom.xml','frontend/pnpm-lock.yaml','contracts/openapi.json','infra/versions.lock.json','infra/versions.stage-b.lock.json','infra/versions.stage-c.lock.json','infra/versions.stage-d.lock.json','infra/backend-dependencies.lock.txt']]
    for folder,pattern in [('backend/src','*.java'),('backend/src/main/resources','*.yml'),('backend/src/main/resources/db/migration','*.sql'),('scripts','*.py'),('scripts','*.ps1'),('docs','*.md'),('frontend/src','*.ts'),('frontend/src','*.vue'),('frontend/tests','*.ts')]:paths+=sorted((ROOT/folder).rglob(pattern))
    paths+=sorted(p for p in (ROOT/'backend/src/test/resources').rglob('*') if p.is_file())
    primary_smoke=sum(read(name)['total'] for name in ['smoke-results.json','B-identity-smoke.json','C-api-smoke.json','D-api-smoke-created.json'])
    report={'stage':'D','verifiedAtUtc':datetime.now(timezone.utc).isoformat(),'git':'No repository; no commit or PR',
        'contractVersion':contract['info']['version'],'contractOperations':statuses,'verificationCommands':checks,
        'backendTestsTotal':sum(s['tests'] for s in suites),'backendTests':suites,'frontendTests':int(frontend[1]),
        'serviceBranchCoverage':{'covered':covered,'missed':missed,'ratio':covered/(covered+missed),'packages':packages},
        'primarySmokeChecks':primary_smoke,'additionalDemoRepeatChecks':read('D-api-smoke.json')['total'],
        'unchangedPublishedMigrations':unchanged,'migrationVersions':[str(i) for i in range(1,8)],
        'actualToolchainOutput':(ROOT/'.artifacts/D-toolchain.txt').read_text(encoding='utf-8-sig'),
        'databaseRuntime':'Isolated real MySQL 8.4.13 Windows, 127.0.0.1:13306; no H2',
        'restartVerified':True,'demoDataAccount':'Random local dev account only; credentials excluded',
        'limitations':['Payment and confirmed close/release remain stage E','Outbox is durable PENDING only; no broker publish/consumer claim','No business checkout UI or browser E2E','No Docker/Linux, remote CI/CD, vulnerability scan, load benchmark or recovery exercise'],
        'sha256':{p.relative_to(ROOT).as_posix():digest(p) for p in sorted(set(paths))},
        'artifactSha256':digest(ROOT/'backend/target/yueji-mall-0.1.0-SNAPSHOT.jar')}
    (DEST/'D-verification.json').write_text(json.dumps(report,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
    print(f"D evidence: {report['backendTestsTotal']} backend / {report['frontendTests']} frontend / {primary_smoke} primary smoke checks; {covered}/{covered+missed} core branches; V1-V6 unchanged")

if __name__=='__main__':main()
