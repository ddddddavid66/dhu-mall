package com.yueji.mall.catalog.model;

import com.yueji.mall.common.api.ApiId;
import com.yueji.mall.common.api.ApiMoney;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

public final class CatalogViews {
    private CatalogViews() {}
    public record CategoryView(ApiId id,ApiId parentId,String name,int sortOrder,String status,List<CategoryView> children) {}
    public record BookView(ApiId id,String isbn,String title,String author,String publisher,LocalDate publicationDate,
            ApiId categoryId,String description,ApiId coverFileId,String status,String categoryName,String coverUrl) {}
    public record ProductView(ApiId id,ApiId shopId,String shopName,BookView book,ApiId skuId,String skuCode,
            ApiMoney salePrice,long availableQuantity,String status,boolean featured,long version,long skuVersion) {}
    public record ReviewView(ApiId id,ApiId productId,int rating,String content,String nickname,Instant createdAt,String status) {}
}
