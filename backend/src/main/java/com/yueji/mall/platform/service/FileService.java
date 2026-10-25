package com.yueji.mall.platform.service;

import com.yueji.mall.common.api.*;
import com.yueji.mall.identity.model.Actor;
import com.yueji.mall.identity.repository.IdentityRepository;
import com.yueji.mall.identity.service.IdentityPolicy;
import com.yueji.mall.platform.model.FileModels.*;
import com.yueji.mall.platform.repository.*;
import java.util.HexFormat;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.*;
import org.springframework.web.multipart.MultipartFile;
import static com.yueji.mall.common.persistence.SqlStore.text;

@Service
public class FileService {
    private final FileRepository repository;private final FileStorage storage;private final SafeImageCodec codec;
    private final IdentityPolicy policy;private final IdentityRepository identities;private final AuditRepository audit;
    public FileService(FileRepository repository,FileStorage storage,SafeImageCodec codec,IdentityPolicy policy,IdentityRepository identities,AuditRepository audit) {
        this.repository=repository;this.storage=storage;this.codec=codec;this.policy=policy;this.identities=identities;this.audit=audit;
    }
    @Transactional
    public FileView upload(Actor actor,MultipartFile file,Purpose purpose) {
        identities.lockUser(actor.userId());policy.require(actor,purpose==Purpose.BOOK_COVER?"file.cover.upload":"file.evidence.upload");
        byte[] bytes=codec.encode(file);boolean publicFile=purpose==Purpose.BOOK_COVER;String key=storage.write(bytes,publicFile);
        // 文件先落盘；数据库事务失败时只删除本次生成的文件，不清扫共享目录。
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            public void afterCompletion(int status) {if(status!=STATUS_COMMITTED) storage.discard(key);}
        });
        String hash;
        try {hash=HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes));}
        catch(java.security.NoSuchAlgorithmException error) {throw new IllegalStateException(error);}
        String id=repository.create(actor.userId(),purpose.name(),publicFile?"PUBLIC":"PRIVATE",key,bytes.length,hash);
        audit.append(actor.userId(),"file.upload","file",id,null,"SUCCESS");
        return new FileView(new ApiId(id),purpose.name(),publicFile?"PUBLIC":"PRIVATE","image/png",bytes.length,publicFile?"/api/v1/files/"+id+"/content":null);
    }
    public void requireCover(String id) {
        new ApiId(id);var row=repository.file(id);
        if(!"BOOK_COVER".equals(row.get("purpose")) || !"PUBLIC".equals(row.get("visibility"))) throw new BusinessException(ErrorCode.PARAM_INVALID);
    }
    public FileContent content(Actor actor,String id) {
        new ApiId(id);var row=repository.file(id);boolean publicFile="PUBLIC".equals(row.get("visibility"));
        if(!publicFile) {
            if(actor==null) throw new BusinessException(ErrorCode.AUTH_REQUIRED);
            policy.require(actor,"file.private.read");
            String owner=text(row,"owner_user_id");
            if(!owner.equals(actor.userId()) && !repository.associated(actor,id,owner)) throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND);
        }
        return new FileContent(storage.read(text(row,"storage_key")),publicFile);
    }
}
