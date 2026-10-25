"""读取真实 C 联调数据，核对公开过滤、购物车和本店隔离；不改写联调业务数据。"""
from pathlib import Path
from urllib.request import Request,urlopen
from urllib.error import HTTPError
from datetime import datetime,timezone
from decimal import Decimal
import argparse
import json

ROOT=Path(__file__).resolve().parents[1]


def main():
    parser=argparse.ArgumentParser();parser.add_argument('--backend',default='http://127.0.0.1:18080');args=parser.parse_args()
    assert args.backend.startswith(('http://127.0.0.1:','http://localhost:'))
    manifest=json.loads((ROOT/'.artifacts/C-demo-data.json').read_text(encoding='utf-8'));assert manifest['status']=='READY'
    credentials=json.loads((ROOT/'.artifacts/B-dev-accounts.json').read_text(encoding='utf-8'))
    checks=[];sessions=[]

    def call(name,method,path,status=200,code='OK',body=None,token=None,extra=None,binary=False):
        headers={'Content-Type':'application/json','X-Request-Id':'stage-c-smoke-123'}
        if token:headers['Authorization']='Bearer '+token
        if extra:headers.update(extra)
        request=Request(args.backend+'/api/v1'+path,data=json.dumps(body).encode() if body is not None else None,headers=headers,method=method)
        try:response=urlopen(request,timeout=20)
        except HTTPError as error:response=error
        with response:
            raw=response.read();assert response.code==status,(name,response.code)
            if binary:
                assert response.headers.get_content_type()=='image/png' and raw.startswith(b'\x89PNG\r\n\x1a\n')
                value=raw
            else:
                value=json.loads(raw);assert value['code']==code,(name,value['code']);value=value.get('data')
            cookie=response.headers.get('Set-Cookie','').split(';',1)[0]
        checks.append({'name':name,'actualStatus':status,'actualCode':None if binary else code,'passed':True})
        return value,cookie

    def login(user):
        session,cookie=call('login-'+user,'POST','/auth/login',body={'username':user,'password':credentials[user]['password']})
        sessions.append((cookie,session['csrfToken']));return session

    try:
        reader=login('reader_a');merchant=login('merchant_b')
        categories,_=call('public-category-tree','GET','/categories');assert len(categories)==5 and sum(len(c['children']) for c in categories)==5
        products,_=call('public-products-database-filter','GET','/products?pageSize=100')
        expected={p['id'] for p in manifest['products'] if p['status']=='ON_SALE'}
        assert {p['id'] for p in products['items']}==expected and products['total']==len(expected)
        featured,_=call('curated-featured-filter','GET','/products?featured=true');assert featured['total']==1 and featured['items'][0]['id']==manifest['featuredProductId']
        ordered,_=call('price-sort-and-money-types','GET','/products?pageSize=100&sort=PRICE_ASC')
        prices=[Decimal(p['salePrice']) for p in ordered['items']];assert prices==sorted(prices)
        assert all(isinstance(p['id'],str) and isinstance(p['salePrice'],str) for p in ordered['items'])
        reviews,_=call('real-review-table-empty','GET','/products/'+next(iter(expected))+'/reviews');assert reviews['items']==[] and reviews['total']==0
        cart,_=call('own-cart-real-invalid-items','GET','/cart',token=reader['accessToken'])
        items=[i for g in cart['groups'] for i in g['items']];assert len(cart['groups'])==2 and len(items)==3
        assert sorted(i['unavailableReason'] for i in items if not i['purchasable'])==['OUT_OF_STOCK','PRODUCT_UNAVAILABLE']
        own=merchant['user']['shops'][0]['id'];stocks,_=call('own-shop-inventory','GET',f'/merchant/shops/{own}/inventory',token=merchant['accessToken']);assert stocks['total']==10
        other=manifest['products'][0]['shopId'];call('cross-shop-denied','GET',f'/merchant/shops/{other}/products',404,'RESOURCE_NOT_FOUND',token=merchant['accessToken'])
        call('member-ops-denied','GET','/ops/books',403,'ACCESS_DENIED',token=reader['accessToken'])
        call('anonymous-cart-denied','GET','/cart',401,'AUTH_REQUIRED')
        for state in ['DRAFT','OFF_SHELF']:
            id=next(p['id'] for p in manifest['products'] if p['status']==state)
            call('public-'+state.lower()+'-hidden','GET','/products/'+id,404,'RESOURCE_NOT_FOUND')
        call('public-reencoded-cover','GET',f'/files/{manifest["coverFileId"]}/content',binary=True)
    finally:
        for index,(cookie,csrf) in enumerate(sessions):call('logout-'+str(index),'POST','/auth/logout',extra={'Cookie':cookie,'Origin':'http://localhost:5173','X-CSRF-Token':csrf})
    report={'verifiedAtUtc':datetime.now(timezone.utc).isoformat(),'checks':checks,'total':len(checks)}
    (ROOT/'.artifacts/C-api-smoke.json').write_text(json.dumps(report,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
    print(f'Stage C smoke: {len(checks)} checks passed; no credentials recorded')


if __name__=='__main__':main()
