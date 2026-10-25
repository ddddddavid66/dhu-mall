package com.yueji.mall.cart.repository;

import com.yueji.mall.common.persistence.SqlStore;
import java.util.*;
import org.springframework.stereotype.Repository;

@Repository
public class CartRepository {
    private final SqlStore sql;
    public CartRepository(SqlStore sql) {this.sql=sql;}
    public List<Map<String,Object>> list(String user) {return sql.rows("SELECT * FROM cart_item WHERE user_id=? ORDER BY id",user);}
    public boolean contains(String user,String sku) {return sql.count("SELECT COUNT(*) FROM cart_item WHERE user_id=? AND sku_id=?",user,sku)>0;}
    public void set(String user,String sku,int quantity,boolean selected) {
        // 设置绝对数量；重复 PUT 不累加，也不改写未变化行的时间戳，便于后续预览核验。
        var row=sql.one("SELECT * FROM cart_item WHERE user_id=? AND sku_id=?",user,sku);
        if(row.isEmpty()) {sql.insert("INSERT INTO cart_item(user_id,sku_id,quantity,selected) VALUES(?,?,?,?)",user,sku,quantity,selected);return;}
        sql.update("UPDATE cart_item SET quantity=?,selected=?,updated_at=UTC_TIMESTAMP(3) WHERE user_id=? AND sku_id=? AND (quantity<>? OR selected<>?)",quantity,selected,user,sku,quantity,selected);
    }
    public void delete(String user,String sku) {sql.update("DELETE FROM cart_item WHERE user_id=? AND sku_id=?",user,sku);}
}
