package com.yueji.mall.common.config;

import io.swagger.v3.core.util.Json31;
import io.swagger.v3.oas.models.OpenAPI;
import java.io.IOException;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;

@Configuration
public class OpenApiConfiguration {
    @Bean
    OpenAPI stageAContract() throws IOException {
        // 契约文件是当前唯一路由清单，避免生成一组看似能运行的空业务 Controller。
        try (var stream = new ClassPathResource("contracts/openapi.json").getInputStream()) {
            return Json31.mapper().readValue(stream, OpenAPI.class);
        }
    }
}
