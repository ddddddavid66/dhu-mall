package com.yueji.mall.platform.service;

import com.yueji.mall.common.api.*;
import com.yueji.mall.identity.service.Crypto;
import java.io.IOException;
import java.nio.file.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class FileStorage {
    private final Path root;
    public FileStorage(@Value("${yueji.files.root}") String root) {this.root=Path.of(root).toAbsolutePath().normalize();}
    public String write(byte[] bytes,boolean publicFile) {
        String key=(publicFile?"public/":"private/")+Crypto.randomToken()+".png";
        Path path=path(key);
        try {
            Files.createDirectories(path.getParent());
            if(Files.isSymbolicLink(root) || Files.isSymbolicLink(path.getParent())) throw new IOException("文件根不能是符号链接");
            Files.write(path,bytes,StandardOpenOption.CREATE_NEW);return key;
        } catch(IOException error) {throw new BusinessException(ErrorCode.DEPENDENCY_UNAVAILABLE);}
    }
    public byte[] read(String key) {
        try {
            Path path=path(key);
            if(Files.isSymbolicLink(root) || Files.isSymbolicLink(path.getParent()) || Files.isSymbolicLink(path) || !Files.isRegularFile(path,LinkOption.NOFOLLOW_LINKS)) throw new IOException("文件不可读");
            if(Files.size(path)>SafeImageCodec.MAX_BYTES) throw new IOException("存储文件大小异常");
            return Files.readAllBytes(path);
        } catch(IOException error) {throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND);}
    }
    public void discard(String key) {
        try {Files.deleteIfExists(path(key));} catch(IOException error) {throw new BusinessException(ErrorCode.DEPENDENCY_UNAVAILABLE);}
    }
    private Path path(String key) {
        if(key==null || !key.matches("(public|private)/[A-Za-z0-9_-]{43}\\.png")) throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND);
        Path path=root.resolve(key).normalize();
        if(!path.startsWith(root)) throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND);
        return path;
    }
}
