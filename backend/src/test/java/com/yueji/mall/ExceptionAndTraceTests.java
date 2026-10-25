package com.yueji.mall;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.yueji.mall.common.api.BusinessException;
import com.yueji.mall.common.api.ErrorCode;
import com.yueji.mall.common.web.GlobalExceptionHandler;
import com.yueji.mall.common.web.TraceIdFilter;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class ExceptionAndTraceTests {
    private MockMvc mvc;

    @BeforeEach
    void setup() {
        var mapper = JsonMapper.builder().enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES).build();
        // 测试专用 Controller 只存在于测试类路径，不向真实应用添加调试后门。
        mvc = MockMvcBuilders.standaloneSetup(new Probe())
                .setControllerAdvice(new GlobalExceptionHandler())
                .setMessageConverters(new MappingJackson2HttpMessageConverter(mapper))
                .addFilters(new TraceIdFilter()).build();
    }

    @Test
    void validationAndMalformedJsonHaveSafeConsistentErrors() throws Exception {
        mvc.perform(post("/_probe").contentType(MediaType.APPLICATION_JSON).content("{\"value\":\"\"}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("PARAM_INVALID"))
                .andExpect(jsonPath("$.details[0].field").value("value"));
        mvc.perform(post("/_probe").contentType(MediaType.APPLICATION_JSON).content("{\"value\":\"a\",\"password\":\"never-log-this\"}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("PARAM_INVALID"))
                .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("never-log-this"))));
    }

    @Test
    void businessAndUnexpectedErrorsPreserveTraceAndCleanMdc() throws Exception {
        mvc.perform(get("/_business").header("X-Request-Id", "test-trace-123"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("STATE_CONFLICT"))
                .andExpect(jsonPath("$.traceId").value("test-trace-123"));
        mvc.perform(get("/_unexpected"))
                .andExpect(status().isInternalServerError()).andExpect(jsonPath("$.code").value("INTERNAL_ERROR"))
                .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("secret-in-exception"))));
        assertThat(MDC.get("traceId")).isNull();
    }

    record Input(@NotBlank String value) {}

    @RestController
    static class Probe {
        @PostMapping("/_probe") Input probe(@Valid @RequestBody Input input) { return input; }
        @GetMapping("/_business") void business() { throw new BusinessException(ErrorCode.STATE_CONFLICT); }
        @GetMapping("/_unexpected") void unexpected() { throw new IllegalStateException("secret-in-exception"); }
    }
}
