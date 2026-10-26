package com.yueji.mall.common.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/** 统一管理调度启用，避免某个基础设施配置意外启用所有测试上下文的后台写入。 */
@Configuration @EnableScheduling
@ConditionalOnProperty(name="yueji.scheduling.enabled",havingValue="true",matchIfMissing=true)
public class SchedulingConfiguration {}
