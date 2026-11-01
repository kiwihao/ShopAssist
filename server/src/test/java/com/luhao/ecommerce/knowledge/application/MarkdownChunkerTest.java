package com.luhao.ecommerce.knowledge.application;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class MarkdownChunkerTest {
    private final MarkdownChunker chunker = new MarkdownChunker();

    @Test void preservesHeadingHierarchyAndOriginalLines() {
        var chunks = chunker.split("# 包\r\n\r\n## 尺寸\r\n高 36 cm\r\n\r\n## 清洗\r\n不可机洗");
        assertThat(chunks).hasSize(2);
        assertThat(chunks.getFirst().heading()).isEqualTo("包 / 尺寸");
        assertThat(chunks.getFirst().lineStart()).isEqualTo(4);
        assertThat(chunks.getLast().lineEnd()).isEqualTo(7);
        assertThat(chunks.getLast().heading()).isEqualTo("包 / 清洗");
        assertThat(chunks.getLast().content()).isEqualTo("不可机洗");
    }

    @Test void doesNotTreatFencedCodeAsHeadings() {
        var chunks = chunker.split("# 说明\n```text\n## 代码中的标题\n```\n正文");
        assertThat(chunks).hasSize(1);
        assertThat(chunks.getFirst().heading()).isEqualTo("说明");
        assertThat(chunks.getFirst().content()).contains("## 代码中的标题");
    }

    @Test void boundsLongLinesWithoutSplittingUnicodeCharacters() {
        String source = "😀".repeat(1700);
        var chunks = chunker.split(source);
        assertThat(chunks).hasSize(3);
        assertThat(String.join("", chunks.stream().map(c -> c.content()).toList())).isEqualTo(source);
        assertThat(chunks).allSatisfy(c -> {
            assertThat(c.content().codePointCount(0, c.content().length())).isLessThanOrEqualTo(800);
            assertThat(c.lineStart()).isEqualTo(1);
            assertThat(c.lineEnd()).isEqualTo(1);
        });
    }

    @Test void headingOnlyOrBlankInputHasNoContent() {
        assertThat(chunker.split("# 说明\n\n## 尺寸\n")).isEmpty();
        assertThat(chunker.split(" \n\n")).isEmpty();
    }

    @Test void contentLinesFitEvenWhenTheNextNewlineWouldExceedTheLimit() {
        var chunks = chunker.split("甲".repeat(800) + "\n" + "乙".repeat(800));
        assertThat(chunks).hasSize(2);
        assertThat(chunks.get(0).content()).isEqualTo("甲".repeat(800));
        assertThat(chunks.get(1).lineStart()).isEqualTo(2);
    }
}
