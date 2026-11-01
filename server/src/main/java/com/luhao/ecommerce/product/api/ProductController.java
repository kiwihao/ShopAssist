package com.luhao.ecommerce.product.api;

import com.luhao.ecommerce.product.application.ProductService;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/products")
public class ProductController {
    private final ProductService products;
    public ProductController(ProductService products) { this.products = products; }
    @GetMapping public List<Map<String, Object>> list() { return products.list(); }
    @GetMapping("/{id}") public Map<String, Object> get(@PathVariable String id) { return products.get(id); }
}
