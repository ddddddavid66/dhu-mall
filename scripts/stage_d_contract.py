"""阶段 D：交易契约，保持原设计网络字段和独立店铺订单。"""
from stage_b_contract import obj, ref, text, query
from stage_c_contract import array

IMPLEMENTED={f'API-{i:02}' for i in range(26,32)}

def apply_stage_d(schemas,paths):
    instant={'type':'string','format':'date-time'}
    optional_time={'type':['string','null'],'format':'date-time'}
    status={'type':'string','enum':['PENDING_PAYMENT','CLOSING','PAID','SHIPPED','COMPLETED','CANCELLED','CLOSED_REFUND']}
    financial={'type':'string','enum':['UNPAID','PAID','PARTIALLY_REFUNDED','REFUNDED']}
    schemas['PreviewInput']=obj({'addressId':ref('Id'),'cartItemIds':{**array(ref('Id')),'minItems':1,'maxItems':50,'uniqueItems':True}})
    schemas['PreviewItem']=obj({'skuId':ref('Id'),'title':text(200),'quantity':{'type':'integer','minimum':1,'maximum':99},'unitPrice':ref('Money'),'lineAmount':ref('Money')})
    schemas['PreviewShop']=obj({'shopId':ref('Id'),'shopName':text(80),'items':array(ref('PreviewItem')),'goodsAmount':ref('Money'),'freightAmount':ref('Money'),'payableAmount':ref('Money')})
    schemas['PreviewView']=obj({'previewId':ref('Id'),'expiresAt':instant,'address':ref('AddressView'),'shops':array(ref('PreviewShop')),'totalAmount':ref('Money')})
    schemas['CreateOrderInput']=obj({'previewId':ref('Id')})
    summary={'id':ref('Id'),'orderNo':text(40),'shopId':ref('Id'),'shopName':text(80),'status':status,'financialStatus':financial,'payableAmount':ref('Money'),'refundedAmount':ref('Money'),'expiresAt':instant,'createdAt':instant,'itemCount':{'type':'integer','minimum':1}}
    schemas['OrderSummary']=obj(summary)
    schemas['BatchView']=obj({'batchId':ref('Id'),'batchNo':text(40),'totalAmount':ref('Money'),'orders':array(ref('OrderSummary'))})
    schemas['OrderItemView']=obj({'id':ref('Id'),'skuId':ref('Id'),'bookTitle':text(200),'isbn':text(13),'unitPrice':ref('Money'),'quantity':{'type':'integer','minimum':1,'maximum':99},'lineAmount':ref('Money'),'refundedQuantity':{'type':'integer','minimum':0},'refundedAmount':ref('Money')})
    schemas['OrderReceiver']=obj({'name':text(40),'phone':text(32),'address':text(300)})
    schemas['OrderTimeline']=obj({'status':status,'action':text(50),'occurredAt':instant,'reason':text(300)})
    schemas['OrderView']=obj({**summary,'items':array(ref('OrderItemView')),'receiver':ref('OrderReceiver'),'paidAt':optional_time,'shippedAt':optional_time,'completedAt':optional_time,'closedAt':optional_time,'shipment':{'type':'null','description':'阶段 D 未实现发货；不制造物流记录'},'timeline':array(ref('OrderTimeline')),'version':{'type':'integer','minimum':1}})
    schemas['CancelInput']=obj({'reason':text(300)})
    schemas['PageOrderSummary']=obj({**schemas['Page']['properties'],'items':array(ref('OrderSummary'))})
    models={26:('PreviewInput','PreviewView',201),27:('CreateOrderInput','BatchView',201),28:(None,'BatchView',200),29:(None,'PageOrderSummary',200),30:(None,'OrderView',200),31:('CancelInput','OrderView',202)}
    for item in paths.values():
        for op in item.values():
            if op['operationId'] not in IMPLEMENTED: continue
            n=int(op['operationId'].split('-')[1]); request,view,code=models[n]
            op.update({'tags':['Trade'],'x-implementation-status':'IMPLEMENTED','security':[{'bearerAuth':[]}],'description':'阶段 D 已实现；取消受理后保持 CLOSING 和预占，渠道确认由阶段 E 实现。'+op['description']})
            name='Result'+view;schemas[name]=obj({**schemas['Result']['properties'],'data':ref(view)})
            success={'description':'实际响应；原键重放返回已持久化业务快照','content':{'application/json':{'schema':ref(name)}}}
            op['responses']={str(code):success}
            if n==31: op['responses']['200']=success
            for error in ['400','401','403','404','409','410','429','500','503']:
                op['responses'][error]={'description':'统一错误；过期重新预览，冲突重新确认，网络未知重放原键并查询批次/订单','content':{'application/json':{'schema':ref('ResultError')}}}
            if request: op['requestBody']={'required':True,'content':{'application/json':{'schema':ref(request)}}}
            if n in [27,31]: op['parameters'].append({'name':'Idempotency-Key','in':'header','required':True,'schema':{'type':'string','pattern':'^[A-Za-z0-9_-]{20,64}$'}})
            if n==29: op['parameters'] += [query('status',status),query('orderNo',text(40)),query('page',{'type':'integer','minimum':1,'default':1}),query('pageSize',{'type':'integer','minimum':1,'maximum':100,'default':20})]
