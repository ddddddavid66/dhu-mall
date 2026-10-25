package com.yueji.mall.identity.model;

import java.util.Set;

public record Actor(String userId, long authVersion, String familyNo, Set<String> permissions) {}
