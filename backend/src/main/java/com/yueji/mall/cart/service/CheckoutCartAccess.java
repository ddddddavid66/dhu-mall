package com.yueji.mall.cart.service;

import com.yueji.mall.common.api.*;
import com.yueji.mall.common.persistence.SqlStore;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

/** 调用方持有本人用户锁，与地址及购物车修改串行；交易模块不直接写购物车。 */
@Service
@Transactional(propagation=Propagation.MANDATORY)
public class CheckoutCartAccess {
    private final SqlStore sql;
    public CheckoutCartAccess(SqlStore sql) {this.sql=sql;}
    public Map<String,Object> item(String user,String id) {
        return sql.required("SELECT * FROM cart_item WHERE user_id=? AND id=?",user,id);
    }
    public Map<String,Object> address(String user,String id) {
        return sql.required("SELECT * FROM user_address WHERE user_id=? AND id=? AND status='ACTIVE'",user,id);
    }
    public void purchased(String user,String id) {
        if(sql.update("DELETE FROM cart_item WHERE user_id=? AND id=?",user,id)!=1) throw new BusinessException(ErrorCode.STATE_CONFLICT);
    }
}
