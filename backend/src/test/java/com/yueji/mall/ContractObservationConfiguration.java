package com.yueji.mall;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.swagger.v3.oas.models.OpenAPI;
import java.io.*;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.web.client.RestTemplateCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.http.*;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.http.server.PathContainer;
import org.springframework.web.util.pattern.PathPatternParser;

/** 只记录操作编号、状态和测试来源，不保存凭据、请求体、响应业务数据或原始URL。 */
@TestConfiguration(proxyBeanMethods=false)
class ContractObservationConfiguration {
    private static synchronized void append(String record) throws IOException {
        var path=Path.of("../.artifacts/R-contract-observations.jsonl");Files.createDirectories(path.getParent());
        Files.writeString(path,record+"\n",StandardOpenOption.CREATE,StandardOpenOption.APPEND);
    }
    @Bean RestTemplateCustomizer observeContract(OpenAPI contract,ObjectMapper mapper) throws IOException {
        var schemas=new ResponseSchemaAssertions(mapper);
        return template->template.getInterceptors().add((request,body,execution)->{
            var response=execution.execute(request,body);
            var operation=contract.getPaths().entrySet().stream()
                    .filter(entry->PathPatternParser.defaultInstance.parse(entry.getKey()).matches(PathContainer.parsePath(request.getURI().getPath())))
                    .map(entry->entry.getValue().readOperationsMap().entrySet().stream()
                            .filter(method->method.getKey().name().equals(request.getMethod().name())).map(Map.Entry::getValue).findFirst().orElse(null))
                    .filter(Objects::nonNull).findFirst();
            if(operation.isEmpty()) return response;
            byte[] bytes=response.getBody().readAllBytes();
            String contractPath=contract.getPaths().entrySet().stream().filter(entry->entry.getValue().readOperations().contains(operation.get())).map(Map.Entry::getKey).findFirst().orElseThrow();
            try {schemas.check(contractPath,request.getMethod().name(),response.getStatusCode().value(),bytes);}
            catch(IOException | AssertionError failure) {response.close();throw failure;}
            String source=StackWalker.getInstance().walk(frames->frames.filter(frame->frame.getClassName().endsWith("Tests")
                    && !Set.of("call","data","error").contains(frame.getMethodName())).findFirst().map(Object::toString).orElse("test-helper"));
            String code="BINARY_OR_CHANNEL_PROTOCOL";
            if("API-35".equals(operation.get().getOperationId())) code="success".equals(new String(bytes,java.nio.charset.StandardCharsets.UTF_8).trim())?"CHANNEL_SUCCESS":"CHANNEL_REJECTED";
            if(response.getHeaders().getContentType()!=null && response.getHeaders().getContentType().isCompatibleWith(MediaType.APPLICATION_JSON))
                code=mapper.readTree(bytes).path("code").asText("MISSING_CODE");
            append(mapper.writeValueAsString(Map.of("operationId",operation.get().getOperationId(),"status",response.getStatusCode().value(),"code",code,"source",source,"observedAt",Instant.now().toString())));
            return new ClientHttpResponse() {
                public HttpStatusCode getStatusCode() throws IOException {return response.getStatusCode();}
                public String getStatusText() throws IOException {return response.getStatusText();}
                public HttpHeaders getHeaders() {return response.getHeaders();}
                public InputStream getBody() {return new ByteArrayInputStream(bytes);}
                public void close() {response.close();}
            };
        });
    }
}
