"""从设计清单同步阶段 A 契约，check 模式只检查漂移，不修改文件。"""
from pathlib import Path
import argparse
import json
import re
import sys
from stage_b_contract import apply_stage_b, IMPLEMENTED
from stage_c_contract import apply_stage_c, IMPLEMENTED as STAGE_C_IMPLEMENTED
from stage_d_contract import apply_stage_d, IMPLEMENTED as STAGE_D_IMPLEMENTED
from stage_e_contract import apply_stage_e, IMPLEMENTED as STAGE_E_IMPLEMENTED
from stage_f_contract import apply_stage_f, IMPLEMENTED as STAGE_F_IMPLEMENTED

ROOT = Path(__file__).resolve().parents[1]
SPEC = ROOT / 'docs' / '04-接口设计.md'


def build():
    text = SPEC.read_text(encoding='utf-8-sig')
    rows = re.findall(r'^\| (API-\d+) \| (GET|POST|PUT|DELETE) (\S+) \| ([^|]+) \| ([^|]+) \|$', text, re.M)
    if len(rows) != 92 or len({r[0] for r in rows}) != 92:
        raise ValueError('接口清单必须明确包含 API-01 至 API-92，共 92 项')
    codes = re.findall(r'^    ([A-Z_]+)\(\d+,', (ROOT / 'backend/src/main/java/com/yueji/mall/common/api/ErrorCode.java').read_text(encoding='utf-8'), re.M)
    schemas = {
        'Id': {'type': 'string', 'pattern': '^[1-9][0-9]{0,19}$', 'maxLength': 20,
               'description': '正的 BIGINT UNSIGNED 十进制字符串，上限 18446744073709551615，由服务端额外核验'},
        'Money': {'type': 'string', 'pattern': r'^-?(0|[1-9][0-9]{0,9})\.[0-9]{2}$',
                  'description': '人民币 DECIMAL(12,2)，仅调整额允许负值；具体业务约束见设计'},
        'ErrorCode': {'type': 'string', 'enum': codes},
        'FieldErrorDetail': {'type': 'object', 'required': ['field', 'reason'], 'additionalProperties': False,
                             'properties': {'field': {'type': 'string'}, 'reason': {'type': 'string'}}},
        'ResultError': {'type': 'object', 'required': ['code', 'message', 'data', 'traceId'],
                        'additionalProperties': False,
                        'properties': {'code': {'$ref': '#/components/schemas/ErrorCode'},
                                       'message': {'type': 'string'}, 'data': {'type': 'null'},
                                       'traceId': {'type': 'string', 'minLength': 8, 'maxLength': 64},
                                       'details': {'type': 'array', 'items': {'$ref': '#/components/schemas/FieldErrorDetail'}}}},
        'Result': {'type': 'object', 'required': ['code', 'message', 'data', 'traceId'],
                   'additionalProperties': False, 'description': '供后续已实现接口引用，本阶段没有成功的业务路由',
                   'properties': {'code': {'type': 'string', 'const': 'OK'}, 'message': {'type': 'string'},
                                  'data': {}, 'traceId': {'type': 'string'}}},
        'Page': {'type': 'object', 'required': ['items', 'page', 'pageSize', 'total'],
                 'additionalProperties': False,
                 'properties': {'items': {'type': 'array', 'items': {}},
                                'page': {'type': 'integer', 'minimum': 1, 'default': 1},
                                'pageSize': {'type': 'integer', 'minimum': 1, 'maximum': 100, 'default': 20},
                                'total': {'type': 'integer', 'minimum': 0}}}}
    paths = {}
    inventory = ['# 阶段 A 接口契约清单', '',
                 '由 scripts/sync-contract.py 从 04 设计目录生成；不能手工修改本清单。', '',
                 '所有业务能力尚未实现。默认返回 501 NOT_IMPLEMENTED；API-34 的模拟付款入口不注册，返回 404。',
                 '计划请求、响应及权限仅为设计引用，不代表已实现参数验证、JWT、RBAC 或渠道验签。', '',
                 '| 编号 | 方法 | 完整路径 | 当前状态 | 计划权限/范围 | 计划契约 |',
                 '|---|---|---|---|---|---|']
    for operation_id, method, path, permission, planned in sorted(rows, key=lambda r: int(r[0].split('-')[1])):
        full_path = '/api/v1' + path
        disabled = operation_id == 'API-34'
        parameters = []
        for name in re.findall(r'\{([^}]+)\}', path):
            schema = {'type': 'string', 'enum': ['MERCHANT', 'WAREHOUSE']} if name == 'duty' else {'$ref': '#/components/schemas/Id'}
            parameters.append({'name': name, 'in': 'path', 'required': True, 'schema': schema})
        response = {'description': '模拟付款路由未启用' if disabled else '尚未实现；无任何业务成功或副作用',
                    'headers': {'X-Request-Id': {'description': '与错误响应 traceId 一致', 'schema': {'type': 'string'}}},
                    'content': {'application/json': {'schema': {'$ref': '#/components/schemas/ResultError'}}}}
        operation = {'operationId': operation_id, 'summary': f'{operation_id} {method} {path}',
                     'description': f'阶段 A 契约占位。计划权限：{permission.strip()}。计划输入输出：{planned.strip()}。完整业务规则见 docs/04-接口设计.md。',
                     'tags': ['StageA'], 'security': [], 'parameters': parameters,
                     'x-implementation-status': 'DISABLED' if disabled else 'NOT_IMPLEMENTED',
                     'x-planned-authorization': permission.strip(), 'x-planned-contract': planned.strip(),
                     'x-design-source': 'docs/04-接口设计.md',
                     'x-requires-idempotency-key': ' K' in permission,
                     'x-requires-version': ' V' in permission or ' K V' in permission,
                     'responses': {'404' if disabled else '501': response}}
        paths.setdefault(full_path, {})[method.lower()] = operation
        inventory.append(f'| {operation_id} | {method} | `{full_path}` | {operation["x-implementation-status"]} | {permission.strip()} | {planned.strip()} |')
    apply_stage_b(schemas,paths)
    apply_stage_c(schemas,paths)
    apply_stage_d(schemas,paths)
    apply_stage_e(schemas,paths)
    apply_stage_f(schemas,paths)
    current_inventory=['# 阶段 F 接口契约清单','','从 04 设计及阶段 B/C/D/E/F schema 生成；91 项操作已实现，API-89 仍为未实现占位。支付宝沙箱联调状态独立记录。','',*inventory[7:9]]
    for line in inventory[9:]:
        operation_id=line.split('|')[1].strip()
        if operation_id in IMPLEMENTED | STAGE_C_IMPLEMENTED | STAGE_D_IMPLEMENTED | STAGE_E_IMPLEMENTED | STAGE_F_IMPLEMENTED: line=line.replace('NOT_IMPLEMENTED','IMPLEMENTED').replace('DISABLED','IMPLEMENTED')
        current_inventory.append(line)
    contract = {'openapi': '3.1.0', 'info': {'title': '阅集图书商城阶段 F 契约', 'version': '0.6.0',
                'description': '阶段 B/C/D/E/F 共 91 项操作已实现；API-89 运营仪表盘仍为占位。支付宝沙箱联调结果独立记录。'},
                'servers': [{'url': '/'}], 'tags': [{'name': 'StageA', 'description': '未实现的设计清单'},{'name':'Identity','description':'阶段 B 已实现身份地址和管理'},{'name':'Catalog','description':'阶段 C 书目商品库存文件购物车'},{'name':'Trade','description':'阶段 D 订单与阶段 F 履约'},{'name':'Payment','description':'阶段 E/F 支付、退款和对账'},{'name':'Fulfillment','description':'阶段 F 发货与收货'},{'name':'AfterSale','description':'阶段 F 售后流程'},{'name':'Review','description':'阶段 F 评价与审核'},{'name':'Reconciliation','description':'阶段 F 渠道核对和差异'},{'name':'Settlement','description':'阶段 F 结算、调整和模拟出款'}],
                'paths': paths, 'components': {'schemas': schemas,
                'securitySchemes': {'bearerAuth': {'type': 'http', 'scheme': 'bearer', 'bearerFormat': 'JWT',
                                    'description': '15 分钟访问 JWT，服务端核对数据库会话及 authVersion'},
                                    'refreshCookie': {'type':'apiKey','in':'cookie','name':'refreshToken'},
                                    'csrfToken': {'type':'apiKey','in':'header','name':'X-CSRF-Token'}}}}
    api_operations = {}
    for path, methods in paths.items():
        for method, operation in methods.items():
            api_operations[operation['operationId']] = {
                'method': method.upper(),
                'path': path,
                'status': operation['x-implementation-status'],
                'requiresIdempotencyKey': operation['x-requires-idempotency-key'],
                'requiresVersion': operation['x-requires-version'],
            }
    operation_lines = []
    for operation_id in sorted(api_operations, key=lambda value: int(value.split('-')[1])):
        operation = api_operations[operation_id]
        operation_lines.append(
            f'  "{operation_id}": {{ method: {json.dumps(operation["method"])}, '
            f'path: {json.dumps(operation["path"])}, status: {json.dumps(operation["status"])}, '
            f'requiresIdempotencyKey: {str(operation["requiresIdempotencyKey"]).lower()}, '
            f'requiresVersion: {str(operation["requiresVersion"]).lower()} }},'
        )
    generated = ('// Generated by scripts/sync-contract.py; do not edit.\n'
                 'export const ID_PATTERN = ' + json.dumps(schemas['Id']['pattern']) + '\n'
                 'export const MONEY_PATTERN = ' + json.dumps(schemas['Money']['pattern']) + '\n'
                 'export const MAX_ID = "18446744073709551615"\n'
                 'export const DEFAULT_PAGE = 1\nexport const DEFAULT_PAGE_SIZE = 20\nexport const MAX_PAGE_SIZE = 100\n'
                 'export const ERROR_CODES = ' + json.dumps(codes) + ' as const\n'
                 'export type ErrorCode = typeof ERROR_CODES[number]\n'
                 'export type ApiId = string & { readonly __brand: "ApiId" }\n'
                 'export type ApiMoney = string & { readonly __brand: "ApiMoney" }\n'
                 'export const API_OPERATIONS = {\n' + '\n'.join(operation_lines) + '\n} as const\n'
                 'export type ApiOperationId = keyof typeof API_OPERATIONS\n')
    return {ROOT / 'contracts/openapi.json': json.dumps(contract, ensure_ascii=False, indent=2) + '\n',
            ROOT / 'docs/stages/F-接口契约清单.md': '\n'.join(current_inventory) + '\n',
            ROOT / 'frontend/src/api/generated.ts': generated}


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--check', action='store_true')
    args = parser.parse_args()
    mismatches = []
    for path, content in build().items():
        if args.check:
            if not path.exists() or path.read_text(encoding='utf-8') != content:
                mismatches.append(str(path.relative_to(ROOT)))
        else:
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text(content, encoding='utf-8')
    if mismatches:
        print('Contract drift:', ', '.join(mismatches))
        return 1
    print('Contract and inventory: 92 operations; ' + ('in sync' if args.check else 'generated'))
    return 0


if __name__ == '__main__':
    sys.exit(main())
