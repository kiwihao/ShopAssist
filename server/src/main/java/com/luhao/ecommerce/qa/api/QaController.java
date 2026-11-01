package com.luhao.ecommerce.qa.api;

import com.luhao.ecommerce.qa.application.QaService;
import java.util.Map;
import org.springframework.web.bind.annotation.*;

@RestController
public class QaController {
    private final QaService service;
    public QaController(QaService service) { this.service=service; }
    @PostMapping("/api/qa") public Map<String,Object> answer(@RequestBody Question request) {
        return service.answer(request.productId(),request.question());
    }
    public record Question(String productId,String question) {}
}
