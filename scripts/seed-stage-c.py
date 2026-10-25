"""在契约与真实集成验证通过后，经正式 API 准备隔离 dev 联调数据；不直接写业务表。"""
from pathlib import Path
from urllib.request import Request, urlopen
from urllib.error import HTTPError
from urllib.parse import urlsplit
from decimal import Decimal
from datetime import datetime, timezone
import argparse
import json
import struct
import time
import uuid
import xml.etree.ElementTree as ET
import zipfile
import zlib

ROOT=Path(__file__).resolve().parents[1]
MANIFEST=ROOT/'.artifacts/C-demo-data.json'


def require_verified_build():
    checks=json.loads((ROOT/'.artifacts/verification-results.json').read_text(encoding='utf-8-sig'))
    assert len(checks)==7 and all(c['exitCode']==0 for c in checks),'先执行 scripts/verify.ps1'
    suites=list((ROOT/'backend/target/surefire-reports').glob('TEST-*.xml'))
    assert any('StageCHttpTests' in p.name for p in suites)
    for path in suites:
        suite=ET.parse(path).getroot()
        assert all(int(suite.get(key,'0'))==0 for key in ['failures','errors','skipped'])
    report=ET.parse(ROOT/'backend/target/site/jacoco/jacoco.xml').getroot()
    counters=[c for p in report.findall('package') if p.get('name').endswith('/service') for c in p.findall('counter') if c.get('type')=='BRANCH']
    covered=sum(int(c.get('covered')) for c in counters);missed=sum(int(c.get('missed')) for c in counters)
    assert covered/(covered+missed)>=.80,'核心覆盖率门禁未通过'
    contract=(ROOT/'contracts/openapi.json').read_bytes()
    assert json.loads(contract)['info']['version'] in ['0.3.0','0.4.0','0.5.0']
    with zipfile.ZipFile(ROOT/'backend/target/yueji-mall-0.1.0-SNAPSHOT.jar') as jar:
        assert jar.read('BOOT-INF/classes/contracts/openapi.json')==contract,'必须构建当前契约的 JAR'


class Client:
    def __init__(self,base,origin):
        self.base=base;self.origin=origin;self.sessions={};self.windows={};self.calls=0

    def request(self,method,path,body=None,user=None,key=None,binary=None):
        headers={'Content-Type':'application/json','X-Request-Id':'stage-c-dev-seed'}
        if user:
            headers['Authorization']='Bearer '+self.sessions[user]['accessToken']
            if method!='GET':
                minute=int(time.time()//60);window,count=self.windows.get(user,(minute,0))
                count=count if window==minute else 0
                if count>=30:
                    print('Waiting for the next write quota window; server limits stay unchanged.',flush=True)
                    time.sleep(61-time.time()%60);minute=int(time.time()//60);count=0
                self.windows[user]=(minute,count+1)
        if key: headers['Idempotency-Key']=key
        if binary:
            boundary='stage-c-'+uuid.uuid4().hex;headers['Content-Type']='multipart/form-data; boundary='+boundary
            payload=(f'--{boundary}\r\nContent-Disposition: form-data; name="purpose"\r\n\r\nBOOK_COVER\r\n--{boundary}\r\nContent-Disposition: form-data; name="file"; filename="demo-cover.png"\r\nContent-Type: image/png\r\n\r\n'.encode()+binary+f'\r\n--{boundary}--\r\n'.encode())
        else: payload=json.dumps(body,ensure_ascii=False).encode() if body is not None else None
        request=Request(self.base+'/api/v1'+path,data=payload,headers=headers,method=method)
        try:
            response=urlopen(request,timeout=30)
        except HTTPError as error:
            raise RuntimeError(f'{method} {path}: HTTP {error.code}；停止生成，未自动重试写请求') from None
        with response:
            value=json.loads(response.read());assert value['code']=='OK'
            if path=='/auth/login':
                value['data']['cookie']=response.headers['Set-Cookie'].split(';',1)[0]
            self.calls+=1
            return value['data']

    def login(self,user,password):
        self.sessions[user]=self.request('POST','/auth/login',{'username':user,'password':password})
        return self.sessions[user]

    def logout_all(self):
        for session in self.sessions.values():
            headers={'Cookie':session['cookie'],'Origin':self.origin,'X-CSRF-Token':session['csrfToken']}
            with urlopen(Request(self.base+'/api/v1/auth/logout',headers=headers,method='POST'),timeout=15) as response:
                assert response.code==200


def png_placeholder():
    def chunk(name,value):
        return struct.pack('!I',len(value))+name+value+struct.pack('!I',zlib.crc32(name+value)&0xffffffff)
    pixels=b''.join(b'\0'+bytes((51,84,94))*64 for _ in range(96))
    return b'\x89PNG\r\n\x1a\n'+chunk(b'IHDR',struct.pack('!2I5B',64,96,8,2,0,0,0))+chunk(b'IDAT',zlib.compress(pixels))+chunk(b'IEND',b'')


def isbn(index):
    prefix='978000000'+f'{index:03}'
    return prefix+str((-sum(int(v)*(1 if i%2==0 else 3) for i,v in enumerate(prefix)))%10)


def build_data(client):
    ops='operator_a';manifest={'status':'IN_PROGRESS','createdAtUtc':datetime.now(timezone.utc).isoformat(),'categories':[],'books':[],'products':[]}
    MANIFEST.write_text(json.dumps(manifest,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
    categories=[]
    for i in range(5):
        parent=client.request('POST','/ops/categories',{'name':f'虚构分类 {i+1}','sortOrder':i,'status':'ACTIVE'},ops)
        child=client.request('POST','/ops/categories',{'name':f'演示子分类 {i+1}','sortOrder':i,'status':'ACTIVE','parentId':parent['id']},ops)
        categories.append(child['id']);manifest['categories'] += [parent['id'],child['id']]
    cover=client.request('POST','/files',user=ops,binary=png_placeholder())
    for i in range(20):
        book=client.request('POST','/ops/books',{'isbn':isbn(i),'title':f'虚构演示图书 {i+1:02}','author':f'虚构作者 {i%5+1}','publisher':'虚构教学出版社','categoryId':categories[i%5],'description':'仅用于阅集开发联调的虚构图书资料。','coverFileId':cover['id'],'status':'ACTIVE'},ops)
        manifest['books'].append(book['id'])
    for store,user in enumerate(['merchant_a','merchant_b','merchant_c']):
        shop=client.sessions[user]['user']['shops'][0]['id']
        indices=[*range(10)] if store==0 else [*range(5),*range(10,15)] if store==1 else [*range(5,10),*range(15,20)]
        for index,book_index in enumerate(indices):
            product=client.request('POST',f'/merchant/shops/{shop}/products',{'bookId':manifest['books'][book_index],'description':'开发联调实体新书','skuCode':f'DEMO-C-{store}-{index}','salePrice':str(Decimal('19.90')+store*5+index)},user)
            quantity=0 if index==2 else 10 if index==3 else 100
            if quantity:
                client.request('POST',f'/merchant/shops/{shop}/inventory/{product["skuId"]}/adjustments',{'delta':quantity,'reason':'阶段 C 联调初始化','version':1},user,key=str(uuid.uuid4()))
            if index!=0:
                product=client.request('PUT',f'/merchant/shops/{shop}/products/{product["id"]}/status',{'status':'OFF_SHELF' if index==1 else 'ON_SALE','version':1},user)
            manifest['products'].append({'id':product['id'],'skuId':product['skuId'],'shopId':shop,'status':product['status'],'initialStock':quantity})
    for index in [4,5,14]:
        client.request('PUT',f'/cart/items/{manifest["products"][index]["skuId"]}',{'quantity':2,'selected':True},'reader_a')
    first=manifest['products'][4];client.request('POST',f'/merchant/shops/{first["shopId"]}/inventory/{first["skuId"]}/adjustments',{'delta':-100,'reason':'演示购物车加入后缺货','version':2},'merchant_a',key=str(uuid.uuid4()))
    second=manifest['products'][5];client.request('PUT',f'/merchant/shops/{second["shopId"]}/products/{second["id"]}/status',{'status':'OFF_SHELF','version':2},'merchant_a')
    featured=manifest['products'][6]
    client.request('PUT',f'/ops/products/{featured["id"]}/moderation',{'offShelf':False,'featured':True,'reason':'阶段 C 人工精选联调','version':2},ops)
    first['initialStock']=0;second['status']='OFF_SHELF'
    manifest.update({'status':'READY','featuredProductId':featured['id'],'coverFileId':cover['id'],'counts':{'categories':10,'books':20,'products':30,'shops':3,'cartItemsForReaderA':3},'scenarios':['同 ISBN 多店报价','人工精选','草稿','下架','缺货','最后 10 本','购物车加入后缺货/下架','跨店分组'],'createdOrders':0,'createdAfterSales':0,'createdReviews':0})
    MANIFEST.write_text(json.dumps(manifest,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
    return manifest


def main():
    parser=argparse.ArgumentParser();parser.add_argument('--backend',default='http://127.0.0.1:18080');parser.add_argument('--origin',default='http://localhost:5173');parser.add_argument('--confirm-dev',action='store_true');args=parser.parse_args()
    url=urlsplit(args.backend)
    assert args.confirm_dev and url.scheme=='http' and url.hostname in ['localhost','127.0.0.1'] and not url.username and not url.path,'仅允许显式确认的本机 dev 实例'
    require_verified_build()
    credentials=json.loads((ROOT/'.artifacts/B-dev-accounts.json').read_text(encoding='utf-8'))
    client=Client(args.backend,args.origin)
    try:
        for user in ['operator_a','merchant_a','merchant_b','merchant_c','reader_a']:client.login(user,credentials[user]['password'])
        if MANIFEST.exists():
            manifest=json.loads(MANIFEST.read_text(encoding='utf-8'));assert manifest['status']=='READY','存在未完成联调清单，停止以免重复造数据'
        else:
            assert client.request('GET','/ops/books',user='operator_a')['total']==0,'已有公共书目，拒绝覆盖或混入演示种子'
            assert client.request('GET','/ops/categories',user='operator_a')==[],'已有分类，拒绝覆盖'
            for user in ['merchant_a','merchant_b','merchant_c']:
                shop=client.sessions[user]['user']['shops'][0]['id']
                assert client.request('GET',f'/merchant/shops/{shop}/products',user=user)['total']==0
            manifest=build_data(client)
        assert client.request('GET','/ops/books',user='operator_a')['total']==20
        assert client.request('GET','/ops/products',user='operator_a')['total']==30
        assert client.request('GET','/products?featured=true')['items'][0]['id']==manifest['featuredProductId']
        cart=client.request('GET','/cart',user='reader_a');items=[item for group in cart['groups'] for item in group['items']]
        assert len(items)==3 and sum(item['purchasable'] for item in items)==1
        print('Stage C data verified: 10 categories, 20 books, 30 SKUs, 3 shops, 3 cart items; no orders/after-sales/reviews.')
    finally:client.logout_all()


if __name__=='__main__':main()
