"""阶段 B 已实现的严格 schema 与操作覆盖；其余操作继续保持失败关闭。"""

IMPLEMENTED = {f'API-{n:02}' for n in [*range(1, 11), *range(70, 78), *range(84, 89)]}


def ref(name):
    return {'$ref': '#/components/schemas/' + name}


def array(item):
    return {'type': 'array', 'items': ref(item)}


def obj(properties, required=None):
    return {'type': 'object', 'additionalProperties': False, 'properties': properties,
            'required': list(properties) if required is None else required}


def text(maximum, minimum=1):
    return {'type': 'string', 'minLength': minimum, 'maxLength': maximum}


def apply_stage_b(schemas, paths):
    status = {'type': 'string', 'enum': ['ACTIVE', 'DISABLED']}
    shop_status = {'type': 'string', 'enum': ['ACTIVE', 'SUSPENDED']}
    password = {**text(72), 'writeOnly': True, 'description': 'UTF-8 长度 12—72 字节，服务端按字节验证'}
    username = {'type': 'string', 'pattern': '^[a-z0-9_]{4,32}$'}
    ids = {**array('Id'), 'maxItems': 100, 'uniqueItems': True}
    schemas.update({
        'RegisterInput': obj({'username': username, 'password': password, 'nickname': text(40)}),
        'LoginInput': obj({'username': text(32), 'password': {**text(72), 'writeOnly': True}}),
        'PasswordInput': obj({'oldPassword': {**text(72), 'writeOnly': True}, 'newPassword': password}),
        'UserCreate': obj({'username': username, 'nickname': text(40), 'initialPassword': password, 'roleIds': ids}),
        'UserEdit': obj({'nickname': text(40), 'status': status, 'roleIds': ids}),
        'ShopScope': obj({'id': ref('Id'), 'name': text(80), 'duties': {'type': 'array', 'items': {'type': 'string', 'enum': ['MERCHANT', 'WAREHOUSE']}}}),
        'UserView': obj({'id': ref('Id'), 'username': text(32), 'nickname': text(40), 'status': status,
                         'roles': {'type': 'array', 'items': {'type': 'string'}},
                         'permissions': {'type': 'array', 'items': {'type': 'string'}}, 'shops': array('ShopScope')}),
        'AuthView': obj({'accessToken': text(4096), 'expiresIn': {'type': 'integer', 'const': 900},
                         'csrfToken': {'type': 'string', 'pattern': '^[A-Za-z0-9_-]{43}$'}, 'user': ref('UserView')}),
        'Ack': obj({'success': {'type': 'boolean', 'const': True}}),
        'AddressInput': obj({'receiverName': text(40), 'phone': text(32), 'province': text(40),
                             'city': text(40), 'district': text(40), 'detail': text(200), 'isDefault': {'type': 'boolean'}}),
        'RoleCreate': obj({'code': {'type': 'string', 'pattern': '^[A-Z][A-Z0-9_]{2,31}$'}, 'name': text(50), 'permissionIds': ids}),
        'RoleEdit': obj({'name': text(50), 'status': status, 'permissionIds': ids}),
        'RoleView': obj({'id': ref('Id'), 'code': text(32), 'name': text(50), 'status': status, 'permissionIds': array('Id')}),
        'PermissionView': obj({'id': ref('Id'), 'code': text(80), 'name': text(80)}),
        'ShopInput': obj({'name': text(80), 'contactName': text(40), 'contactPhone': text(32), 'status': shop_status}),
        'MemberInput': obj({'status': status}),
        'MemberView': obj({'id': ref('Id'), 'shopId': ref('Id'), 'userId': ref('Id'), 'username': text(32),
                           'duty': {'type': 'string', 'enum': ['MERCHANT', 'WAREHOUSE']}, 'status': status}),
        'AuditView': obj({'id': ref('Id'), 'actorId': {'anyOf': [ref('Id'), {'type': 'null'}]}, 'action': text(80),
                          'resourceType': text(40), 'resourceId': {'type': ['string', 'null']},
                          'result': {'type': 'string', 'enum': ['SUCCESS', 'DENIED', 'FAILED']},
                          'summary': {'type': 'object'}, 'traceId': text(64, 8), 'createdAt': {'type': 'string', 'format': 'date-time'}}),
    })
    schemas['AddressView'] = obj({'id': ref('Id'), **schemas['AddressInput']['properties']})
    schemas['ShopView'] = obj({'id': ref('Id'), **schemas['ShopInput']['properties'], 'createdAt': {'type': 'string', 'format': 'date-time'}})
    models = {
        1: ('RegisterInput', 'UserView', 201), 2: ('LoginInput', 'AuthView', 200), 3: (None, 'AuthView', 200),
        4: (None, 'Ack', 200), 5: (None, 'UserView', 200), 6: ('PasswordInput', 'Ack', 200),
        7: (None, 'Addresses', 200), 8: ('AddressInput', 'AddressView', 201), 9: ('AddressInput', 'AddressView', 200),
        10: (None, 'Ack', 200), 70: (None, 'UserPage', 200), 71: ('UserCreate', 'UserView', 201),
        72: ('UserEdit', 'UserView', 200), 73: (None, 'RolePage', 200), 74: ('RoleCreate', 'RoleView', 201),
        75: ('RoleEdit', 'RoleView', 200), 76: (None, 'Permissions', 200), 77: (None, 'AuditPage', 200),
        84: (None, 'ShopPage', 200), 85: ('ShopInput', 'ShopView', 201), 86: ('ShopInput', 'ShopView', 200),
        87: (None, 'MemberPage', 200), 88: ('MemberInput', 'MemberView', 200)}
    schemas['Addresses'] = array('AddressView')
    schemas['Permissions'] = array('PermissionView')
    for name, view in [('UserPage','UserView'), ('RolePage','RoleView'), ('ShopPage','ShopView'), ('MemberPage','MemberView'), ('AuditPage','AuditView')]:
        schemas[name] = obj({**schemas['Page']['properties'], 'items': array(view)})
    for item in paths.values():
        for operation in item.values():
            operation_id = operation['operationId']
            if operation_id not in IMPLEMENTED:
                continue
            number = int(operation_id.split('-')[1])
            request, view, success_status = models[number]
            result_name = 'Result' + view
            schemas[result_name] = obj({**schemas['Result']['properties'], 'data': ref(view)})
            operation['tags'] = ['Identity']
            operation['x-implementation-status'] = 'IMPLEMENTED'
            operation['description'] = '阶段 B 已实现。' + operation['description'].split('阶段 A 契约占位。', 1)[-1]
            operation['security'] = [] if number in [1,2] else ([{'refreshCookie': [], 'csrfToken': []}] if number in [3,4] else [{'bearerAuth': []}])
            operation['responses'] = {str(success_status): {'description': '实际成功响应', 'content': {'application/json': {'schema': ref(result_name)}}}}
            for code in ['400','401','403','404','409','429','503','500']:
                operation['responses'][code] = {'description': '统一错误，具体业务码见设计', 'content': {'application/json': {'schema': ref('ResultError')}}}
            if number == 88:
                operation['responses']['201'] = operation['responses']['200']
            if number in [2,3,4]:
                operation['responses'][str(success_status)]['headers'] = {'Set-Cookie': {'description': 'refreshToken，仅 HttpOnly Cookie；Secure 随环境，SameSite=Lax，Path=/api/v1/auth', 'schema': {'type': 'string'}}}
            if request:
                operation['requestBody'] = {'required': True, 'content': {'application/json': {'schema': ref(request)}}}
            if number in [3,4]:
                operation['parameters'].append({'name': 'Origin', 'in': 'header', 'required': True, 'schema': {'type': 'string', 'format': 'uri'}})
            if number in [70,73,77,84,87]:
                operation['parameters'] += [query('page', {'type': 'integer', 'minimum': 1, 'default': 1}), query('pageSize', {'type': 'integer', 'minimum': 1, 'maximum': 100, 'default': 20})]
            if number == 70:
                operation['parameters'] += [query('keyword',text(100,0)), query('status',status)]
            if number == 84:
                operation['parameters'].append(query('status',shop_status))
            if number == 77:
                operation['parameters'] += [query('actorId',ref('Id')),query('action',text(80)),query('traceId',text(64)),query('from',{'type':'string','format':'date-time'}),query('to',{'type':'string','format':'date-time'})]


def query(name, schema):
    return {'name': name, 'in': 'query', 'required': False, 'schema': schema}
