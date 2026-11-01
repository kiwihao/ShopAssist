package com.luhao.ecommerce.model.infrastructure.deepseek;

import com.fasterxml.jackson.databind.JsonNode;
import com.luhao.ecommerce.model.application.ChatClient;
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
public class DeepSeekChatClient implements ChatClient {
    private final RestClient client;
    private final String key, model;
    public DeepSeekChatClient(RestClient.Builder builder,
            @Value("${demo.chat.base-url}") String url,@Value("${demo.chat.api-key}") String key,
            @Value("${demo.chat.model}") String model) {
        this.key=key.strip(); this.model=model;
        var factory=new JdkClientHttpRequestFactory(HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build());
        factory.setReadTimeout(Duration.ofSeconds(60));
        client=builder.baseUrl(url.replaceAll("/+$", "")).requestFactory(factory).build();
    }
    @Override public String complete(String system,String user) {
        if(key.isBlank()) throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE,"CHAT_NOT_CONFIGURED","请在本机配置 DEEPSEEK_API_KEY 后重启服务");
        try {
            JsonNode result=client.post().uri("/chat/completions").header("Authorization","Bearer "+key)
                    .body(Map.of("model",model,"messages",List.of(Map.of("role","system","content",system),
                            Map.of("role","user","content",user)),"stream",false,"max_tokens",1200,
                            "thinking",Map.of("type","disabled"),"response_format",Map.of("type","json_object")))
                    .retrieve().body(JsonNode.class);
            if(result==null || !result.path("choices").path(0).path("message").path("content").isTextual()
                    || result.path("choices").path(0).path("message").path("content").asText().isBlank())
                throw invalid();
            return result.path("choices").path(0).path("message").path("content").asText();
        } catch(RestClientResponseException e) {
            throw new ApiException(HttpStatus.BAD_GATEWAY,"CHAT_HTTP_"+e.getStatusCode().value(),"DeepSeek 调用失败，请检查账户权限、余额或服务状态");
        } catch(ResourceAccessException e) {
            throw new ApiException(HttpStatus.GATEWAY_TIMEOUT,"CHAT_UNAVAILABLE","DeepSeek 连接失败或超时");
        } catch(RestClientException e) { throw invalid(); }
    }
    private ApiException invalid() { return new ApiException(HttpStatus.BAD_GATEWAY,"CHAT_INVALID_RESPONSE","聊天服务返回格式不符合要求"); }
}
