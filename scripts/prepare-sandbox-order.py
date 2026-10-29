"""在全新沙箱库经正式API准备一笔虚构新书订单；不调用模拟确认。"""
from pathlib import Path
from datetime import datetime, timezone
import importlib.util
import html
import json
import uuid
import argparse

ROOT = Path(__file__).resolve().parents[1]
OUT = ROOT / '.artifacts/alipay-sandbox'
spec = importlib.util.spec_from_file_location('seed_client', ROOT / 'scripts/seed-stage-c.py')
module = importlib.util.module_from_spec(spec); spec.loader.exec_module(module)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--recover-order', help='Resume a known existing sandbox order, never create a second payment')
    args = parser.parse_args()
    assert not (OUT / 'order.json').exists(), 'Existing order must be recovered rather than recreated'
    accounts = json.loads((OUT / 'records/accounts.json').read_text(encoding='utf-8'))
    client = module.Client('http://127.0.0.1:28080', 'https://localhost:28443')
    for user in ['operator_a', 'merchant_a', 'reader_a']:
        client.login(user, accounts[user]['password'])
    ops, merchant, buyer = 'operator_a', 'merchant_a', 'reader_a'
    if args.recover_order:
        assert args.recover_order.isdigit()
        order = client.request('GET', '/orders/' + args.recover_order, user=buyer)
        payment = client.request('POST', '/orders/' + args.recover_order + '/payments', {}, buyer, key=str(uuid.uuid4()))
        save_entry(order, payment, order['shopId'], order['items'][0]['skuId'])
        return
    assert client.request('GET', '/ops/books', user=ops)['total'] == 0
    category = client.request('POST', '/ops/categories', dict(name='沙箱虚构书目', sortOrder=0, status='ACTIVE'), ops)
    book = client.request('POST', '/ops/books', dict(isbn='9780132350884', title='沙箱联调虚构新书', author='虚构作者',
                          publisher='虚构教学出版社', categoryId=category['id'], description='仅支付宝虚拟资金联调，不实际发货', status='ACTIVE'), ops)
    shop = client.sessions[merchant]['user']['shops'][0]['id']
    product = client.request('POST', f'/merchant/shops/{shop}/products', dict(bookId=book['id'],
                             description='支付宝沙箱教学专用', skuCode='SANDBOX-001', salePrice='0.01'), merchant)
    client.request('POST', f'/merchant/shops/{shop}/inventory/{product["skuId"]}/adjustments',
                   dict(delta=10, reason='沙箱虚构新书入库', version=1), merchant, key=str(uuid.uuid4()))
    client.request('PUT', f'/merchant/shops/{shop}/products/{product["id"]}/status', dict(status='ON_SALE', version=1), merchant)
    address = client.request('POST', '/addresses', dict(receiverName='虚构测试买家', phone='13800000000', province='测试省',
                            city='测试市', district='测试区', detail='教学虚构地址不实际投递', isDefault=True), buyer)
    client.request('PUT', '/cart/items/' + product['skuId'], dict(quantity=1, selected=True), buyer)
    cart = client.request('GET', '/cart', user=buyer)
    ids = [i['id'] for g in cart['groups'] for i in g['items']]
    preview = client.request('POST', '/checkout/previews', dict(addressId=address['id'], cartItemIds=ids), buyer)
    batch = client.request('POST', '/checkout/orders', dict(previewId=preview['previewId']), buyer, key=str(uuid.uuid4()))
    order = batch['orders'][0]
    payment = client.request('POST', '/orders/' + order['id'] + '/payments', {}, buyer, key=str(uuid.uuid4()))
    save_entry(order, payment, shop, product['skuId'])


def save_entry(order, payment, shop, sku):
    assert payment['provider'] == 'ALIPAY_SANDBOX'
    assert payment['paymentAction']['type'] == 'REDIRECT'
    url = payment['paymentAction']['url']; assert url.startswith('https://openapi-sandbox.dl.alipaydev.com/gateway.do?')
    record = dict(createdAt=datetime.now(timezone.utc).isoformat(), provider='ALIPAY_SANDBOX', order=order, payment=payment,
                  shopId=shop, skuId=sku, localMock=False, status='AWAITING_BUYER')
    (OUT / 'order.json').write_text(json.dumps(record, ensure_ascii=False, indent=2), encoding='utf-8')
    # 签名支付URL只保留在忽略目录，不输出终端、不进入Git或课程包。
    page = '<!doctype html><meta charset="utf-8"><title>阅集支付宝沙箱付款</title><h1>支付宝沙箱虚拟付款</h1>'
    page += '<p>虚拟金额 0.01 元；使用沙箱买家及沙箱余额。此链接有订单有效期。</p>'
    page += '<p><a href="' + html.escape(url, quote=True) + '">进入支付宝沙箱收银台</a></p>'
    page += '<p>付款完成后通知代理核验，回跳本身不作为成功依据。</p>'
    (OUT / '付款入口.html').write_text(page, encoding='utf-8')
    print(json.dumps(dict(status='AWAITING_BUYER', orderId=order['id'], paymentNo=payment['paymentNo'],
                          amount='0.01', expiresAt=payment['paymentAction']['expiresAt']), ensure_ascii=False))


if __name__ == '__main__': main()
