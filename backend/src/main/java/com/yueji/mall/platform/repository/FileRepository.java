package com.yueji.mall.platform.repository;

import com.yueji.mall.common.persistence.SqlStore;
import com.yueji.mall.identity.model.Actor;
import java.util.Map;
import org.springframework.stereotype.Repository;

@Repository
public class FileRepository {
    private final SqlStore sql;
    public FileRepository(SqlStore sql) {this.sql=sql;}
    public Map<String,Object> file(String id) {return sql.required("SELECT * FROM file_object WHERE id=? AND status='ACTIVE'",id);}
    public String create(String user,String purpose,String visibility,String key,long size,String hash) {
        return sql.insert("INSERT INTO file_object(owner_user_id,purpose,visibility,storage_key,mime_type,byte_size,sha256) VALUES(?,?,?,?,'image/png',?,?)",user,purpose,visibility,key,size,hash);
    }
    public boolean associated(Actor actor,String file,String owner) {
        String join=" FROM after_sale_evidence e JOIN after_sale a ON a.id=e.after_sale_id JOIN order_item oi ON oi.id=a.order_item_id AND oi.order_id=a.order_id JOIN trade_order o ON o.id=a.order_id AND o.user_id=a.user_id WHERE e.file_id=? AND a.user_id=?";
        if(actor.permissions().contains("support.after_sale.read") && sql.count("SELECT COUNT(*)"+join,file,owner)>0) return true;
        return actor.permissions().contains("shop.return.inspect") && sql.count("SELECT COUNT(*)"+join+" AND a.type='RETURN_REFUND' AND EXISTS(SELECT 1 FROM shop_member m WHERE m.shop_id=o.shop_id AND m.user_id=? AND m.duty='WAREHOUSE' AND m.status='ACTIVE')",file,owner,actor.userId())>0;
    }
}
