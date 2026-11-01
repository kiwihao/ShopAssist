package com.luhao.ecommerce.recommendation.infrastructure;

import com.luhao.ecommerce.recommendation.domain.RecommendationCriteria;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class RecommendationRepository {
    private final JdbcTemplate jdbc;
    public RecommendationRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public List<Map<String, Object>> filter(RecommendationCriteria criteria) {
        var sql = new StringBuilder("SELECT * FROM products WHERE status = 'ACTIVE' AND category = '背包'");
        var args = new ArrayList<Object>();
        if (criteria.minPriceCny() != null) {
            sql.append(criteria.minPriceInclusive() ? " AND price_cny >= ?" : " AND price_cny > ?");
            args.add(criteria.minPriceCny());
        }
        if (criteria.maxPriceCny() != null) {
            sql.append(criteria.maxPriceInclusive() ? " AND price_cny <= ?" : " AND price_cny < ?");
            args.add(criteria.maxPriceCny());
        }
        if (criteria.laptop() != null) {
            var size = criteria.laptop();
            // Allow rotating the computer within the compartment plane, never rotate thickness.
            sql.append(" AND GREATEST(laptop_height_cm, laptop_width_cm) >= ? AND LEAST(laptop_height_cm, laptop_width_cm) >= ? AND laptop_depth_cm >= ?");
            args.add(size.lengthCm().max(size.widthCm()));
            args.add(size.lengthCm().min(size.widthCm()));
            args.add(size.thicknessCm());
        }
        if (criteria.machineWashRequired()) sql.append(" AND machine_washable IS TRUE");
        sql.append(" ORDER BY price_cny ASC, product_id ASC LIMIT 10");
        return jdbc.queryForList(sql.toString(), args.toArray());
    }

    /** Exact product/version lookup: evidence is not selected by cross-product similarity. */
    public List<Map<String, Object>> evidence(String productId) {
        return jdbc.queryForList("""
                SELECT c.chunk_id, d.product_id, d.filename, v.version, c.heading, c.line_start, c.line_end, c.content
                FROM documents d JOIN document_versions v ON v.version_id = d.active_version_id AND v.document_id = d.document_id
                JOIN ingestion_jobs j ON j.version_id = v.version_id AND j.status = 'READY'
                JOIN chunks c ON c.version_id = v.version_id
                WHERE d.product_id = ? ORDER BY d.filename, c.ordinal LIMIT 30
                """, productId);
    }
}
