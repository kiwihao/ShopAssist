package com.luhao.ecommerce.product.application;

import com.luhao.ecommerce.shared.error.ApiException;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
public class ProductService {
    private final JdbcTemplate jdbc;
    public ProductService(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public List<Map<String, Object>> list() {
        return jdbc.queryForList("SELECT * FROM products WHERE status = 'ACTIVE' ORDER BY product_id");
    }

    public Map<String, Object> get(String productId) {
        var rows = jdbc.queryForList("SELECT * FROM products WHERE product_id = ? AND status = 'ACTIVE'", productId);
        if (rows.isEmpty()) throw new ApiException(HttpStatus.NOT_FOUND, "PRODUCT_NOT_FOUND", "商品不存在或已停用");
        return rows.getFirst();
    }
}
