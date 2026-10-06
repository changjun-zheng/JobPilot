package com.jobpilot.controller;

import com.jobpilot.ai.Citation;
import com.jobpilot.ai.RetrievalQuery;
import com.jobpilot.ai.RetrievalResult;
import com.jobpilot.ai.RetrievedChunk;
import com.jobpilot.common.ApiResponse;
import com.jobpilot.domain.KbDocumentEntity;
import com.jobpilot.knowledge.DocumentIngestService;
import com.jobpilot.knowledge.IngestCommand;
import com.jobpilot.knowledge.KnowledgeRetrievalService;
import com.jobpilot.knowledge.RagAskService;
import com.jobpilot.security.UserContext;
import com.jobpilot.usage.UsageScenario;
import jakarta.validation.constraints.NotBlank;
import org.springframework.http.HttpStatus;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.util.List;

/**
 * I-1 账号与租户隔离 API：user_id 由服务端从认证上下文解析，客户端不得传入或覆盖。
 */
@RestController
@RequestMapping("/api/v1/knowledge")
@Validated
public class KnowledgeController {

    private final DocumentIngestService ingestService;
    private final KnowledgeRetrievalService retrievalService;
    private final RagAskService askService;

    public KnowledgeController(DocumentIngestService ingestService,
                               KnowledgeRetrievalService retrievalService,
                               RagAskService askService) {
        this.ingestService = ingestService;
        this.retrievalService = retrievalService;
        this.askService = askService;
    }

    public record IngestRequest(
            @NotBlank String name,
            String docType,
            String tags,
            String content // 空白内容按 PRD-FP-1.1 进入 FAILED 状态，而非 400
    ) {
    }

    public record IngestResponse(String documentId, String status, Integer chunkCount, String errorMessage) {

        static IngestResponse from(KbDocumentEntity doc) {
            return new IngestResponse(doc.getId(), doc.getStatus(), doc.getChunkCount(), doc.getErrorMessage());
        }
    }

    public record SearchRequest(
            @NotBlank String query,
            Integer topK,
            String docType
    ) {
    }

    public record SearchItem(String chunkId, String documentId, String text, double score, Citation citation) {
    }

    public record SearchResponse(List<SearchItem> items, String searchMode, boolean degraded) {

        static SearchResponse from(RetrievalResult result) {
            return new SearchResponse(
                    result.items().stream()
                            .map(c -> new SearchItem(c.chunkId(), c.documentId(), c.text(), c.score(), c.citation()))
                            .toList(),
                    result.searchMode().name(),
                    result.degraded());
        }
    }

    public record AskRequest(
            @NotBlank String question,
            Integer topK,
            String docType
    ) {
    }

    public record AskResponse(String answer, List<Citation> citations, String searchMode, boolean degraded) {
    }

    public record DocumentResponse(String id, String name, String docType, String status,
                                   Integer indexVersion, Integer chunkCount, String errorMessage,
                                   LocalDateTime createdAt) {

        static DocumentResponse from(KbDocumentEntity doc) {
            return new DocumentResponse(doc.getId(), doc.getName(), doc.getDocType(),
                    doc.getStatus(), doc.getIndexVersion(), doc.getChunkCount(),
                    doc.getErrorMessage(), doc.getCreatedAt());
        }
    }

    /** 入队导入（I-1c）：落 PENDING 即返回 202，索引由后台 worker 执行；进度用 GET /documents/{id} 查询 */
    @ResponseStatus(HttpStatus.ACCEPTED)
    @PostMapping("/documents")
    public ApiResponse<IngestResponse> ingest(@RequestBody @Validated IngestRequest request) {
        String docType = request.docType() == null || request.docType().isBlank()
                ? guessDocType(request.name())
                : request.docType().toUpperCase();
        KbDocumentEntity doc = ingestService.enqueue(new IngestCommand(
                UserContext.require(), request.name(), docType, request.tags(), request.content()));
        return ApiResponse.ok(IngestResponse.from(doc));
    }

    /** 重排既有文档（重导）：READY / FAILED 可重排，同 index_version 的 upsert 覆盖不产生孤儿向量 */
    @ResponseStatus(HttpStatus.ACCEPTED)
    @PostMapping("/documents/{id}/reindex")
    public ApiResponse<DocumentResponse> reindex(@PathVariable String id) {
        return ApiResponse.ok(DocumentResponse.from(ingestService.reindex(id)));
    }

    /** 文档列表（PRD-FP-6 知识库页）：status 可选，按导入时间倒序 */
    @GetMapping("/documents")
    public ApiResponse<List<DocumentResponse>> documents(
            @RequestParam(required = false) String status,
            @RequestParam(required = false, defaultValue = "0") int limit) {
        return ApiResponse.ok(ingestService.list(status, limit).stream()
                .map(DocumentResponse::from).toList());
    }

    /** 索引状态查询（PRD：导入成功只代表任务创建，状态必须可查） */
    @GetMapping("/documents/{id}")
    public ApiResponse<DocumentResponse> document(@PathVariable String id) {
        return ApiResponse.ok(DocumentResponse.from(ingestService.document(id)));
    }

    /** 删除文档：级联删除向量与 Chunk（NFR-6 删除一致性）；跨租户与不存在均 404 */
    @DeleteMapping("/documents/{id}")
    public ApiResponse<Void> delete(@PathVariable String id) {
        ingestService.delete(id);
        return ApiResponse.ok(null);
    }

    /** 纯检索（不带生成），降级信息透传 */
    @PostMapping("/search")
    public ApiResponse<SearchResponse> search(@RequestBody @Validated SearchRequest request) {
        RetrievalResult result = retrievalService.search(new RetrievalQuery(
                UserContext.require(), request.query(),
                request.topK() == null ? 0 : request.topK(), request.docType()), UsageScenario.SEARCH);
        return ApiResponse.ok(SearchResponse.from(result));
    }

    /** 引用问答：回答 + 引用列表 + 检索模式 */
    @PostMapping("/ask")
    public ApiResponse<AskResponse> ask(@RequestBody @Validated AskRequest request) {
        RagAskService.AskAnswer answer = askService.ask(
                UserContext.require(), request.question(),
                request.topK() == null ? 0 : request.topK(), request.docType());
        List<Citation> citations = answer.evidence().stream().map(RetrievedChunk::citation).toList();
        return ApiResponse.ok(new AskResponse(answer.answer(), citations,
                answer.retrieval().searchMode().name(), answer.retrieval().degraded()));
    }

    private String guessDocType(String name) {
        String lower = name.toLowerCase();
        return lower.endsWith(".md") || lower.endsWith(".markdown") ? "MARKDOWN" : "PLAIN_TEXT";
    }
}
