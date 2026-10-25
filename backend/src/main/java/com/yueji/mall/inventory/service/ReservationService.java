package com.yueji.mall.inventory.service;

import com.yueji.mall.common.api.*;
import com.yueji.mall.common.persistence.SqlStore;
import com.yueji.mall.inventory.repository.InventoryRepository;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

@Service
@Transactional(propagation=Propagation.MANDATORY)
public class ReservationService {
    private final SqlStore sql;private final InventoryRepository inventory;
    public ReservationService(SqlStore sql,InventoryRepository inventory) {this.sql=sql;this.inventory=inventory;}
    /** 调用方必须以数值 SKU ID 升序传入；全部锁完成后才能建立订单。 */
    public void lockAvailable(List<String> skus,List<Integer> quantities) {
        for(int i=0;i<skus.size();i++) {
            var stock=inventory.view(inventory.lock(skus.get(i)));
            if(stock.available()<quantities.get(i)) throw new BusinessException(ErrorCode.OUT_OF_STOCK);
        }
    }
    public void reserve(String order,String item,String sku,int quantity,Instant expires,String actor,String trace) {
        int changed=sql.update("UPDATE inventory SET reserved=reserved+?,version=version+1,updated_at=UTC_TIMESTAMP(3) WHERE sku_id=? AND on_hand-reserved>=?",quantity,sku,quantity);
        if(changed!=1) throw new BusinessException(ErrorCode.OUT_OF_STOCK);
        sql.insert("INSERT INTO inventory_reservation(order_id,order_item_id,sku_id,quantity,expires_at) VALUES(?,?,?,?,?)",order,item,sku,quantity,Timestamp.from(expires));
        var after=inventory.view(inventory.lock(sku));
        sql.insert("INSERT INTO inventory_movement(sku_id,order_item_id,movement_type,business_key,on_hand_delta,reserved_delta,on_hand_after,reserved_after,reason,operator_id,trace_id) VALUES(?,?,'RESERVE',?,0,?,?,?,?,?,?)",sku,item,"RESERVE:"+item,quantity,after.onHand(),after.reserved(),"下单预占",actor,trace);
    }
    /** 先锁订单行，再升序锁全部库存和预占；财务行必须在本方法之后加锁。 */
    public void lockOrderResources(String order) {
        var items=sql.rows("SELECT id,sku_id FROM order_item WHERE order_id=? ORDER BY id FOR UPDATE",order);
        items.stream().map(r->r.get("sku_id").toString()).distinct().sorted(java.util.Comparator.comparing(java.math.BigInteger::new)).forEach(inventory::lock);
        sql.rows("SELECT id FROM inventory_reservation WHERE order_id=? ORDER BY sku_id,id FOR UPDATE",order);
    }
    public void finish(String order,boolean paid,String trace) {
        for(var row:sql.rows("SELECT * FROM inventory_reservation WHERE order_id=? ORDER BY sku_id,id",order)) {
            String target=paid?"COMMITTED":"RELEASED",item=row.get("order_item_id").toString(),sku=row.get("sku_id").toString();
            if(target.equals(row.get("status"))) continue;
            if(!"RESERVED".equals(row.get("status"))) throw new BusinessException(ErrorCode.STATE_CONFLICT);
            int quantity=((Number)row.get("quantity")).intValue(),physical=paid?-quantity:0;
            if(sql.update("UPDATE inventory SET on_hand=on_hand+?,reserved=reserved-?,version=version+1,updated_at=UTC_TIMESTAMP(3) WHERE sku_id=? AND reserved>=? AND on_hand>=?",physical,quantity,sku,quantity,paid?quantity:0)!=1) throw new BusinessException(ErrorCode.STATE_CONFLICT);
            if(sql.update("UPDATE inventory_reservation SET status=?,updated_at=UTC_TIMESTAMP(3) WHERE id=? AND status='RESERVED'",target,row.get("id"))!=1) throw new BusinessException(ErrorCode.STATE_CONFLICT);
            var after=inventory.view(inventory.lock(sku));String kind=paid?"COMMIT":"RELEASE";
            sql.insert("INSERT INTO inventory_movement(sku_id,order_item_id,movement_type,business_key,on_hand_delta,reserved_delta,on_hand_after,reserved_after,reason,trace_id) VALUES(?,?,?,?,?,?,?,?,?,?)",sku,item,kind,kind+":"+item,physical,-quantity,after.onHand(),after.reserved(),paid?"核实支付扣减":"核实关单释放",trace);
        }
    }
}
