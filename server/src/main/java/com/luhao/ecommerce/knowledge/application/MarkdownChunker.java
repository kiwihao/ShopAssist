package com.luhao.ecommerce.knowledge.application;

import com.luhao.ecommerce.knowledge.domain.TextChunk;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/** ATX 标题分段 + Unicode 码点长度上限；不是完整 Markdown 渲染器或 token 分词器。 */
@Component
public class MarkdownChunker {
    private static final int MAX_CODE_POINTS = 800;
    private static final Pattern HEADING = Pattern.compile("^ {0,3}(#{1,6})\\s+(.+?)\\s*#*\\s*$");
    private static final Pattern FENCE = Pattern.compile("^ {0,3}(`{3,}|~{3,}).*$");

    public List<TextChunk> split(String source) {
        String[] lines = source.replace("\r\n", "\n").replace('\r', '\n').split("\n", -1);
        List<TextChunk> chunks = new ArrayList<>();
        String[] headings = new String[6];
        Arrays.fill(headings, "");
        String heading = "正文";
        StringBuilder body = new StringBuilder();
        int start = 1, end = 1, fenceSize = 0;
        char fenceChar = 0;
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            var title = HEADING.matcher(line);
            if (fenceSize == 0 && title.matches()) {
                emit(chunks, heading, start, end, body);
                int level = title.group(1).length() - 1;
                headings[level] = title.group(2);
                Arrays.fill(headings, level + 1, 6, "");
                heading = String.join(" / ", Arrays.stream(headings).filter(s -> !s.isEmpty()).toList());
                continue;
            }
            var fence = FENCE.matcher(line);
            if (fence.matches()) {
                String marker = fence.group(1);
                if (fenceSize == 0) {
                    fenceSize = marker.length();
                    fenceChar = marker.charAt(0);
                } else if (marker.charAt(0) == fenceChar && marker.length() >= fenceSize
                        && line.strip().substring(marker.length()).isBlank()) {
                    fenceSize = 0;
                }
            }
            // 保留精确行范围；同一超长行可对应多个片段。
            int offset = 0;
            if (line.isBlank() && body.isEmpty()) continue;
            do {
                int room = MAX_CODE_POINTS - body.codePointCount(0, body.length()) - (body.isEmpty() ? 0 : 1);
                if (room <= 0) {
                    emit(chunks, heading, start, end, body);
                    room = MAX_CODE_POINTS;
                }
                int count = Math.min(room, line.codePointCount(offset, line.length()));
                int next = line.offsetByCodePoints(offset, count);
                if (body.isEmpty()) start = i + 1;
                else body.append('\n');
                body.append(line, offset, next);
                end = i + 1;
                offset = next;
                if (offset < line.length()) emit(chunks, heading, start, end, body);
            } while (offset < line.length());
        }
        emit(chunks, heading, start, end, body);
        return chunks;
    }

    private void emit(List<TextChunk> chunks, String heading, int start, int end, StringBuilder body) {
        if (!body.toString().isBlank()) chunks.add(new TextChunk(chunks.size(), heading, start, end, body.toString()));
        body.setLength(0);
    }
}
