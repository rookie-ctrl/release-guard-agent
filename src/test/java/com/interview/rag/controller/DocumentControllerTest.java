package com.interview.rag.controller;

import com.interview.rag.domain.DocumentEntity;
import com.interview.rag.domain.ParseStatus;
import com.interview.rag.exception.GlobalExceptionHandler;
import com.interview.rag.mq.ParseMessageProducer;
import com.interview.rag.repository.DocumentRepository;
import com.interview.rag.service.VectorIndexService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.HttpHeaders;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class DocumentControllerTest {

    @TempDir
    Path directory;

    private Path storage;
    private DocumentRepository repository;
    private ParseMessageProducer producer;
    private VectorIndexService index;
    private DocumentController controller;
    private MockMvc mvc;

    @BeforeEach
    void setUp() throws Exception {
        storage = Files.createDirectory(directory.resolve("documents"));
        repository = mock(DocumentRepository.class);
        producer = mock(ParseMessageProducer.class);
        index = mock(VectorIndexService.class);
        controller = new DocumentController(repository, producer, index, storage.toString());
        mvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new GlobalExceptionHandler()).build();
    }

    @Test
    void previewsMarkdownBeforeIndexingWithoutCallingEmbeddingPipeline() throws Exception {
        DocumentEntity document = document("policy.md", "# 发布规范\n\n必须使用参数化查询。\n");
        document.setStatus(ParseStatus.PENDING);
        var content = controller.content(1L);
        assertThat(content.id()).isEqualTo(1L);
        assertThat(content.format()).isEqualTo("markdown");
        assertThat(content.content()).contains("# 发布规范", "必须使用参数化查询");
        assertThat(content.truncated()).isFalse();
        verifyNoInteractions(producer, index);
    }

    @Test
    void previewsOriginalTextWhenIndexingFailed() throws Exception {
        DocumentEntity document = document("runbook.txt", "Rollback the deployment before retrying.\n");
        document.setStatus(ParseStatus.FAILED);
        var content = controller.content(1L);
        assertThat(content.format()).isEqualTo("text");
        assertThat(content.content()).contains("Rollback the deployment");
        assertThat(content.truncated()).isFalse();
    }

    @Test
    void boundsLongPreviewAndMarksOmittedContent() throws Exception {
        document("large.txt", "release policy\n".repeat(8000));
        var content = controller.content(1L);
        assertThat(content.content()).hasSize(100000);
        assertThat(content.truncated()).isTrue();
    }

    @Test
    void extractsWordDocumentText() throws Exception {
        Path file = storage.resolve("policy.docx");
        try (ZipOutputStream archive = new ZipOutputStream(Files.newOutputStream(file))) {
            entry(archive, "[Content_Types].xml", """
                    <?xml version="1.0" encoding="UTF-8"?>
                    <Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">
                      <Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>
                      <Default Extension="xml" ContentType="application/xml"/>
                      <Override PartName="/word/document.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml"/>
                    </Types>
                    """);
            entry(archive, "_rels/.rels", """
                    <?xml version="1.0" encoding="UTF-8"?>
                    <Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
                      <Relationship Id="document" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="word/document.xml"/>
                    </Relationships>
                    """);
            entry(archive, "word/document.xml", """
                    <?xml version="1.0" encoding="UTF-8"?>
                    <w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main">
                      <w:body><w:p><w:r><w:t>ReleaseGuard rollback policy</w:t></w:r></w:p></w:body>
                    </w:document>
                    """);
        }
        savedDocument(file, "policy.docx");
        assertThat(controller.content(1L).content()).contains("ReleaseGuard rollback policy");
    }

    @Test
    void downloadsExactOriginalBytesAsAttachment() throws Exception {
        String original = "# 发布规范\n禁止 SQL 拼接。\n";
        document("发布规范.md", original);
        var response = mvc.perform(get("/api/documents/1/download"))
                .andExpect(status().isOk()).andReturn().getResponse();
        assertThat(response.getContentAsByteArray()).isEqualTo(original.getBytes(StandardCharsets.UTF_8));
        assertThat(response.getHeader(HttpHeaders.CONTENT_DISPOSITION)).contains("attachment", "UTF-8");
        assertThat(response.getHeader(HttpHeaders.CONTENT_TYPE)).isEqualTo("application/octet-stream");
        assertThat(response.getHeader(HttpHeaders.CACHE_CONTROL)).contains("no-store");
        assertThat(response.getHeader("X-Content-Type-Options")).isEqualTo("nosniff");
    }

    @Test
    void returnsNotFoundForUnknownDocument() throws Exception {
        when(repository.findById(99L)).thenReturn(Optional.empty());
        mvc.perform(get("/api/documents/99/content")).andExpect(status().isNotFound());
        mvc.perform(get("/api/documents/99/download")).andExpect(status().isNotFound());
    }

    @Test
    void refusesReadingFilesOutsideDocumentStorage() throws Exception {
        Path outside = Files.writeString(directory.resolve("outside.txt"), "not a knowledge document");
        savedDocument(outside, "outside.txt");
        mvc.perform(get("/api/documents/1/content")).andExpect(status().isNotFound());
        mvc.perform(get("/api/documents/1/download")).andExpect(status().isNotFound());
    }

    @Test
    void reportsMissingOriginalFile() throws Exception {
        savedDocument(storage.resolve("missing.md"), "missing.md");
        assertThatThrownBy(() -> controller.content(1L)).hasMessageContaining("原文件不存在");
        mvc.perform(get("/api/documents/1/download")).andExpect(status().isNotFound());
    }

    private DocumentEntity document(String filename, String text) throws Exception {
        Path file = Files.writeString(storage.resolve(filename), text, StandardCharsets.UTF_8);
        return savedDocument(file, filename);
    }

    private DocumentEntity savedDocument(Path file, String filename) {
        DocumentEntity document = new DocumentEntity();
        document.setId(1L);
        document.setDocName(filename);
        document.setFilePath(file.toString());
        when(repository.findById(1L)).thenReturn(Optional.of(document));
        return document;
    }

    private void entry(ZipOutputStream archive, String filename, String content) throws Exception {
        archive.putNextEntry(new ZipEntry(filename));
        archive.write(content.getBytes(StandardCharsets.UTF_8));
        archive.closeEntry();
    }
}
