package com.yueji.mall.identity.repository;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

@TableName("iam_permission")
public class PermissionEntity {
    @TableId public String id;
    public String code;
    public String name;
}
