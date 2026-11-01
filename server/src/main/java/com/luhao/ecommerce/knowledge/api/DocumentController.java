package com.luhao.ecommerce.knowledge.api;

import com.luhao.ecommerce.knowledge.application.IngestionService;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/admin")
public class DocumentController {
    private final IngestionService service;
    public DocumentController(IngestionService service) {
        this.service = service;
    }

    @PostMapping(value = "/documents", consumes = "multipart/form-data")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public Map<String, Object> upload(@RequestParam String productId, @RequestParam int version,
            @RequestPart MultipartFile file) {
        return service.submit(productId, version, file);
    }

    @GetMapping("/ingestion-jobs/{id}")
    public Map<String, Object> job(@PathVariable UUID id) { return service.job(id); }

    @GetMapping("/ingestion-jobs/{id}/chunks")
    public List<Map<String, Object>> chunks(@PathVariable UUID id) { return service.chunks(id); }
}
