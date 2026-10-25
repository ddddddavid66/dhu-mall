package com.yueji.mall.cart.model;

import com.yueji.mall.common.api.ApiId;

public record AddressView(ApiId id,String receiverName,String phone,String province,String city,String district,String detail,boolean isDefault) {}
