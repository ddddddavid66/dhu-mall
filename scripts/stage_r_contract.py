"""收尾实现沿用 API-89 已接受字段，不增加接口或业务字段。"""
from stage_b_contract import obj, ref, query


def apply_stage_r(schemas, paths):
    # 04 的 ? 字段、省略列表明细和商家个人信息屏蔽规则。
    for name, optional in {
        'SettlementView': {'createdBy', 'approvedBy', 'paidAt', 'mockPayoutNo', 'items', 'adjustments'},
        'SettlementAdjustmentView': {'createdBy', 'approvedBy', 'targetSettlementId'},
    }.items():
        schemas[name]['required'] = [field for field in schemas[name]['required'] if field not in optional]
    schemas['SettlementAdjustmentView']['properties'].update({
        'originalSettlementId': ref('Id'), 'orderId': ref('Id'), 'issueId': ref('Id'),
    })
    schemas['SettlementAdjustmentView']['required'] += ['originalSettlementId', 'orderId', 'issueId']
    schemas['DashboardView'] = obj({
        'todayOrderCount': {'type': 'integer', 'minimum': 0},
        'todayPaidAmount': ref('Money'),
        'pendingShipmentCount': {'type': 'integer', 'minimum': 0},
        'openAfterSaleCount': {'type': 'integer', 'minimum': 0},
        'unresolvedIssueCount': {'type': 'integer', 'minimum': 0},
    }, required=[])
    schemas['ResultDashboardView'] = obj({**schemas['Result']['properties'], 'data': ref('DashboardView')})
    operation = paths['/api/v1/dashboard']['get']
    operation.update({'x-implementation-status': 'IMPLEMENTED', 'tags': ['Platform'],
                      'description': '按有效动作权限裁剪；不允许读取他店范围；日期按上海自然日；无权字段省略。',
                      'security': [{'bearerAuth': []}], 'parameters': [query('shopId', ref('Id'))]})
    operation['responses'] = {'200': {'description': '真实权限内统计', 'content': {'application/json': {'schema': ref('ResultDashboardView')}}}}
    for status in ('400', '401', '403', '404', '429', '500', '503'):
        operation['responses'][status] = {'description': '统一错误', 'content': {'application/json': {'schema': ref('ResultError')}}}
