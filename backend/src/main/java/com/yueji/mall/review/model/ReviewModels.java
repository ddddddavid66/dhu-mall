package com.yueji.mall.review.model;

import com.yueji.mall.common.api.*;
import jakarta.validation.constraints.*;
import java.time.Instant;

public final class ReviewModels {
    private ReviewModels() {}
    public record ReviewInput(@NotNull ApiId orderItemId,@Min(1) @Max(5) int rating,@NotBlank @Size(max=1000) String content) {}
    public record VisibilityInput(@NotBlank @Pattern(regexp="VISIBLE|HIDDEN") String status,@NotBlank @Size(max=300) String reason) {}
    public record ReviewView(ApiId id,ApiId productId,int rating,String content,String nickname,Instant createdAt,String status) {}
}
