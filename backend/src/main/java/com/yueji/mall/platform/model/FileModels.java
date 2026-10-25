package com.yueji.mall.platform.model;

import com.yueji.mall.common.api.ApiId;

public final class FileModels {
    private FileModels() {}
    public enum Purpose { BOOK_COVER, AFTER_SALE_EVIDENCE }
    public record FileView(ApiId id,String purpose,String visibility,String mimeType,long byteSize,String url) {}
    public record FileContent(byte[] bytes,boolean publicFile) {}
}
