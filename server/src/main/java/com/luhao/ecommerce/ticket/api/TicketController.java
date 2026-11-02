package com.luhao.ecommerce.ticket.api;

import com.luhao.ecommerce.ticket.application.TicketService;
import java.util.Map;
import java.util.UUID;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/ticket-drafts")
public class TicketController {
    private final TicketService service;
    public TicketController(TicketService service) { this.service=service; }
    @PostMapping public Map<String,Object> prepare(@RequestBody Draft request) { return service.prepare(request.productId(),request.summary()); }
    @PutMapping("/{id}") public Map<String,Object> edit(@PathVariable UUID id,@RequestBody Edit request) { return service.edit(id,request.version(),request.confirmationToken(),request.summary()); }
    @PostMapping("/{id}/cancel") public Map<String,Object> cancel(@PathVariable UUID id,@RequestBody Edit request) { return service.cancel(id,request.version(),request.confirmationToken()); }
    @PostMapping("/{id}/confirm") public Map<String,Object> confirm(@PathVariable UUID id,@RequestHeader(value="Idempotency-Key",required=false) String key,@RequestBody Confirmation request) { return service.confirm(id,request.version(),request.confirmationToken(),request.confirmed(),key); }
    public record Draft(String productId,String summary) {}
    public record Edit(Integer version,String confirmationToken,String summary) {}
    public record Confirmation(Integer version,String confirmationToken,Boolean confirmed) {}
}
