package com.yueji.mall.cart.repository;

import com.yueji.mall.cart.model.AddressInput;
import com.yueji.mall.cart.model.AddressView;
import com.yueji.mall.common.api.ApiId;
import com.yueji.mall.common.persistence.SqlStore;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Repository;
import static com.yueji.mall.common.persistence.SqlStore.text;

@Repository
public class AddressRepository {
    private final SqlStore sql;
    public AddressRepository(SqlStore sql) { this.sql=sql; }
    public List<AddressView> list(String user) {
        return sql.rows("SELECT * FROM user_address WHERE user_id=? AND status='ACTIVE' ORDER BY is_default DESC,id",user).stream().map(this::view).toList();
    }
    public Optional<Map<String,Object>> owned(String user,String id) {
        return sql.one("SELECT * FROM user_address WHERE user_id=? AND id=?",user,id);
    }
    public void clearDefault(String user) {
        sql.update("UPDATE user_address SET is_default=0,updated_at=UTC_TIMESTAMP(3) WHERE user_id=? AND status='ACTIVE' AND is_default=1",user);
    }
    public AddressView create(String user,AddressInput input) {
        String id=sql.insert("""
                INSERT INTO user_address(user_id,receiver_name,phone,province,city,district,detail,is_default)
                VALUES(?,?,?,?,?,?,?,?)
                """,user,input.receiverName(),input.phone(),input.province(),input.city(),input.district(),input.detail(),input.isDefault());
        return view(owned(user,id).orElseThrow());
    }
    public AddressView update(String user,String id,AddressInput input) {
        sql.update("""
                UPDATE user_address SET receiver_name=?,phone=?,province=?,city=?,district=?,detail=?,is_default=?,updated_at=UTC_TIMESTAMP(3)
                WHERE user_id=? AND id=? AND status='ACTIVE'
                """,input.receiverName(),input.phone(),input.province(),input.city(),input.district(),input.detail(),input.isDefault(),user,id);
        return view(owned(user,id).orElseThrow());
    }
    public void delete(String user,String id) {
        sql.update("UPDATE user_address SET status='DELETED',is_default=0,updated_at=UTC_TIMESTAMP(3) WHERE user_id=? AND id=? AND status='ACTIVE'",user,id);
    }
    private AddressView view(Map<String,Object> row) {
        return new AddressView(new ApiId(text(row,"id")),text(row,"receiver_name"),text(row,"phone"),text(row,"province"),
                text(row,"city"),text(row,"district"),text(row,"detail"),((Number)row.get("is_default")).intValue()==1);
    }
}
