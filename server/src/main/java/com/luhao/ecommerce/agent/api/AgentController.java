package com.luhao.ecommerce.agent.api;

import com.luhao.ecommerce.agent.application.AgentService;
import java.util.Map;
import org.springframework.web.bind.annotation.*;

@RestController
public class AgentController {
    private final AgentService service;
    public AgentController(AgentService service) { this.service=service; }
    @PostMapping("/api/agent") public Map<String,Object> run(@RequestBody Request request) {
        return service.run(request.productId(),request.message());
    }
    public record Request(String productId,String message) {}
}
