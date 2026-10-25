package com.yueji.mall.catalog.controller;

import com.yueji.mall.common.api.*;
import com.yueji.mall.common.web.TraceIdFilter;
import com.yueji.mall.catalog.model.CatalogInputs.*;
import com.yueji.mall.catalog.model.CatalogViews.*;
import com.yueji.mall.catalog.service.*;
import com.yueji.mall.identity.model.Actor;
import io.swagger.v3.oas.annotations.Hidden;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@Hidden @RestController @RequestMapping("/api/v1")
public class CatalogController {
    private final CatalogMaintenance maintenance;private final ProductService products;
    public CatalogController(CatalogMaintenance maintenance,ProductService products) {this.maintenance=maintenance;this.products=products;}
    @GetMapping("/categories")
    public Result<List<CategoryView>> categories(HttpServletRequest request) {return ok(maintenance.categories(null,null,false),request);}
    @GetMapping("/products")
    public Result<PageData<ProductView>> products(@ModelAttribute PageQuery page,@RequestParam(required=false) String keyword,
            @RequestParam(required=false) String categoryId,@RequestParam(required=false) String shopId,@RequestParam(required=false) Boolean featured,HttpServletRequest request) {
        return ok(products.list(null,shopId,null,keyword,categoryId,featured,page,true,false),request);
    }
    @GetMapping("/products/{id}")
    public Result<ProductView> product(@PathVariable String id,HttpServletRequest request) {return ok(products.publicProduct(id),request);}
    @GetMapping("/products/{id}/reviews")
    public Result<PageData<ReviewView>> reviews(@PathVariable String id,@ModelAttribute PageQuery page,HttpServletRequest request) {return ok(products.reviews(id,page),request);}
    @GetMapping("/merchant/shops/{shopId}/products")
    public Result<PageData<ProductView>> merchantProducts(@AuthenticationPrincipal Actor actor,@PathVariable String shopId,@ModelAttribute PageQuery page,
            @RequestParam(required=false) ProductStatus status,@RequestParam(required=false) String keyword,HttpServletRequest request) {
        return ok(products.list(actor,shopId,status==null?null:status.name(),keyword,null,null,page,false,false),request);
    }
    @PostMapping("/merchant/shops/{shopId}/products") @ResponseStatus(HttpStatus.CREATED)
    public Result<ProductView> createProduct(@AuthenticationPrincipal Actor actor,@PathVariable String shopId,@Valid @RequestBody ProductInput input,HttpServletRequest request) {return ok(products.create(actor,shopId,input),request);}
    @PutMapping("/merchant/shops/{shopId}/products/{id}")
    public Result<ProductView> editProduct(@AuthenticationPrincipal Actor actor,@PathVariable String shopId,@PathVariable String id,@Valid @RequestBody ProductEdit input,HttpServletRequest request) {return ok(products.edit(actor,shopId,id,input),request);}
    @PutMapping("/merchant/shops/{shopId}/products/{id}/status")
    public Result<ProductView> productStatus(@AuthenticationPrincipal Actor actor,@PathVariable String shopId,@PathVariable String id,@Valid @RequestBody ProductStatusInput input,HttpServletRequest request) {return ok(products.status(actor,shopId,id,input),request);}
    @GetMapping("/ops/books")
    public Result<PageData<BookView>> books(@AuthenticationPrincipal Actor actor,@ModelAttribute PageQuery page,@RequestParam(required=false) String keyword,HttpServletRequest request) {return ok(maintenance.books(actor,page,keyword),request);}
    @PostMapping("/ops/books") @ResponseStatus(HttpStatus.CREATED)
    public Result<BookView> createBook(@AuthenticationPrincipal Actor actor,@Valid @RequestBody BookInput input,HttpServletRequest request) {return ok(maintenance.saveBook(actor,null,input),request);}
    @PutMapping("/ops/books/{id}")
    public Result<BookView> editBook(@AuthenticationPrincipal Actor actor,@PathVariable String id,@Valid @RequestBody BookInput input,HttpServletRequest request) {return ok(maintenance.saveBook(actor,id,input),request);}
    @GetMapping("/ops/categories")
    public Result<List<CategoryView>> opsCategories(@AuthenticationPrincipal Actor actor,@RequestParam(required=false) Status status,HttpServletRequest request) {return ok(maintenance.categories(actor,status,true),request);}
    @PostMapping("/ops/categories") @ResponseStatus(HttpStatus.CREATED)
    public Result<CategoryView> createCategory(@AuthenticationPrincipal Actor actor,@Valid @RequestBody CategoryInput input,HttpServletRequest request) {return ok(maintenance.saveCategory(actor,null,input),request);}
    @PutMapping("/ops/categories/{id}")
    public Result<CategoryView> editCategory(@AuthenticationPrincipal Actor actor,@PathVariable String id,@Valid @RequestBody CategoryInput input,HttpServletRequest request) {return ok(maintenance.saveCategory(actor,id,input),request);}
    @PutMapping("/ops/products/{id}/moderation")
    public Result<ProductView> moderate(@AuthenticationPrincipal Actor actor,@PathVariable String id,@Valid @RequestBody ModerationInput input,HttpServletRequest request) {return ok(products.moderate(actor,id,input),request);}
    @GetMapping("/ops/products")
    public Result<PageData<ProductView>> opsProducts(@AuthenticationPrincipal Actor actor,@ModelAttribute PageQuery page,
            @RequestParam(required=false) String keyword,@RequestParam(required=false) String shopId,@RequestParam(required=false) ProductStatus status,HttpServletRequest request) {
        return ok(products.list(actor,shopId,status==null?null:status.name(),keyword,null,null,page,false,true),request);
    }
    private <T> Result<T> ok(T value,HttpServletRequest request) {return Result.ok(value,TraceIdFilter.current(request));}
}
