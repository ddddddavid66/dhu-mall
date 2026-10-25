"""阶段 F 履约、售后、退款、评价和结算契约；业务规则仍以 04 为准。"""
from stage_b_contract import obj, ref, text, query
from stage_c_contract import array

IMPLEMENTED = {f'API-{i:02}' for i in [36, 37, *range(38, 63), 69, *range(78, 84), 91]}


def nullable(schema):
    return {'oneOf': [schema, {'type': 'null'}]}


def page(schemas, name, item):
    schemas[name] = obj({**schemas['Page']['properties'], 'items': array(ref(item))})


def apply_stage_f(schemas, paths):
    instant = {'type': 'string', 'format': 'date-time'}
    optional_time = {'type': ['string', 'null'], 'format': 'date-time'}
    version = {'type': 'integer', 'minimum': 1, 'maximum': 4294967295}
    yes_no = {'type': 'boolean'}

    schemas['ShipmentInput'] = obj({'carrier': text(50), 'trackingNo': text(80), 'version': version})
    schemas['ShipmentEventInput'] = obj({'eventTime': instant, 'description': text(300)})
    schemas['ShipmentItemView'] = obj({'orderItemId': ref('Id'), 'shippedQuantity': {'type': 'integer', 'minimum': 1}})
    schemas['ShipmentEventView'] = obj({'eventTime': instant, 'description': text(300), 'source': {'type': 'string', 'enum': ['MANUAL', 'SYSTEM']}})
    schemas['ShipmentView'] = obj({'id': ref('Id'), 'carrier': text(50), 'trackingNo': text(80), 'shippedAt': instant,
                                   'receivedAt': optional_time, 'items': array(ref('ShipmentItemView')),
                                   'events': array(ref('ShipmentEventView'))})
    # 阶段 F 将阶段 D 的只读物流占位替换为实际可空物流快照。
    schemas['OrderView']['properties']['shipment'] = nullable(ref('ShipmentView'))

    schemas['AfterSaleInput'] = obj({'orderItemId': ref('Id'), 'type': {'type': 'string', 'enum': ['REFUND_ONLY', 'RETURN_REFUND']},
                                     'quantity': {'type': 'integer', 'minimum': 1, 'maximum': 99}, 'reason': text(500),
                                     'evidenceFileIds': {'type': 'array', 'items': ref('Id'), 'maxItems': 5, 'uniqueItems': True}})
    schemas['AfterSaleDecision'] = obj({'approved': yes_no, 'note': text(500)})
    schemas['ReturnShipmentInput'] = obj({'carrier': text(50), 'trackingNo': text(80)})
    schemas['ReturnInspectionInput'] = obj({'accepted': yes_no, 'restock': yes_no, 'note': text(500)})
    schemas['RefundView'] = obj({'id': ref('Id'), 'refundNo': text(40), 'afterSaleId': nullable(ref('Id')), 'paymentId': ref('Id'),
                                 'reasonType': {'type': 'string'}, 'status': {'type': 'string', 'enum': ['CREATED', 'PROCESSING', 'SUCCEEDED', 'FAILED']},
                                 'amount': ref('Money'), 'failureCode': {'type': ['string', 'null']}, 'succeededAt': optional_time})
    schemas['AfterSaleView'] = obj({'id': ref('Id'), 'afterSaleNo': text(40), 'orderId': ref('Id'), 'orderItemId': ref('Id'),
                                    'type': {'type': 'string', 'enum': ['REFUND_ONLY', 'RETURN_REFUND']}, 'quantity': {'type': 'integer', 'minimum': 1},
                                    'requestedAmount': ref('Money'), 'reason': text(500),
                                    'status': {'type': 'string', 'enum': ['REQUESTED', 'APPROVED', 'RETURNING', 'RECEIVED', 'REFUNDING', 'REJECTED', 'CANCELLED', 'COMPLETED', 'EXPIRED']},
                                    'evidenceFileIds': array(ref('Id')), 'reviewNote': {'type': ['string', 'null']}, 'returnDeadline': optional_time,
                                    'returnCarrier': {'type': ['string', 'null']}, 'returnTrackingNo': {'type': ['string', 'null']},
                                    'restockQuantity': {'type': 'integer', 'minimum': 0}, 'refund': nullable(ref('RefundView')), 'createdAt': instant})
    schemas['OrderSnapshotLine'] = obj({'id': ref('Id'), 'bookTitle': text(200), 'isbn': text(13), 'unitPrice': ref('Money'),
                                        'quantity': {'type': 'integer', 'minimum': 1}, 'refundedQuantity': {'type': 'integer', 'minimum': 0},
                                        'refundedAmount': ref('Money')})
    schemas['AfterSaleOrderSnapshot'] = obj({'id': ref('Id'), 'orderNo': text(40),
                                             'status': {'type': 'string', 'enum': ['PENDING_PAYMENT', 'CLOSING', 'PAID', 'SHIPPED', 'COMPLETED', 'CANCELLED', 'CLOSED_REFUND']},
                                             'financialStatus': {'type': 'string', 'enum': ['UNPAID', 'PAID', 'PARTIALLY_REFUNDED', 'REFUNDED']},
                                             'payableAmount': ref('Money'), 'refundedAmount': ref('Money'), 'items': array(ref('OrderSnapshotLine')),
                                             'receiverName': text(40), 'receiverPhone': text(32), 'receiverAddress': text(300)})

    schemas['ReviewInput'] = obj({'orderItemId': ref('Id'), 'rating': {'type': 'integer', 'minimum': 1, 'maximum': 5}, 'content': text(1000)})
    schemas['ReviewVisibilityInput'] = obj({'status': {'type': 'string', 'enum': ['VISIBLE', 'HIDDEN']}, 'reason': text(300)})
    # 阶段 C 中 ReviewView 描述的是公开读取字段；这里保持相同字段并开放运营读取的审核状态。
    schemas['ReviewView']['properties']['status'] = {'type': 'string', 'enum': ['VISIBLE', 'HIDDEN']}
    schemas['ReviewView']['required'] = list(schemas['ReviewView']['properties'])

    schemas['RefundPage'] = obj({**schemas['Page']['properties'], 'items': array(ref('RefundView'))})
    schemas['AfterSalePage'] = obj({**schemas['Page']['properties'], 'items': array(ref('AfterSaleView'))})
    schemas['ReconciliationStart'] = obj({'billDate': {'type': 'string', 'format': 'date'}, 'provider': {'type': 'string', 'enum': ['ALIPAY_SANDBOX', 'LOCAL_MOCK']}})
    schemas['ReconciliationView'] = obj({'id': ref('Id'), 'provider': {'type': 'string', 'enum': ['ALIPAY_SANDBOX', 'LOCAL_MOCK']},
                                         'billDate': {'type': 'string', 'format': 'date'}, 'status': {'type': 'string', 'enum': ['RUNNING', 'SUCCEEDED', 'FAILED']},
                                         'checkedCount': {'type': 'integer', 'minimum': 0}, 'differenceCount': {'type': 'integer', 'minimum': 0}, 'completedAt': optional_time})
    schemas['ReconciliationIssueView'] = obj({'id': ref('Id'), 'issueType': text(64), 'orderId': nullable(ref('Id')), 'paymentId': nullable(ref('Id')),
                                              'refundId': nullable(ref('Id')), 'expectedAmount': nullable(ref('Money')), 'actualAmount': nullable(ref('Money')),
                                              'status': {'type': 'string', 'enum': ['OPEN', 'RESOLVED']}, 'resolution': {'type': ['string', 'null']}, 'createdAt': instant})
    schemas['IssueResolutionInput'] = obj({'resolution': text(1000), 'evidenceReference': text(255)})

    schemas['SettlementInput'] = obj({'shopId': ref('Id'), 'periodDate': {'type': 'string', 'format': 'date'}})
    schemas['SettlementDecision'] = obj({'approved': yes_no, 'note': text(500), 'version': version})
    schemas['SettlementPayoutInput'] = obj({'version': version})
    schemas['SettlementItemView'] = obj({'orderId': ref('Id'), 'goodsAmount': ref('Money'), 'refundAmount': ref('Money'), 'netAmount': ref('Money'),
                                         'feeAmount': ref('Money'), 'payableAmount': ref('Money')})
    schemas['SettlementAdjustmentView'] = obj({'id': ref('Id'), 'amount': ref('Money'), 'reason': text(500), 'status': {'type': 'string', 'enum': ['PENDING', 'APPROVED', 'APPLIED']},
                                               'createdBy': nullable(ref('Id')), 'approvedBy': nullable(ref('Id')), 'targetSettlementId': nullable(ref('Id'))})
    schemas['SettlementView'] = obj({'id': ref('Id'), 'settlementNo': text(40), 'shopId': ref('Id'), 'periodDate': {'type': 'string', 'format': 'date'},
                                     'status': {'type': 'string', 'enum': ['DRAFT', 'APPROVED', 'PAID', 'REJECTED']},
                                     'grossAmount': ref('Money'), 'refundAmount': ref('Money'), 'feeAmount': ref('Money'), 'adjustmentAmount': ref('Money'),
                                     'payableAmount': ref('Money'), 'createdBy': nullable(ref('Id')), 'approvedBy': nullable(ref('Id')),
                                     'paidAt': optional_time, 'mockPayoutNo': {'type': ['string', 'null']}, 'version': version,
                                     'items': {'oneOf': [array(ref('SettlementItemView')), {'type': 'null'}]},
                                     'adjustments': {'oneOf': [array(ref('SettlementAdjustmentView')), {'type': 'null'}]}})
    schemas['SettlementAdjustmentInput'] = obj({'originalSettlementId': ref('Id'), 'orderId': ref('Id'), 'issueId': ref('Id'), 'amount': ref('Money'), 'reason': text(500)})
    schemas['SettlementAdjustmentDecision'] = obj({'note': text(300)})
    page(schemas, 'ReconciliationPage', 'ReconciliationView')
    page(schemas, 'ReconciliationIssuePage', 'ReconciliationIssueView')
    page(schemas, 'SettlementPage', 'SettlementView')
    page(schemas, 'SettlementAdjustmentPage', 'SettlementAdjustmentView')

    models = {
        36: (None, 'OrderView', 200), 37: ('ReviewInput', 'ReviewView', 201), 38: (None, 'PageOrderSummary', 200),
        39: (None, 'OrderView', 200), 40: ('ShipmentInput', 'ShipmentView', 201), 41: ('ShipmentEventInput', 'ShipmentView', 201),
        42: ('AfterSaleInput', 'AfterSaleView', 201), 43: (None, 'AfterSalePage', 200), 44: (None, 'AfterSaleView', 200),
        45: (None, 'AfterSaleView', 200), 46: ('ReturnShipmentInput', 'AfterSaleView', 200), 47: (None, 'AfterSalePage', 200),
        48: (None, 'AfterSaleView', 200), 49: ('AfterSaleDecision', 'AfterSaleView', 200), 50: (None, 'AfterSalePage', 200),
        51: ('ReturnInspectionInput', 'AfterSaleView', 200), 52: (None, 'RefundPage', 200), 53: (None, 'RefundView', 202),
        54: (None, 'ReconciliationPage', 200), 55: ('ReconciliationStart', 'ReconciliationView', 202), 56: (None, 'ReconciliationIssuePage', 200),
        57: ('IssueResolutionInput', 'ReconciliationIssueView', 200), 58: (None, 'SettlementPage', 200),
        59: ('SettlementInput', 'SettlementView', 201), 60: ('SettlementDecision', 'SettlementView', 200), 61: ('SettlementPayoutInput', 'SettlementView', 200),
        62: (None, 'SettlementPage', 200), 69: ('ReviewVisibilityInput', 'ReviewView', 200), 78: (None, 'AfterSaleOrderSnapshot', 200),
        79: (None, 'SettlementView', 200), 80: (None, 'SettlementView', 200), 81: ('SettlementAdjustmentInput', 'SettlementAdjustmentView', 201),
        82: (None, 'SettlementAdjustmentPage', 200), 83: ('SettlementAdjustmentDecision', 'SettlementAdjustmentView', 200),
        91: (None, 'PageReviewView', 200),
    }
    request_keys = {36, 40, 42, 51, 53, 55, 59, 60, 61}
    for item in paths.values():
        for op in item.values():
            if op['operationId'] not in IMPLEMENTED:
                continue
            n = int(op['operationId'].split('-')[1])
            request, view, status = models[n]
            tag = 'Fulfillment' if n in {36, 38, 39, 40, 41} else ('AfterSale' if n in {*range(42, 52), 78} else ('Review' if n in {37, 69, 91} else ('Payment' if n in {52, 53} else ('Reconciliation' if n in {*range(54, 58)} else 'Settlement'))))
            op.update({'tags': [tag], 'x-implementation-status': 'IMPLEMENTED',
                       'description': '阶段 F 已实现，真实业务约束以 docs/04-接口设计.md 与 docs/02-系统设计文档.md 为准。' + op['description'].split('阶段 A 契约占位。', 1)[-1]})
            op['security'] = [{'bearerAuth': []}]
            name = 'Result' + view
            schemas[name] = obj({**schemas['Result']['properties'], 'data': ref(view)})
            success = {'description': '实际成功响应', 'content': {'application/json': {'schema': ref(name)}}}
            op['responses'] = {str(status): success}
            for error in ['400', '401', '403', '404', '409', '410', '413', '415', '429', '500', '502', '503']:
                op['responses'][error] = {'description': '统一错误；具体业务码和恢复规则见 04', 'content': {'application/json': {'schema': ref('ResultError')}}}
            if request:
                op['requestBody'] = {'required': True, 'content': {'application/json': {'schema': ref(request)}}}
            if n in request_keys:
                op['parameters'].append({'name': 'Idempotency-Key', 'in': 'header', 'required': True,
                                         'schema': {'type': 'string', 'pattern': '^[A-Za-z0-9_-]{20,64}$'}})
            if n in {38, 43, 47, 50, 52, 54, 56, 58, 62, 82, 91}:
                op['parameters'] += [query('page', {'type': 'integer', 'minimum': 1, 'default': 1}),
                                     query('pageSize', {'type': 'integer', 'minimum': 1, 'maximum': 100, 'default': 20})]
            if n in {38}: op['parameters'] += [query('status', schemas['OrderSummary']['properties']['status']), query('orderNo', text(40))]
            if n in {43, 47, 50}: op['parameters'].append(query('status', schemas['AfterSaleView']['properties']['status']))
            if n == 47: op['parameters'].append(query('shopId', ref('Id')))
            if n == 52: op['parameters'] += [query('status', schemas['RefundView']['properties']['status']), query('shopId', ref('Id'))]
            if n == 54: op['parameters'].append(query('billDate', {'type': 'string', 'format': 'date'}))
            if n == 56: op['parameters'].append(query('status', schemas['ReconciliationIssueView']['properties']['status']))
            if n == 58: op['parameters'] += [query('shopId', ref('Id')), query('status', schemas['SettlementView']['properties']['status']), query('periodDate', {'type': 'string', 'format': 'date'})]
            if n == 62: op['parameters'].append(query('status', schemas['SettlementView']['properties']['status']))
            if n == 82: op['parameters'] += [query('status', schemas['SettlementAdjustmentView']['properties']['status']), query('shopId', ref('Id'))]
            if n == 91: op['parameters'] += [query('productId', ref('Id')), query('status', schemas['ReviewView']['properties']['status'])]
