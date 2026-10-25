"""阶段 E 支付协议；实现状态与真实沙箱验收状态分开。"""
from stage_b_contract import obj, ref, text
IMPLEMENTED={f'API-{n}' for n in range(32,36)}

def apply_stage_e(schemas,paths):
    optional_time={'type':['string','null'],'format':'date-time'}
    schemas['PaymentAction']=obj({'type':{'type':'string','enum':['REDIRECT','MOCK']},'url':{'type':['string','null'],'format':'uri','description':'只允许已冻结沙箱网关'},'expiresAt':optional_time})
    schemas['PaymentView']=obj({'id':ref('Id'),'paymentNo':text(40),'orderId':ref('Id'),'provider':{'type':'string','enum':['LOCAL_MOCK','ALIPAY_SANDBOX']},'status':{'type':'string','enum':['INIT','PENDING','SUCCEEDED','CLOSED']},'amount':ref('Money'),'currency':{'type':'string','const':'CNY'},'paidAt':optional_time,'paymentAction':{'oneOf':[ref('PaymentAction'),{'type':'null'}]}})
    schemas['ResultPaymentView']=obj({**schemas['Result']['properties'],'data':ref('PaymentView')})
    for item in paths.values():
        for op in item.values():
            if op['operationId'] not in IMPLEMENTED: continue
            n=int(op['operationId'].split('-')[1]);op.update({'tags':['Payment'],'x-implementation-status':'IMPLEMENTED','security':[] if n==35 else [{'bearerAuth':[]}],'description':'阶段 E 已实现协议与本地模拟；真实支付宝沙箱待环境输入联调。'+('模拟路由仅 dev/test + LOCAL_MOCK + ENABLE_MOCK_PAYMENT=true 注册；其他配置为404。' if n==34 else '')})
            success={'description':'渠道确认前仅表示支付单受理，不表示已支付','content':{'application/json':{'schema':ref('ResultPaymentView')}}}
            op['responses']={'200':success}
            if n==32: op['responses']['201']=success
            if n in (32,34): op['parameters'].append({'name':'Idempotency-Key','in':'header','required':True,'schema':{'type':'string','pattern':'^[A-Za-z0-9_-]{20,64}$'}})
            for error in ['400','401','403','404','409','429','500','502','503']:
                op['responses'][error]={'description':'统一错误；渠道未知时查询原 paymentNo，不能换号重复付款','content':{'application/json':{'schema':ref('ResultError')}}}
            if n==35:
                fields=['app_id','seller_id','notify_id','notify_type','out_trade_no','trade_no','trade_status','total_amount','sign','sign_type']
                form={'type':'object','additionalProperties':{'type':'string'},'required':fields,'properties':{k:{'type':'string'} for k in fields}}
                op['requestBody']={'required':True,'content':{'application/x-www-form-urlencoded':{'schema':form}}}
                op['responses']={'200':{'description':'验签、业务核验及提交后 success，失败 fail；均为纯文本','content':{'text/plain':{'schema':{'type':'string','enum':['success','fail']}}}},'415':{'description':'仅接受渠道表单协议'}}
