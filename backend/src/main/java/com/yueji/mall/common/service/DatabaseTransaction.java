package com.yueji.mall.common.service;

import java.util.function.Supplier;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.*;
import org.springframework.transaction.support.TransactionTemplate;

/** 每次重试必须进入新事务；业务拒绝、序列化错误和外部渠道调用不重试。 */
@Component
public class DatabaseTransaction {
    private final TransactionTemplate template;
    public DatabaseTransaction(PlatformTransactionManager manager) {
        template=new TransactionTemplate(manager);
        template.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    }
    public <T> T run(Supplier<T> action) {
        for(int attempt=0;;attempt++) {
            try {return template.execute(status->action.get());}
            catch(PessimisticLockingFailureException error) {if(attempt>=2) throw error;}
        }
    }
}
