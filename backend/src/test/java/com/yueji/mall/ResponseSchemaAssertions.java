package com.yueji.mall;

import com.fasterxml.jackson.databind.*;
import com.networknt.schema.*;
import java.util.concurrent.ConcurrentHashMap;

/** 在内存验证原始响应；失败只输出操作、字段路径与规则，不输出JWT、口令或业务值。 */
final class ResponseSchemaAssertions {
    private final ObjectMapper mapper;
    private final JsonNode specification;
    private final ConcurrentHashMap<String,JsonSchema> compiled=new ConcurrentHashMap<>();
    ResponseSchemaAssertions(ObjectMapper mapper) throws java.io.IOException {
        this.mapper=mapper;
        try(var source=getClass().getResourceAsStream("/contracts/openapi.json")) {specification=mapper.readTree(source);}
    }
    void check(String path,String method,int status,byte[] response) throws java.io.IOException {
        JsonNode operation=specification.path("paths").path(path).path(method.toLowerCase());
        JsonNode declaration=operation.path("responses").path(Integer.toString(status));
        if(declaration.isMissingNode()) throw new AssertionError(operation.path("operationId").asText()+": HTTP状态未在契约声明: "+status);
        JsonNode shape=declaration.path("content").path("application/json").path("schema");
        if(shape.isMissingNode()) return; // 文件字节和支付宝纯文本有各自协议断言。
        String key=operation.path("operationId").asText()+":"+status;
        JsonSchema schema=compiled.computeIfAbsent(key,ignored->{
            var document=mapper.createObjectNode();document.put("$schema","https://json-schema.org/draft/2020-12/schema");
            document.setAll((com.fasterxml.jackson.databind.node.ObjectNode)shape.deepCopy());
            document.set("$defs",specification.path("components").path("schemas"));
            JsonNode normalized;
            try {normalized=mapper.readTree(document.toString().replace("#/components/schemas/","#/$defs/"));}
            catch(java.io.IOException error) {throw new IllegalStateException("Invalid in-memory schema",error);}
            var configuration=new SchemaValidatorsConfig();configuration.setFormatAssertionsEnabled(true);
            return JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012).getSchema(normalized,configuration);
        });
        var failures=schema.validate(mapper.readTree(response));
        if(!failures.isEmpty()) throw new AssertionError(key+" response schema violations: "+failures.stream()
                .map(failure->failure.getInstanceLocation()+":"+failure.getType()).distinct().sorted().toList());
    }
}
