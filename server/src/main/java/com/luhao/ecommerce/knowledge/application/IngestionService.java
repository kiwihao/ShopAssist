package com.luhao.ecommerce.knowledge.application;

import com.luhao.ecommerce.knowledge.infrastructure.IngestionRepository;
import com.luhao.ecommerce.shared.error.ApiException;
import com.luhao.ecommerce.product.application.ProductService;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Map;
import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

@Service
public class IngestionService {
    private final ProductService products;
    private final IngestionRepository repository;
    public IngestionService(ProductService products, IngestionRepository repository) {
        this.products = products;
        this.repository = repository;
    }

    public Map<String, Object> job(UUID jobId) { return repository.job(jobId); }

    public List<Map<String, Object>> chunks(UUID jobId) { return repository.chunks(jobId); }

    public Map<String, Object> submit(String productId, int version, MultipartFile file) {
        products.get(productId);
        if (version < 1) throw invalid("版本号必须为正整数");
        String filename = file.getOriginalFilename();
        if (filename == null || filename.length() > 160 || filename.contains("/") || filename.contains("\\")
                || filename.chars().anyMatch(Character::isISOControl)
                || !filename.toLowerCase(Locale.ROOT).endsWith(".md"))
            throw invalid("仅支持文件名不含路径的 .md 文件");
        if (file.isEmpty()) throw invalid("文件不能为空");
        if (file.getSize() > 256 * 1024) throw new ApiException(HttpStatus.PAYLOAD_TOO_LARGE, "FILE_TOO_LARGE", "文件不能超过 256KB");
        try {
            byte[] bytes = file.getBytes();
            String source = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
            if (source.startsWith("\uFEFF")) source = source.substring(1);
            if (source.isBlank() || source.indexOf('\0') >= 0) throw invalid("文件必须为非空 UTF-8 文本");
            checkMetadata(source, "product_id", productId);
            checkMetadata(source, "document_version", String.valueOf(version));
            String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
            UUID jobId = repository.submit(productId, filename, version, source, hash);
            return Map.of("jobId", jobId, "statusUrl", "/api/admin/ingestion-jobs/" + jobId);
        } catch (IOException e) {
            throw invalid("无法读取文件，请使用 UTF-8 编码");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private void checkMetadata(String source, String key, String expected) {
        var matcher = Pattern.compile("(?m)^- " + key + ":\\s*([^\\r\\n]+)$").matcher(source);
        while (matcher.find()) {
            if (!matcher.group(1).strip().equals(expected)) throw invalid("文件中的 " + key + " 与上传参数不一致");
        }
    }

    private ApiException invalid(String message) {
        return new ApiException(HttpStatus.BAD_REQUEST, "INVALID_DOCUMENT", message);
    }
}
