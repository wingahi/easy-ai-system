package com.wgh.rag.controller.kb;

import com.wgh.rag.auth.constant.AuthConstants;
import com.wgh.rag.common.result.Result;
import com.wgh.rag.common.security.RequiresPermission;
import com.wgh.rag.kb.connector.ConnectorService;
import com.wgh.rag.kb.entity.KbConnector;
import com.wgh.rag.kb.entity.KbConnectorRun;
import com.wgh.rag.log.annotation.Audit;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 知识库 URL / HTTP 数据源连接器：配置 CRUD、手动同步、运行记录查询
 */
@Tag(name = "知识库数据源连接器")
@RestController
@RequestMapping("/api/kb/{kbId}/connectors")
@RequiredArgsConstructor
@RequiresPermission(AuthConstants.PERM_KB_READ)
public class ConnectorController {

    private final ConnectorService connectorService;

    @Operation(summary = "列出知识库连接器")
    @GetMapping
    public Result<List<KbConnector>> list(@PathVariable Long kbId) {
        return Result.success(connectorService.list(kbId));
    }

    @Operation(summary = "新建 URL 连接器")
    @PostMapping
    @Audit(action = "KB_CONNECTOR_CREATE", resourceType = "KB_CONNECTOR")
    @RequiresPermission(AuthConstants.PERM_KB_WRITE)
    public Result<KbConnector> create(@PathVariable Long kbId,
                                      @RequestBody ConnectorService.ConnectorPayload payload) {
        return Result.success(connectorService.create(kbId, payload));
    }

    @Operation(summary = "编辑 URL 连接器")
    @PutMapping("/{id}")
    @Audit(action = "KB_CONNECTOR_UPDATE", resourceType = "KB_CONNECTOR")
    @RequiresPermission(AuthConstants.PERM_KB_WRITE)
    public Result<KbConnector> update(@PathVariable Long kbId, @PathVariable Long id,
                                      @RequestBody ConnectorService.ConnectorPayload payload) {
        return Result.success(connectorService.update(kbId, id, payload));
    }

    @Operation(summary = "删除 URL 连接器（运行记录保留归档）")
    @DeleteMapping("/{id}")
    @Audit(action = "KB_CONNECTOR_DELETE", resourceType = "KB_CONNECTOR")
    @RequiresPermission(AuthConstants.PERM_KB_DELETE)
    public Result<Void> delete(@PathVariable Long kbId, @PathVariable Long id) {
        connectorService.delete(kbId, id);
        return Result.success();
    }

    @Operation(summary = "立即同步一次（内容未变不重复入库）")
    @PostMapping("/{id}/sync")
    @Audit(action = "KB_CONNECTOR_SYNC", resourceType = "KB_CONNECTOR")
    @RequiresPermission(AuthConstants.PERM_KB_WRITE)
    public Result<KbConnectorRun> sync(@PathVariable Long kbId, @PathVariable Long id) {
        return Result.success(connectorService.sync(kbId, id));
    }

    @Operation(summary = "查询连接器运行记录")
    @GetMapping("/{id}/runs")
    public Result<List<KbConnectorRun>> runs(@PathVariable Long kbId, @PathVariable Long id,
                                             @RequestParam(defaultValue = "20") Integer limit) {
        return Result.success(connectorService.listRuns(kbId, id, limit));
    }
}
