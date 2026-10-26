"""从实际HTTP观察生成92项证据矩阵；缺失覆盖必须显示，不能从实现标记推断通过。"""
from pathlib import Path
import argparse
import json

root = Path(__file__).resolve().parents[1]
parser = argparse.ArgumentParser()
parser.add_argument('--require-complete', action='store_true')
args = parser.parse_args()
spec = json.loads((root / 'contracts/openapi.json').read_text(encoding='utf-8'))
source = root / '.artifacts/R-contract-observations.jsonl'
observed = [json.loads(line) for line in source.read_text(encoding='utf-8').splitlines() if line.strip()] if source.exists() else []
rows = []
for path, methods in spec['paths'].items():
    for method, operation in methods.items():
        records = [row for row in observed if row['operationId'] == operation['operationId']]
        success = sorted({row['status'] for row in records if 200 <= row['status'] < 300 and row['code'] != 'CHANNEL_REJECTED'})
        errors = sorted({row['status'] for row in records if 400 <= row['status'] < 500 or row['code'] == 'CHANNEL_REJECTED'})
        rows.append(dict(operationId=operation['operationId'], method=method.upper(), path=path,
                         successStatuses=success, rejectionStatuses=errors,
                         observations=len(records), status='OBSERVED_BOTH' if success and errors else 'COVERAGE_GAP',
                         sources=sorted({row['source'] for row in records})))
rows.sort(key=lambda row: int(row['operationId'].split('-')[1]))
report = dict(note='HTTP observation evidence, not a claim of full schema or business assertion coverage',
              operations=92, observedBoth=sum(row['status'] == 'OBSERVED_BOTH' for row in rows), rows=rows)
target = root / 'docs/stages/evidence/R-contract-coverage.json'
target.write_text(json.dumps(report, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
lines = ['# 收尾阶段接口HTTP验收观察矩阵', '', report['note'], '',
         '| 操作 | 方法与路径 | 实际成功状态 | 实际拒绝状态 | 观察数 | 状态 |', '|---|---|---|---|---|---|']
for row in rows:
    lines.append(f"| {row['operationId']} | {row['method']} {row['path']} | {row['successStatuses']} | {row['rejectionStatuses']} | {row['observations']} | {row['status']} |")
(root / 'docs/stages/R-契约验收矩阵.md').write_text('\n'.join(lines) + '\n', encoding='utf-8')
print(json.dumps({'operations': 92, 'observedBoth': report['observedBoth'], 'gaps': [row['operationId'] for row in rows if row['status'] != 'OBSERVED_BOTH']}))
if args.require_complete and report['observedBoth'] != 92:
    raise SystemExit(1)
