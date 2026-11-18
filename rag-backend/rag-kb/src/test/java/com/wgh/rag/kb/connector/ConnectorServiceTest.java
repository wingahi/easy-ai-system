package com.wgh.rag.kb.connector;

import com.wgh.rag.kb.entity.KbConnector;
import com.wgh.rag.kb.entity.KbConnectorRun;
import com.wgh.rag.kb.entity.KnowledgeFile;
import com.wgh.rag.kb.mapper.KbConnectorMapper;
import com.wgh.rag.kb.mapper.KbConnectorRunMapper;
import com.wgh.rag.kb.service.KnowledgeBaseService;
import com.wgh.rag.kb.service.KnowledgeFileService;
import com.wgh.rag.tool.security.SsrfBlockedException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 同步语义：内容未变不重复入库、内容变化产生新版本、源删除清理文件、SSRF 拒绝。
 */
class ConnectorServiceTest {

    private static final Long CONNECTOR_ID = 5L;
    private static final Long KB_ID = 1L;
    private static final String URL = "https://example.com/docs";

    private KbConnectorMapper connectorMapper;
    private KbConnectorRunMapper runMapper;
    private KnowledgeBaseService kbService;
    private KnowledgeFileService fileService;
    private UrlConnector urlConnector;
    private ConnectorService service;

    private KbConnector connector;

    @BeforeEach
    void setUp() {
        connectorMapper = mock(KbConnectorMapper.class);
        runMapper = mock(KbConnectorRunMapper.class);
        kbService = mock(KnowledgeBaseService.class);
        fileService = mock(KnowledgeFileService.class);
        urlConnector = mock(UrlConnector.class);

        ConnectorProperties properties = new ConnectorProperties();
        service = new ConnectorService(connectorMapper, runMapper, kbService, fileService, urlConnector, properties);

        connector = new KbConnector();
        connector.setId(CONNECTOR_ID);
        connector.setKnowledgeBaseId(KB_ID);
        connector.setName("示例页面");
        connector.setUrl(URL);
        connector.setSplitStrategy("PARAGRAPH");
        connector.setLastFileId(100L);
        when(connectorMapper.selectById(CONNECTOR_ID)).thenReturn(connector);
    }

    @Test
    void unchangedContentDoesNotCreateNewFile() {
        when(urlConnector.fetch(URL)).thenReturn(page("正文内容未变化"));
        KnowledgeFile existing = file(100L, 4);
        when(fileService.ingestContent(eq(KB_ID), anyString(), eq("txt"), any(byte[].class), any(), any()))
                .thenReturn(new KnowledgeFileService.IngestOutcome(existing, true));

        KbConnectorRun run = service.sync(CONNECTOR_ID);

        assertThat(run.getStatus()).isEqualTo(ConnectorService.STATUS_UNCHANGED);
        assertThat(run.getFileId()).isEqualTo(100L);
        verify(fileService, never()).delete(any());
        assertThat(connector.getLastFileId()).isEqualTo(100L);
        verify(connectorMapper).updateById(connector);
        verify(runMapper).insert(run);
    }

    @Test
    void changedContentCreatesNewFileAndRemovesOldVersion() {
        when(urlConnector.fetch(URL)).thenReturn(page("正文内容已更新"));
        KnowledgeFile created = file(200L, 0);
        when(fileService.ingestContent(eq(KB_ID), anyString(), eq("txt"), any(byte[].class), any(), any()))
                .thenReturn(new KnowledgeFileService.IngestOutcome(created, false));

        KbConnectorRun run = service.sync(CONNECTOR_ID);

        assertThat(run.getStatus()).isEqualTo(ConnectorService.STATUS_SUCCESS);
        assertThat(run.getFileId()).isEqualTo(200L);
        assertThat(run.getContentHash()).isEqualTo(sha256("正文内容已更新"));
        verify(fileService).delete(100L);
        assertThat(connector.getLastFileId()).isEqualTo(200L);
        assertThat(connector.getLastContentHash()).isEqualTo(sha256("正文内容已更新"));
    }

    @Test
    void missingSourcePageLogicallyDeletesFile() {
        when(urlConnector.fetch(URL)).thenReturn(new UrlConnector.FetchedPage(404, null, null, URL));

        KbConnectorRun run = service.sync(CONNECTOR_ID);

        assertThat(run.getStatus()).isEqualTo(ConnectorService.STATUS_REMOVED);
        assertThat(run.getHttpStatus()).isEqualTo(404);
        verify(fileService).delete(100L);
        assertThat(connector.getLastFileId()).isNull();
    }

    @Test
    void ssrfBlockedRunIsMarkedFailedWithoutIngest() {
        when(urlConnector.fetch(URL)).thenThrow(new SsrfBlockedException("目标地址不允许访问"));

        KbConnectorRun run = service.sync(CONNECTOR_ID);

        assertThat(run.getStatus()).isEqualTo(ConnectorService.STATUS_FAILED);
        assertThat(run.getMessage()).contains("目标地址不允许访问");
        assertThat(connector.getLastStatus()).isEqualTo(ConnectorService.STATUS_FAILED);
        verifyNoInteractions(fileService);
    }

    @Test
    void emptyBodyIsRejected() {
        when(urlConnector.fetch(URL)).thenReturn(page("   "));

        KbConnectorRun run = service.sync(CONNECTOR_ID);

        assertThat(run.getStatus()).isEqualTo(ConnectorService.STATUS_FAILED);
        assertThat(run.getMessage()).contains("正文为空");
        verifyNoInteractions(fileService);
    }

    @Test
    void syncRecordsRunWithHttpStatusAndDuration() {
        when(urlConnector.fetch(URL)).thenReturn(page("正文"));
        when(fileService.ingestContent(eq(KB_ID), anyString(), anyString(), any(byte[].class), any(), any()))
                .thenReturn(new KnowledgeFileService.IngestOutcome(file(300L, 2), false));

        ArgumentCaptor<KbConnectorRun> captor = ArgumentCaptor.forClass(KbConnectorRun.class);
        service.sync(CONNECTOR_ID);
        verify(runMapper).insert(captor.capture());

        KbConnectorRun saved = captor.getValue();
        assertThat(saved.getConnectorId()).isEqualTo(CONNECTOR_ID);
        assertThat(saved.getHttpStatus()).isEqualTo(200);
        assertThat(saved.getStartedAt()).isNotNull();
        assertThat(saved.getFinishedAt()).isNotNull();
        assertThat(saved.getDurationMs()).isNotNull();
    }

    private UrlConnector.FetchedPage page(String text) {
        return new UrlConnector.FetchedPage(200, "示例标题", text, URL);
    }

    private KnowledgeFile file(Long id, int chunkCount) {
        KnowledgeFile file = new KnowledgeFile();
        file.setId(id);
        file.setChunkCount(chunkCount);
        return file;
    }

    private static String sha256(String text) {
        try {
            var digest = java.security.MessageDigest.getInstance("SHA-256");
            return java.util.HexFormat.of().formatHex(digest.digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
