"""从gh的结构化只读输出生成脱敏CI证据，不解析或发布原始环境日志。"""
from pathlib import Path
import argparse
import json

ROOT = Path(__file__).resolve().parents[1]
parser = argparse.ArgumentParser()
parser.add_argument('input')
args = parser.parse_args()
data = json.loads(Path(args.input).read_text(encoding='utf-8-sig'))
report = {key: data[key] for key in ('databaseId', 'headSha', 'status', 'conclusion', 'url')}
report['jobs'] = [{key: job[key] for key in ('name', 'status', 'conclusion', 'url')} for job in data['jobs']]
for source, job in zip(data['jobs'], report['jobs']):
    job['steps'] = [{key: step[key] for key in ('name', 'status', 'conclusion')}
                    for step in source.get('steps', [])]
report['productionDeployment'] = False
destination = ROOT / 'docs/stages/evidence' / f"R-ci-{data['databaseId']}.json"
destination.write_text(json.dumps(report, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
print(destination.name, report['status'], report['conclusion'])
