package com.yueji.mall.trade.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yueji.mall.cart.model.AddressView;
import com.yueji.mall.cart.service.CheckoutCartAccess;
import com.yueji.mall.catalog.service.CatalogAccess;
import com.yueji.mall.common.api.*;
import com.yueji.mall.common.persistence.*;
import com.yueji.mall.common.service.*;
import com.yueji.mall.identity.model.Actor;
import com.yueji.mall.identity.repository.IdentityRepository;
import com.yueji.mall.identity.service.*;
import com.yueji.mall.inventory.service.ReservationService;
import com.yueji.mall.platform.service.OutboxService;
import com.yueji.mall.trade.model.TradeModels.*;
import com.yueji.mall.trade.repository.TradeRepository;
import java.math.*;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;
import org.springframework.stereotype.Service;
import static com.yueji.mall.common.persistence.SqlStore.text;
import static com.yueji.mall.catalog.repository.CatalogRepository.number;

@Service
public class CheckoutService {
    private final SqlStore sql;private final ObjectMapper mapper;private final Clock clock;private final DatabaseTransaction transactions;
    private final IdentityRepository identities;private final IdentityPolicy policy;private final CheckoutCartAccess cart;
    private final CatalogAccess catalog;private final ReservationService inventory;private final IdempotencyService idempotency;
    private final TradeRepository orders;private final OutboxService outbox;
    public CheckoutService(SqlStore sql,ObjectMapper mapper,Clock clock,DatabaseTransaction transactions,IdentityRepository identities,IdentityPolicy policy,CheckoutCartAccess cart,CatalogAccess catalog,ReservationService inventory,IdempotencyService idempotency,TradeRepository orders,OutboxService outbox) {
        this.sql=sql;this.mapper=mapper;this.clock=clock;this.transactions=transactions;this.identities=identities;this.policy=policy;this.cart=cart;this.catalog=catalog;this.inventory=inventory;this.idempotency=idempotency;this.orders=orders;this.outbox=outbox;
    }
    private void lock(Actor actor) {
        identities.shareCommerceAnchor();identities.lockUser(actor.userId());policy.require(actor,"member.order");
    }
    public PreviewView preview(Actor actor,PreviewInput input) {
        return transactions.run(()-> {
            lock(actor);
            if(new HashSet<>(input.cartItemIds()).size()!=input.cartItemIds().size()) throw new BusinessException(ErrorCode.PARAM_INVALID);
            var address=cart.address(actor.userId(),input.addressId().value());
            fullAddress(address);
            var snapshots=new ArrayList<ItemSnapshot>();var products=new HashMap<String,Map<String,Object>>();
            for(var id:input.cartItemIds()) {
                var line=cart.item(actor.userId(),id.value());
                if(number(line,"selected")!=1) throw new BusinessException(ErrorCode.STATE_CONFLICT);
                String sku=text(line,"sku_id");int quantity=(int)number(line,"quantity");var product=catalog.sku(sku);catalog.requirePurchasable(product,quantity);
                products.put(sku,product);
                snapshots.add(new ItemSnapshot(id.value(),RowValues.instant(line.get("updated_at")),sku,quantity,number(product,"sku_version"),number(product,"version"),new ApiMoney((BigDecimal)product.get("sale_price")),text(product,"shop_id")));
            }
            // 数值排序避免字符串 "10" 排在 "2" 前；一个确定顺序贯穿所有库存锁与预占。
            snapshots.sort(Comparator.comparing(i->new BigInteger(i.skuId())));
            var shops=previewShops(snapshots,products);var total=sum(snapshots);
            Instant expires=clock.instant().plus(Duration.ofMinutes(5));
            String id=sql.insert("INSERT INTO checkout_preview(user_id,address_id,items_snapshot,address_version_hash,amount,expires_at) VALUES(?,?,?,?,?,?)",actor.userId(),input.addressId().value(),json(snapshots),addressHash(address),total,Timestamp.from(expires));
            var a=new AddressView(input.addressId(),text(address,"receiver_name"),text(address,"phone"),text(address,"province"),text(address,"city"),text(address,"district"),text(address,"detail"),number(address,"is_default")==1);
            return new PreviewView(new ApiId(id),expires,a,shops,new ApiMoney(total));
        });
    }
    public BatchView create(Actor actor,CreateOrderInput input,String key,String trace) {
        return transactions.run(()-> {
            lock(actor);
            var batch=idempotency.execute(actor,"API-27",key,input,"checkout_batch",null,BatchView.class,()->createBatch(actor,input.previewId().value(),trace));
            sql.update("UPDATE idempotency_record SET resource_id=? WHERE user_id=? AND operation_id='API-27' AND idempotency_key=? AND resource_id IS NULL",batch.batchId().value(),actor.userId(),key);
            return batch;
        });
    }
    private BatchView createBatch(Actor actor,String previewId,String trace) {
        var preview=sql.required("SELECT * FROM checkout_preview WHERE id=? AND user_id=? FOR UPDATE",previewId,actor.userId());
        // 04 规定已用预览返回状态冲突；响应丢失只能重放原键或查询既有批次。
        var existing=sql.one("SELECT id FROM checkout_batch WHERE preview_id=? AND user_id=?",previewId,actor.userId());
        if(existing.isPresent()) throw new BusinessException(ErrorCode.STATE_CONFLICT);
        if(!RowValues.instant(preview.get("expires_at")).isAfter(clock.instant())) throw new BusinessException(ErrorCode.PREVIEW_EXPIRED);
        if(preview.get("used_at")!=null) throw new BusinessException(ErrorCode.STATE_CONFLICT);
        var items=parse(text(preview,"items_snapshot"));
        var address=unchangedAddress(actor,preview);var products=unchangedItems(actor,items);
        inventory.lockAvailable(items.stream().map(ItemSnapshot::skuId).toList(),items.stream().map(ItemSnapshot::quantity).toList());
        String batch=sql.insert("INSERT INTO checkout_batch(batch_no,user_id,preview_id,total_amount) VALUES(?,?,?,?)",businessNo("B"),actor.userId(),previewId,preview.get("amount"));
        Instant expires=clock.instant().plus(Duration.ofMinutes(30));
        for(var group:groups(items).entrySet()) {
            BigDecimal amount=sum(group.getValue());String shop=group.getKey();
            String order=sql.insert("INSERT INTO trade_order(order_no,batch_id,user_id,shop_id,goods_amount,payable_amount,receiver_name,receiver_phone,receiver_address,expires_at) VALUES(?,?,?,?,?,?,?,?,?,?)",businessNo("O"),batch,actor.userId(),shop,amount,amount,address.get("receiver_name"),address.get("phone"),fullAddress(address),Timestamp.from(expires));
            for(var item:group.getValue()) {
                var p=products.get(item.skuId());
                String line=sql.insert("INSERT INTO order_item(order_id,sku_id,book_id,product_id,book_title,isbn,cover_storage_key,sku_code,unit_price,quantity,line_amount) VALUES(?,?,?,?,?,?,?,?,?,?,?)",order,item.skuId(),p.get("book_id"),p.get("id"),p.get("title"),p.get("isbn"),catalog.coverStorageKey(p),p.get("sku_code"),item.price().value(),item.quantity(),lineAmount(item));
                inventory.reserve(order,line,item.skuId(),item.quantity(),expires,actor.userId(),trace);
                cart.purchased(actor.userId(),item.cartItemId());
            }
            orders.log(order,null,"PENDING_PAYMENT","CREATE",actor.userId(),"按店创建订单",trace);outbox.orderCreated(order,shop,trace);
        }
        sql.update("UPDATE checkout_preview SET used_at=UTC_TIMESTAMP(3),updated_at=UTC_TIMESTAMP(3) WHERE id=?",previewId);
        return orders.batch(actor.userId(),batch);
    }
    private Map<String,Object> unchangedAddress(Actor actor,Map<String,Object> preview) {
        try {
            var address=cart.address(actor.userId(),text(preview,"address_id"));
            if(!addressHash(address).equals(preview.get("address_version_hash"))) throw new BusinessException(ErrorCode.STATE_CONFLICT);
            return address;
        } catch(BusinessException error) {throw new BusinessException(ErrorCode.STATE_CONFLICT);}
    }
    private Map<String,Map<String,Object>> unchangedItems(Actor actor,List<ItemSnapshot> items) {
        var products=new HashMap<String,Map<String,Object>>();
        for(var item:items) {
            Map<String,Object> line;
            try {line=cart.item(actor.userId(),item.cartItemId());}
            catch(BusinessException error) {throw new BusinessException(ErrorCode.STATE_CONFLICT);}
            if(number(line,"selected")!=1 || number(line,"quantity")!=item.quantity() || !text(line,"sku_id").equals(item.skuId()) || !RowValues.instant(line.get("updated_at")).equals(item.cartUpdatedAt())) throw new BusinessException(ErrorCode.STATE_CONFLICT);
            var product=catalog.sku(item.skuId());
            if(number(product,"sku_version")!=item.skuVersion() || ((BigDecimal)product.get("sale_price")).compareTo(item.price().value())!=0) throw new BusinessException(ErrorCode.PRICE_CHANGED);
            if(number(product,"version")!=item.productVersion() || !text(product,"shop_id").equals(item.shopId())) throw new BusinessException(ErrorCode.STATE_CONFLICT);
            catalog.requirePurchasable(product,item.quantity());products.put(item.skuId(),product);
        }
        return products;
    }
    private List<PreviewShop> previewShops(List<ItemSnapshot> items,Map<String,Map<String,Object>> products) {
        var result=new ArrayList<PreviewShop>();
        for(var group:groups(items).entrySet()) {
            var amount=new ApiMoney(sum(group.getValue()));
            var lines=group.getValue().stream().map(i->new PreviewItem(new ApiId(i.skuId()),text(products.get(i.skuId()),"title"),i.quantity(),i.price(),new ApiMoney(lineAmount(i)))).toList();
            result.add(new PreviewShop(new ApiId(group.getKey()),text(products.get(group.getValue().getFirst().skuId()),"shop_name"),lines,amount,new ApiMoney(BigDecimal.ZERO.setScale(2)),amount));
        }
        return result;
    }
    private Map<String,List<ItemSnapshot>> groups(List<ItemSnapshot> items) {
        var groups=new TreeMap<String,List<ItemSnapshot>>(Comparator.comparing(BigInteger::new));
        for(var item:items) groups.computeIfAbsent(item.shopId(),k->new ArrayList<>()).add(item);return groups;
    }
    private BigDecimal lineAmount(ItemSnapshot i) {return i.price().value().multiply(BigDecimal.valueOf(i.quantity()));}
    private BigDecimal sum(List<ItemSnapshot> items) {
        BigDecimal amount=items.stream().map(this::lineAmount).reduce(BigDecimal.ZERO.setScale(2),BigDecimal::add);
        if(amount.compareTo(new BigDecimal("9999999999.99"))>0) throw new BusinessException(ErrorCode.PARAM_INVALID);return amount;
    }
    private String fullAddress(Map<String,Object> a) {
        String address=text(a,"province")+" "+text(a,"city")+" "+text(a,"district")+" "+text(a,"detail");
        if(address.codePointCount(0,address.length())>300) throw new BusinessException(ErrorCode.PARAM_INVALID);
        return address;
    }
    private String addressHash(Map<String,Object> a) {
        return Crypto.hash(json(List.of(text(a,"id"),text(a,"receiver_name"),text(a,"phone"),text(a,"province"),text(a,"city"),text(a,"district"),text(a,"detail"),text(a,"status"),RowValues.instant(a.get("updated_at")).toString())));
    }
    private String json(Object value) {
        try {return mapper.writeValueAsString(value);}catch(com.fasterxml.jackson.core.JsonProcessingException error) {throw new IllegalStateException("交易快照序列化失败",error);}
    }
    private List<ItemSnapshot> parse(String json) {
        try {return mapper.readValue(json,new TypeReference<List<ItemSnapshot>>() {});}catch(com.fasterxml.jackson.core.JsonProcessingException error) {throw new IllegalStateException("交易快照读取失败",error);}
    }
    private String businessNo(String prefix) {return prefix+UUID.randomUUID().toString().replace("-","");}
}
