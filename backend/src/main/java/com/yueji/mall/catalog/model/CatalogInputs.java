package com.yueji.mall.catalog.model;

import com.yueji.mall.common.api.ApiId;
import com.yueji.mall.common.api.ApiMoney;
import jakarta.validation.constraints.*;
import java.time.LocalDate;

public final class CatalogInputs {
    private CatalogInputs() {}
    public enum Status { ACTIVE, DISABLED }
    public enum ProductStatus { DRAFT, ON_SALE, OFF_SHELF }
    public record CategoryInput(ApiId parentId,@NotBlank @Size(max=50) @Pattern(regexp="[^<>]*") String name,
            @NotNull Integer sortOrder,@NotNull Status status) {}
    public record BookInput(@NotBlank @Size(max=17) String isbn,@NotBlank @Size(max=200) @Pattern(regexp="[^<>]*") String title,
            @NotBlank @Size(max=200) @Pattern(regexp="[^<>]*") String author,@NotBlank @Size(max=120) @Pattern(regexp="[^<>]*") String publisher,
            LocalDate publicationDate,@NotNull ApiId categoryId,@NotNull @Size(max=5000) @Pattern(regexp="[^<>]*") String description,
            ApiId coverFileId,@NotNull Status status) {}
    public record ProductInput(@NotNull ApiId bookId,@NotNull @Size(max=5000) @Pattern(regexp="[^<>]*") String description,
            @NotNull @Pattern(regexp="[A-Za-z0-9_-]{1,64}") String skuCode,@NotNull ApiMoney salePrice) {}
    public record ProductEdit(@NotNull @Size(max=5000) @Pattern(regexp="[^<>]*") String description,@NotNull ApiMoney salePrice,
            @NotNull @Min(1) @Max(4294967295L) Long version) {}
    public record ProductStatusInput(@NotNull ProductStatus status,@NotNull @Min(1) @Max(4294967295L) Long version) {}
    public record ModerationInput(@NotNull Boolean offShelf,@NotNull Boolean featured,@NotBlank @Size(max=300) @Pattern(regexp="[^<>]*") String reason,
            @NotNull @Min(1) @Max(4294967295L) Long version) {}
}
