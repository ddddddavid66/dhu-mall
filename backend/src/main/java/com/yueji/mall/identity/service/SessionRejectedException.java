package com.yueji.mall.identity.service;

import com.yueji.mall.common.api.BusinessException;
import com.yueji.mall.common.api.ErrorCode;

// 登录失败计数和刷新重放撤销必须提交，不能因响应 401 被回滚。
public class SessionRejectedException extends BusinessException {
    public SessionRejectedException(ErrorCode code) { super(code); }
}
