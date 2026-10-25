package com.yueji.mall;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration;

// 阶段 A 尚未实现身份模块，禁止自动生成可登录的默认用户。
@SpringBootApplication(exclude = UserDetailsServiceAutoConfiguration.class)
public class YuejiApplication {
    public static void main(String[] args) {
        SpringApplication.run(YuejiApplication.class, args);
    }
}
