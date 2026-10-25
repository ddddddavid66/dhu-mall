"""阶段 C 正式网络契约；保留其他阶段的失败关闭状态。"""
from stage_b_contract import obj, ref, text, query

def array(schema):
    return {'type':'array','items':schema}

IMPLEMENTED = {f'API-{i:02}' for i in [*range(11,26),*range(63,69),90,92]}


def apply_stage_c(schemas, paths):
    status={'type':'string','enum':['ACTIVE','DISABLED']}
    product_status={'type':'string','enum':['DRAFT','ON_SALE','OFF_SHELF']}
    version={'type':'integer','minimum':1,'maximum':4294967295}
    boolean={'type':'boolean'}
    category={'parentId': {'oneOf':[ref('Id'),{'type':'null'}]},'name':text(50),'sortOrder':{'type':'integer','minimum':-2147483648,'maximum':2147483647},'status':status}
    schemas['CategoryInput']=obj(category,required=['name','sortOrder','status'])
    schemas['CategoryView']=obj({'id':ref('Id'),**category,'children':array(ref('CategoryView'))})
    book={'isbn':text(17),'title':text(200),'author':text(200),'publisher':text(120),'publicationDate':{'type':['string','null'],'format':'date'},'categoryId':ref('Id'),'description':text(5000,0),'coverFileId':{'oneOf':[ref('Id'),{'type':'null'}]},'status':status}
    schemas['BookInput']=obj(book,required=[k for k in book if k not in ['publicationDate','coverFileId']])
    schemas['BookView']=obj({'id':ref('Id'),**book,'categoryName':text(50),'coverUrl':{'type':['string','null']}})
    schemas['ProductInput']=obj({'bookId':ref('Id'),'description':text(5000,0),'skuCode':{'type':'string','pattern':'^[A-Za-z0-9_-]{1,64}$'},'salePrice':ref('Money')})
    schemas['ProductEdit']=obj({'description':text(5000,0),'salePrice':ref('Money'),'version':version})
    schemas['ProductStatusInput']=obj({'status':{'type':'string','enum':['ON_SALE','OFF_SHELF']},'version':version})
    schemas['ModerationInput']=obj({'offShelf':boolean,'featured':boolean,'reason':text(300),'version':version})
    schemas['ProductView']=obj({'id':ref('Id'),'shopId':ref('Id'),'shopName':text(80),'book':ref('BookView'),'skuId':ref('Id'),'skuCode':text(64),'salePrice':ref('Money'),'availableQuantity':{'type':'integer','minimum':0},'status':product_status,'featured':boolean,'version':version,'skuVersion':version})
    schemas['InventoryView']=obj({'skuId':ref('Id'),'onHand':{'type':'integer','minimum':0},'reserved':{'type':'integer','minimum':0},'available':{'type':'integer','minimum':0},'version':version})
    schemas['InventoryAdjust']=obj({'delta':{'type':'integer','minimum':-2147483648,'maximum':2147483647,'not':{'const':0}},'reason':text(300),'version':version})
    schemas['FileView']=obj({'id':ref('Id'),'purpose':{'type':'string','enum':['BOOK_COVER','AFTER_SALE_EVIDENCE']},'visibility':{'type':'string','enum':['PUBLIC','PRIVATE']},'mimeType':{'type':'string','const':'image/png'},'byteSize':{'type':'integer','minimum':1,'maximum':5242880},'url':{'type':['string','null']}})
    schemas['FileUpload']=obj({'file':{'type':'string','format':'binary'},'purpose':{'type':'string','enum':['BOOK_COVER','AFTER_SALE_EVIDENCE']}})
    schemas['CartSet']=obj({'quantity':{'type':'integer','minimum':1,'maximum':99},'selected':boolean})
    schemas['CartItemView']=obj({'id':ref('Id'),'skuId':ref('Id'),'productId':ref('Id'),'title':text(200),'coverUrl':{'type':['string','null']},'salePrice':ref('Money'),'quantity':{'type':'integer','minimum':1,'maximum':99},'selected':boolean,'availableQuantity':{'type':'integer','minimum':0},'purchasable':boolean,'unavailableReason':{'type':['string','null']}})
    schemas['CartGroupView']=obj({'shopId':ref('Id'),'shopName':text(80),'items':array(ref('CartItemView'))})
    schemas['CartView']=obj({'groups':array(ref('CartGroupView'))})
    schemas['ReviewView']=obj({'id':ref('Id'),'productId':ref('Id'),'rating':{'type':'integer','minimum':1,'maximum':5},'content':text(1000),'nickname':text(40),'createdAt':{'type':'string','format':'date-time'},'status':{'type':'string','const':'VISIBLE'}})
    for model in ['ProductView','BookView','InventoryView','ReviewView']:
        schemas['Page'+model]=obj({**schemas['Page']['properties'],'items':array(ref(model))})
    models={11:(None,'CategoryTree',200),12:(None,'PageProductView',200),13:(None,'ProductView',200),14:(None,'PageReviewView',200),15:(None,'PageProductView',200),16:('ProductInput','ProductView',201),17:('ProductEdit','ProductView',200),18:('ProductStatusInput','ProductView',200),19:(None,'PageInventoryView',200),20:('InventoryAdjust','InventoryView',201),21:('FileUpload','FileView',201),22:(None,None,200),23:(None,'CartView',200),24:('CartSet','CartView',200),25:(None,'Ack',200),63:(None,'PageBookView',200),64:('BookInput','BookView',201),65:('BookInput','BookView',200),66:('CategoryInput','CategoryView',201),67:('CategoryInput','CategoryView',200),68:('ModerationInput','ProductView',200),90:(None,'PageProductView',200),92:(None,'CategoryTree',200)}
    schemas['CategoryTree']=array(ref('CategoryView'))
    for item in paths.values():
        for operation in item.values():
            if operation['operationId'] not in IMPLEMENTED: continue
            number=int(operation['operationId'].split('-')[1]); request,view,code=models[number]
            operation.update({'tags':['Catalog'],'x-implementation-status':'IMPLEMENTED','description':'阶段 C 已实现。'+operation['description'].split('阶段 A 契约占位。',1)[-1]})
            operation['security']=[] if number in [11,12,13,14,22] else [{'bearerAuth':[]}]
            if number==22:
                operation['description']+=' 私有凭证必须 Bearer + 当前权限 + 本人/关联售后/本店退货关系，无静态下载路径。'
                operation['responses']={'200':{'description':'重新编码的安全图片字节','content':{'image/png':{'schema':{'type':'string','format':'binary'}}}}}
            else:
                name='Result'+view; schemas[name]=obj({**schemas['Result']['properties'],'data':ref(view)})
                operation['responses']={str(code):{'description':'实际成功响应','content':{'application/json':{'schema':ref(name)}}}}
            for error in ['400','401','403','404','409','413','415','429','500','503']:
                operation['responses'][error]={'description':'统一错误，code 按 04；429 含 Retry-After','content':{'application/json':{'schema':ref('ResultError')}}}
            if request:
                operation['requestBody']={'required':True,'content':{'multipart/form-data' if number==21 else 'application/json':{'schema':ref(request)}}}
            if number in [12,14,15,19,63,90]: operation['parameters'] += [query('page',{'type':'integer','minimum':1,'default':1}),query('pageSize',{'type':'integer','minimum':1,'maximum':100,'default':20})]
            if number in [12,15,63,90]: operation['parameters'].append(query('keyword',text(100,0)))
            if number==12: operation['parameters'] += [query('categoryId',ref('Id')),query('shopId',ref('Id')),query('featured',boolean),query('sort',{'type':'string','enum':['NEWEST','PRICE_ASC','PRICE_DESC']})]
            if number in [15,90]: operation['parameters'].append(query('status',product_status))
            if number==90: operation['parameters'].append(query('shopId',ref('Id')))
            if number==19: operation['parameters'].append(query('skuId',ref('Id')))
            if number==92: operation['parameters'].append(query('status',status))
            if number==20: operation['parameters'].append({'name':'Idempotency-Key','in':'header','required':True,'schema':{'type':'string','pattern':'^[A-Za-z0-9_-]{20,64}$'}})
