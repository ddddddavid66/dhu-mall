"""汇总实际报告，不从实现标记或设计阈值推导测试通过。"""
from pathlib import Path
from datetime import datetime, timezone
import hashlib
import json
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]
PRIVATE = ROOT / '.artifacts'
PUBLIC = ROOT / 'docs/stages/evidence'
PUBLIC.mkdir(parents=True, exist_ok=True)

def read_json(path):
    return json.loads(path.read_text(encoding='utf-8-sig')) if path.exists() else None

def digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()

def backend_report():
    directory = PRIVATE / 'R-final-backend-reports'
    cases = []
    totals = dict(tests=0, failures=0, errors=0, skipped=0)
    safe = ET.Element('testsuites')
    for path in sorted((directory / 'surefire-reports').glob('TEST-*.xml')):
        suite = ET.parse(path).getroot()
        for key in totals: totals[key] += int(suite.get(key, '0'))
        output = ET.SubElement(safe, 'testsuite', {key: value for key, value in suite.attrib.items()
                                                  if key in ('name', 'tests', 'failures', 'errors', 'skipped', 'time', 'timestamp')})
        for case in suite.findall('testcase'):
            ET.SubElement(output, 'testcase', dict(case.attrib))
            cases.append(dict(suite=suite.get('name'), name=case.get('name'), seconds=float(case.get('time', '0'))))
    ET.ElementTree(safe).write(PUBLIC / 'R-junit-sanitized.xml', encoding='utf-8', xml_declaration=True)
    covered = missed = 0
    for item in ET.parse(directory / 'jacoco/jacoco.xml').getroot().iter('class'):
        if '/service/' not in item.get('name', ''): continue
        for counter in item.findall('counter'):
            if counter.get('type') == 'BRANCH':
                covered += int(counter.get('covered')); missed += int(counter.get('missed'))
    return dict(**totals, command='cd backend && ./mvnw.cmd -B -ntp clean verify',
                log='.artifacts/R-backend-clean-verify-final.log', coreBranchesCovered=covered,
                coreBranchesMissed=missed, coreBranchPercent=round(100*covered/(covered+missed), 4),
                gatePercent=80, gatePassed=covered/(covered+missed) >= .8,
                originalJacocoSha256=digest(directory / 'jacoco/jacoco.xml'), cases=cases)

def browser_report():
    report = read_json(PRIVATE / 'R-playwright.json')
    assert report is not None, '缺少实际 Playwright JSON'
    return dict(command='pnpm --dir frontend run test:acceptance', stats=report['stats'],
                log='.artifacts/R-browser-acceptance-final.log',
                scope='LOCAL_MOCK, desktop and mobile; no real Alipay sandbox claim')

def vulnerability_report():
    report = read_json(PRIVATE / 'R-trivy-backend-image.json')
    rows = [dict(id=v['VulnerabilityID'], package=v['PkgName'], installed=v['InstalledVersion'],
                 severity=v['Severity'], fixed=v.get('FixedVersion'))
            for result in report.get('Results', []) for v in result.get('Vulnerabilities') or []]
    return dict(status='FAILED_RISK_ACCEPTED_LOCAL_ONLY', findings=rows,
                decision='ADR-19; user canceled compatibility upgrades and accepts local non-production risk',
                image=report['ArtifactName'], scannerVersion=report['Trivy']['Version'],
                reportSha256=digest(PRIVATE / 'R-trivy-backend-image.json'))

report = dict(generatedAtUtc=datetime.now(timezone.utc).isoformat(),
              baseline='Java 21 / Spring Boot 3.5.16 / V11 / OpenAPI 0.7.0',
              backend=backend_report(), browser=browser_report(),
              contract=read_json(PUBLIC / 'R-contract-coverage.json'), security=vulnerability_report(),
              processCrash=read_json(PRIVATE / 'R-process-crash.json'),
              recovery=read_json(PRIVATE / 'R-recovery/R-local-recovery.json'),
              restoredApplication=read_json(PRIVATE / 'R-recovery/R-restored-application.json'),
              alipayConnectivity=read_json(PRIVATE / 'R-alipay-connectivity.json'),
              limitations=['GitHub Actions execution is recorded separately', 'Alipay buyer login/payment not available; read-only probe only',
                           'No independent offsite backup target', 'No fixed-profile load test',
                           '24-hour availability is not completed', 'Teacher template/member signatures not supplied'])
(PUBLIC / 'R-validation.json').write_text(json.dumps(report, ensure_ascii=False, indent=2)+'\n', encoding='utf-8')
for source, target in [('R-process-crash.json', 'R-process-crash.json'),
                       ('R-recovery/R-local-recovery.json', 'R-local-recovery.json'),
                       ('R-recovery/R-restored-application.json', 'R-restored-application.json'),
                       ('R-alipay-connectivity.json', 'R-alipay-connectivity.json')]:
    content = read_json(PRIVATE / source)
    if content: (PUBLIC / target).write_text(json.dumps(content, ensure_ascii=False, indent=2)+'\n', encoding='utf-8')
alerts = PRIVATE / 'R-alerts-observed.jsonl'
if alerts.exists(): (PUBLIC / 'R-alerts-observed.jsonl').write_bytes(alerts.read_bytes())
print(json.dumps(dict(backend=report['backend']['tests'], coverage=report['backend']['coreBranchPercent'],
                     browser=report['browser']['stats'], findings=len(report['security']['findings'])), ensure_ascii=False))
