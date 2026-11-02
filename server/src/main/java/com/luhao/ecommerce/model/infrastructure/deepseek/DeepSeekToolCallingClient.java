package com.luhao.ecommerce.model.infrastructure.deepseek;

import com.fasterxml.jackson.databind.JsonNode;
import com.luhao.ecommerce.model.application.ToolCallingClient;
import com.luhao.ecommerce.shared.error.ApiException;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.*;

@Component
public class DeepSeekToolCallingClient implements ToolCallingClient {
    private final RestClient client;
    private final String key, model;
    public DeepSeekToolCallingClient(RestClient.Builder builder,
            @Value("${demo.chat.base-url}") String url, @Value("${demo.chat.api-key}") String key,
            @Value("${demo.chat.model}") String model) {
        this.key=key.strip(); this.model=model;
        var factory=new JdkClientHttpRequestFactory(HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build());
        factory.setReadTimeout(Duration.ofSeconds(20));
        client=builder.baseUrl(url.replaceAll("/+$", "")).requestFactory(factory).build();
    }
    @Override public JsonNode next(List<Map<String,Object>> messages,List<Map<String,Object>> tools) {
        if(key.isBlank()) throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE,"CHAT_NOT_CONFIGURED","请配置 DEEPSEEK_API_KEY 后重启服务");
        try {
            var response=client.post().uri("/chat/completions").header("Authorization","Bearer "+key)
                    .body(Map.of("model",model,"messages",messages,"tools",tools,"tool_choice","auto",
                            "thinking",Map.of("type","disabled"),"max_tokens",800,"stream",false))
                    .retrieve().body(JsonNode.class);
            if(response==null || !response.path("choices").path(0).path("message").isObject())
                throw new ApiException(HttpStatus.BAD_GATEWAY,"AGENT_MODEL_INVALID","工具编排模型返回格式异常");
            return response.path("choices").path(0).path("message");
        } catch(RestClientResponseException e) {
            throw new ApiException(HttpStatus.BAD_GATEWAY,"CHAT_HTTP_"+e.getStatusCode().value(),"工具编排模型调用失败");
        } catch(ResourceAccessException e) {
            throw new ApiException(HttpStatus.GATEWAY_TIMEOUT,"AGENT_MODEL_TIMEOUT","工具编排模型连接失败或超时");
        } catch(RestClientException e) {
            throw new ApiException(HttpStatus.BAD_GATEWAY,"AGENT_MODEL_INVALID","工具编排模型返回格式异常");
        }
    }
}
