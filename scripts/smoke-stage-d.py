"""本机 dev 联调：验证通过后，经正式 API 创建独立测试会员及真实未付款订单。"""
from pathlib import Path
from datetime import datetime,timezone
from decimal import Decimal
from urllib.request import Request,urlopen
from urllib.error import HTTPError
import argparse,json,secrets,uuid,zipfile

ROOT=Path(__file__).resolve().parents[1]

def main():
    parser=argparse.ArgumentParser();parser.add_argument('--backend',default='http://127.0.0.1:18080');parser.add_argument('--confirm-dev',action='store_true');args=parser.parse_args()
    assert args.confirm_dev and args.backend.startswith(('http://127.0.0.1:','http://localhost:'))
    checks=json.loads((ROOT/'.artifacts/verification-results.json').read_text(encoding='utf-8-sig'));assert len(checks)==7 and all(c['exitCode']==0 for c in checks)
    contract=(ROOT/'contracts/openapi.json').read_bytes();assert json.loads(contract)['info']['version']=='0.4.0'
    with zipfile.ZipFile(ROOT/'backend/target/yueji-mall-0.1.0-SNAPSHOT.jar') as jar:assert jar.read('BOOT-INF/classes/contracts/openapi.json')==contract
    checks=[];account_path=ROOT/'.artifacts/D-demo-account.json';manifest_path=ROOT/'.artifacts/D-demo-data.json'
    def call(name,method,path,status=200,body=None,token=None,key=None,code='OK',session=None):
        headers={'Content-Type':'application/json','X-Request-Id':'stage-d-smoke-123'}
        if token:headers['Authorization']='Bearer '+token
        if key:headers['Idempotency-Key']=key
        if session:headers.update({'Cookie':session[0],'X-CSRF-Token':session[1],'Origin':'http://localhost:5173'})
        request=Request(args.backend+'/api/v1'+path,data=json.dumps(body).encode() if body is not None else None,headers=headers,method=method)
        try:response=urlopen(request,timeout=20)
        except HTTPError as error:response=error
        with response:
            value=json.loads(response.read());assert response.code==status and value['code']==code,(name,response.code,value['code'])
            assert value['traceId']==response.headers['X-Request-Id'];cookie=response.headers.get('Set-Cookie','').split(';',1)[0]
        checks.append({'name':name,'actualStatus':status,'actualCode':code,'passed':True});return value['data'],cookie
    if account_path.exists():
        account=json.loads(account_path.read_text(encoding='utf-8'))
        assert manifest_path.exists() and json.loads(manifest_path.read_text(encoding='utf-8'))['status']=='READY','上次未完成，检查后人工恢复，不重复造订单'
        fresh=False
    else:
        account={'username':'d_demo_'+secrets.token_hex(5),'password':secrets.token_urlsafe(24)}
        account_path.write_text(json.dumps(account,indent=2),encoding='utf-8');fresh=True
        manifest_path.write_text(json.dumps({'status':'IN_PROGRESS','username':account['username']}),encoding='utf-8')
        call('register-independent-member','POST','/auth/register',201,{'username':account['username'],'password':account['password'],'nickname':'阶段D虚构联调会员'})
    auth,cookie=call('login-member','POST','/auth/login',body=account);token=auth['accessToken']
    try:
        if fresh:
            products,_=call('select-real-purchasable-products','GET','/products?pageSize=100')
            selected={}
            for p in products['items']:
                if p['availableQuantity']>=20 and p['shopId'] not in selected:selected[p['shopId']]=p
                if len(selected)==2:break
            assert len(selected)==2
            address,_=call('create-fictional-address','POST','/addresses',201,{'receiverName':'虚构读者丁','phone':'13800000009','province':'虚构省','city':'虚构市','district':'虚构区','detail':'阶段D虚构联调地址','isDefault':True},token)
            cart=None
            for p in selected.values():cart,_=call('set-cart-'+p['skuId'],'PUT','/cart/items/'+p['skuId'],body={'quantity':1,'selected':True},token=token)
            items=[i['id'] for g in cart['groups'] for i in g['items']]
            preview,_=call('preview-two-shops','POST','/checkout/previews',201,{'addressId':address['id'],'cartItemIds':items},token)
            key=str(uuid.uuid4());batch,_=call('create-atomic-batch','POST','/checkout/orders',201,{'previewId':preview['previewId']},token,key)
            repeat,_=call('replay-lost-response','POST','/checkout/orders',201,{'previewId':preview['previewId']},token,key);assert repeat==batch
            call('reject-used-preview-new-key','POST','/checkout/orders',409,{'previewId':preview['previewId']},token,str(uuid.uuid4()),'STATE_CONFLICT')
            call('reject-same-key-other-preview','POST','/checkout/orders',409,{'previewId':'999999999'},token,key,'IDEMPOTENCY_CONFLICT')
            closing,_=call('cancel-accepted-not-final','POST','/orders/'+batch['orders'][0]['id']+'/cancel',202,{'reason':'阶段D联调取消意图，等待E渠道核实'},token,str(uuid.uuid4()))
            assert closing['status']=='CLOSING' and closing['closedAt'] is None
            manifest={'status':'READY','username':account['username'],'batchId':batch['batchId'],'batchNo':batch['batchNo'],'previewId':preview['previewId'],'totalAmount':batch['totalAmount'],'orders':batch['orders'],'createdAtUtc':datetime.now(timezone.utc).isoformat()}
            manifest_path.write_text(json.dumps(manifest,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
        else:manifest=json.loads(manifest_path.read_text(encoding='utf-8'))
        recovered,_=call('recover-owned-batch','GET','/checkout/batches/'+manifest['batchId'],token=token)
        assert len(recovered['orders'])==2 and sum(Decimal(o['payableAmount']) for o in recovered['orders'])==Decimal(recovered['totalAmount'])
        manifest['orders']=recovered['orders'];manifest['lastVerifiedAtUtc']=datetime.now(timezone.utc).isoformat()
        manifest_path.write_text(json.dumps(manifest,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
        orders,_=call('list-owned-orders','GET','/orders?pageSize=100',token=token);assert orders['total']==2
        first,_=call('closing-order-has-snapshot-and-log','GET','/orders/'+manifest['orders'][0]['id'],token=token)
        assert first['status']=='CLOSING' and first['financialStatus']=='UNPAID' and first['closedAt'] is None
        assert first['receiver']['name']=='虚构读者丁' and len(first['timeline'])==2 and first['timeline'][1]['action']=='CANCEL_REQUEST'
        second,_=call('other-shop-remains-independent','GET','/orders/'+manifest['orders'][1]['id'],token=token)
        # 30 分钟后真实扫描也可转 CLOSING；此脚本不把超时当已关单。
        assert second['status'] in ['PENDING_PAYMENT','CLOSING'] and second['financialStatus']=='UNPAID'
        call('payment-remains-unimplemented','POST','/orders/'+first['id']+'/payments',501,{},token,str(uuid.uuid4()),'NOT_IMPLEMENTED')
        call('batch-anonymous-denied','GET','/checkout/batches/'+manifest['batchId'],401,code='AUTH_REQUIRED')
    finally:call('logout','POST','/auth/logout',body={},session=(cookie,auth['csrfToken']))
    report={'stage':'D','verifiedAtUtc':datetime.now(timezone.utc).isoformat(),'freshDataCreated':fresh,'total':len(checks),'checks':checks}
    (ROOT/'.artifacts/D-api-smoke.json').write_text(json.dumps(report,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
    print(f'Stage D smoke: {len(checks)} checks passed; fresh data: {fresh}')

if __name__=='__main__':main()
