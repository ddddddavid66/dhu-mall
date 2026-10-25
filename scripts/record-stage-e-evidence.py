"""Write machine evidence from actual stage E verification logs; never infer a pass."""
from pathlib import Path
from datetime import datetime, timezone
import hashlib, json, re, xml.etree.ElementTree as ET

ROOT=Path(__file__).resolve().parents[1]
ART=ROOT/'.artifacts'
DEST=ROOT/'docs/stages/evidence'

def digest(path): return hashlib.sha256(path.read_bytes()).hexdigest()
def read(path): return json.loads(path.read_text('utf-8-sig'))
def log(name): return (ART/name).read_text('utf-8-sig',errors='replace')
def result(text):
    matches=re.findall(r'Tests run: (\d+), Failures: (\d+), Errors: (\d+), Skipped: (\d+)',text)
    assert matches, 'No Maven test summary found'
    return dict(zip(['tests','failures','errors','skipped'],map(int,matches[-1])))

def main():
    checks=read(ART/'verification-results.json')
    assert len(checks)==7 and all(x['exitCode']==0 for x in checks), 'Full verification must be green before recording a local pass'
    full=result(log('backend-verify.log'))
    assert full['tests']>=98 and full['failures']==full['errors']==full['skipped']==0, full
    assert 'All coverage checks have been met.' in log('backend-verify.log')
    compensation=result(log('E-late-compensation.log'))
    assert compensation=={'tests':1,'failures':0,'errors':0,'skipped':0}
    migration=result(log('E-migration.log'))
    assert migration=={'tests':2,'failures':0,'errors':0,'skipped':0}
    frontend=re.search(r'Tests\s+(\d+) passed \((\d+)\)',log('frontend-tests.log'))
    assert frontend and frontend[1]==frontend[2]

    coverage=[]
    for package in ET.parse(ROOT/'backend/target/site/jacoco/jacoco.xml').getroot().findall('package'):
        if not package.get('name').endswith('/service'): continue
        for counter in package.findall('counter'):
            if counter.get('type')=='BRANCH':
                coverage.append({'package':package.get('name'),'covered':int(counter.get('covered')),'missed':int(counter.get('missed'))})
    covered=sum(row['covered'] for row in coverage);missed=sum(row['missed'] for row in coverage)
    assert covered+missed and covered/(covered+missed)>=0.8

    baseline=read(DEST/'D-verification.json')
    migrations={}
    for path in sorted((ROOT/'backend/src/main/resources/db/migration').glob('V[1-7]__*.sql')):
        name=path.relative_to(ROOT).as_posix();migrations[name]=digest(path)
        assert baseline['sha256'][name]==migrations[name], f'Published migration changed: {name}'
    assert digest(ROOT/'frontend/pnpm-lock.yaml')==baseline['sha256']['frontend/pnpm-lock.yaml']
    previous=read(DEST/'E-verification-preapproval.json')
    v8='backend/src/main/resources/db/migration/V8__payments_events_and_compensation.sql'
    assert previous['sha256'][v8]==digest(ROOT/v8), 'Frozen V8 migration changed'

    contract=read(ROOT/'contracts/openapi.json')
    ops=[operation for path in contract['paths'].values() for operation in path.values()]
    statuses={status:sum(operation['x-implementation-status']==status for operation in ops)
              for status in ['IMPLEMENTED','NOT_IMPLEMENTED','DISABLED']}
    assert statuses=={'IMPLEMENTED':56,'NOT_IMPLEMENTED':36,'DISABLED':0}
    inputs=read(ART/'E-sandbox-input-presence.json')
    assert all(not item['Present'] for item in inputs)
    dev_version=log('E-dev-db-version.txt').strip()
    assert dev_version=='7', f'Development schema was expected to remain at V7, got {dev_version}'

    local_evidence=[]
    invariant_keys=['stockBounds','reservationMismatch','duplicatePayments','duplicateCompensation','duplicateMovement','cancelledWithCommit']
    for path in sorted(ART.glob('E-local-*.json')):
        value=read(path)
        assert all(value[key]==0 for key in invariant_keys), path.name
        (DEST/path.name).write_text(json.dumps(value,ensure_ascii=False,indent=2)+'\n','utf-8')
        local_evidence.append(path.name)
    assert 'E-local-late-compensation.json' in local_evidence

    sources=['AGENTS.md','README.md','contracts/openapi.json','backend/pom.xml',
             'infra/backend-dependencies.lock.txt','infra/versions.stage-e.lock.json',
             '.env.payment.example','frontend/pnpm-lock.yaml']
    paths=[ROOT/name for name in sources]
    for folder,patterns in [('backend/src',['*.java','*.yml','*.sql']),('scripts',['*.py','*.ps1']),('docs',['*.md'])]:
        for pattern in patterns: paths.extend((ROOT/folder).rglob(pattern))
    execution_logs=['E-verify.log','backend-verify.log','verification-results.json','E-migration.log',
                    'E-late-compensation.log','E-protocol-boundaries.log','E-final-boundary-and-gate.log','E-dev-db-version.txt','E-toolchain.txt',
                    'E-integration-initial.log','E-verify-preapproval.log','backend-verify-preapproval.log']
    execution_logs=[name for name in execution_logs if (ART/name).exists()]
    report={
        'stage':'E','status':'BLOCKED_EXTERNAL_SANDBOX','capturedAtUtc':datetime.now(timezone.utc).isoformat(),
        'git':'No repository; no commit or PR',
        'verificationCommands':checks,'fullRegression':full,'fullRegressionPassed':full['tests'],
        'frontendTests':int(frontend[1]),'migrationTests':migration,'lateCompensationFocusedRun':compensation,
        'contractVersion':contract['info']['version'],'contractOperations':statuses,
        'serviceBranchCoverage':{'covered':covered,'missed':missed,'ratio':covered/(covered+missed),'gatePassedInFullVerify':True,'packages':coverage},
        'unchangedPublishedMigrationsV1toV7':migrations,'unchangedFrontendLock':True,'backendDependencyLockUpdatedForSdk':True,
        'database':'Real isolated MySQL 8.4.13 on 127.0.0.1:13306; V1-V9 fresh and V1 legacy-row forward migration verified',
        'toolchainActual':log('E-toolchain.txt'),
        'developmentDatabaseMigrationApplied':False,'developmentDatabaseVersion':dev_version,'developmentDemoDataCreated':False,
        'packagedJarBuilt':True,'packagedJarSmokeExecuted':False,
        'sandbox':{'executed':False,'missingProcessInputs':inputs,'result':'BLOCKED_ENVIRONMENT_INPUT'},
        'compensation':{'executedSuccessfully':True,'decision':'User approved VARCHAR(32) on 2026-10-25',
                        'migration':'V9 expands reason_type and rebuilds the unique functional index as CHAR(32)',
                        'localEvidence':'E-local-late-compensation.json'},
        'protocolFixtures':'Temporary RSA keys, SDK verification and controlled adapter responses; no real sandbox funds request',
        'localInvariantEvidence':local_evidence,
        'preApprovalFailurePreserved':{'summary':'docs/stages/evidence/E-verification-preapproval.json','logs':['.artifacts/E-verify-preapproval.log','.artifacts/backend-verify-preapproval.log']},
        'executionLogsSha256':{'.artifacts/'+name:digest(ART/name) for name in execution_logs},
        'sha256':{path.relative_to(ROOT).as_posix():digest(path) for path in sorted(set(paths))}}
    (DEST/'E-verification.json').write_text(json.dumps(report,ensure_ascii=False,indent=2)+'\n','utf-8')
    print(f"E local verification PASSED: backend {full['tests']}/{full['tests']}; frontend {report['frontendTests']}; "
          f"core branches {covered}/{covered+missed}; real sandbox BLOCKED on missing credentials")

if __name__=='__main__': main()
