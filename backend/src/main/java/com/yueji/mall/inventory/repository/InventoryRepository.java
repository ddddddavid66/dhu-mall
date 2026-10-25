package com.yueji.mall.inventory.repository;

import com.yueji.mall.common.api.*;
import com.yueji.mall.common.persistence.SqlStore;
import com.yueji.mall.inventory.model.InventoryModels.InventoryView;
import java.util.Map;
import org.springframework.stereotype.Repository;
import static com.yueji.mall.catalog.repository.CatalogRepository.number;
import static com.yueji.mall.common.persistence.SqlStore.text;

@Repository
public class InventoryRepository {
    private final SqlStore sql;
    public InventoryRepository(SqlStore sql) {this.sql=sql;}
    public void initialize(String sku) {sql.insert("INSERT INTO inventory(sku_id) VALUES(?)",sku);}
    public Map<String,Object> lock(String sku) {return sql.required("SELECT * FROM inventory WHERE sku_id=? FOR UPDATE",sku);}
    public InventoryView view(Map<String,Object> row) {return new InventoryView(new ApiId(text(row,"sku_id")),number(row,"on_hand"),number(row,"reserved"),number(row,"on_hand")-number(row,"reserved"),number(row,"version"));}
    public PageData<InventoryView> list(String shop,String sku,PageQuery page) {
        String where=" FROM inventory i JOIN sku k ON k.id=i.sku_id JOIN product p ON p.id=k.product_id WHERE p.shop_id=?"+(sku==null?"":" AND i.sku_id=?");
        Object[] filter=sku==null?new Object[]{shop}:new Object[]{shop,sku};var args=new java.util.ArrayList<Object>(java.util.Arrays.asList(filter));args.add(page.pageSize());args.add(page.offset());
        var list=sql.rows("SELECT i.*"+where+" ORDER BY i.id DESC LIMIT ? OFFSET ?",args.toArray()).stream().map(this::view).toList();
        return new PageData<>(list,page.page(),page.pageSize(),sql.count("SELECT COUNT(*)"+where,filter));
    }
    public void adjust(String sku,long expected,int delta) {
        int changed=sql.update("UPDATE inventory SET on_hand=on_hand+?,version=version+1,updated_at=UTC_TIMESTAMP(3) WHERE sku_id=? AND version=? AND CAST(on_hand AS SIGNED)+? BETWEEN reserved AND 4294967295",delta,sku,expected,delta);
        if(changed!=1) throw new BusinessException(ErrorCode.STATE_CONFLICT);
    }
    public void movement(String sku,int delta,InventoryView after,String key,String reason,String actor,String trace) {
        sql.insert("INSERT INTO inventory_movement(sku_id,movement_type,business_key,on_hand_delta,reserved_delta,on_hand_after,reserved_after,reason,operator_id,trace_id) VALUES(?,'MANUAL_ADJUST',?,?,0,?,?,?,?,?)",sku,key,delta,after.onHand(),after.reserved(),reason,actor,trace);
    }
    public long countMovement(String key){return sql.count("SELECT COUNT(*) FROM inventory_movement WHERE business_key=?",key);}
    public int restock(String sku,int quantity){return sql.update("UPDATE inventory SET on_hand=on_hand+?,version=version+1,updated_at=UTC_TIMESTAMP(3) WHERE sku_id=? AND CAST(on_hand AS SIGNED)+?<=4294967295",quantity,sku,quantity);}
    public void restockMovement(String sku,String item,String afterSale,String type,String key,int quantity,InventoryView after,String actor,String trace){
        sql.insert("INSERT INTO inventory_movement(sku_id,order_item_id,after_sale_id,movement_type,business_key,on_hand_delta,reserved_delta,on_hand_after,reserved_after,reason,operator_id,trace_id) VALUES(?,?,?,?,?,?,0,?,?,?,?,?)",sku,item,afterSale,type,key,quantity,after.onHand(),after.reserved(),"售后库存回补",actor,trace);
    }
}
