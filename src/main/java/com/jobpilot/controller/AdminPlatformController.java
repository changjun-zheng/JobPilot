package com.jobpilot.controller;

import com.jobpilot.common.ApiResponse;
import com.jobpilot.domain.KbDocumentEntity;
import com.jobpilot.domain.PlatformCompanyEntity;
import com.jobpilot.domain.PlatformPositionEntity;
import com.jobpilot.platform.PlatformAdminService;
import com.jobpilot.platform.PlatformCatalogService;
import com.jobpilot.platform.PlatformDocumentService;
import jakarta.validation.constraints.NotBlank;
import org.springframework.http.HttpStatus;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 平台内容管理（**管理端**，第二认证轴）：公司 / 岗位 / 面经的写入口与下架。
 * <p>
 * 全部在 {@code /api/v1/admin/**} 下——由 {@code AdminAuthInterceptor} 用**管理令牌**把关，
 * 与用户侧 {@code /api/v1/companies}（只读）是两条独立的门。管理请求不写 {@code UserContext}，
 * 因此这里触不到任何租户表（写了会被拦截器 fail-closed 炸掉）。
 * <p>
 * <b>管理员看不到用户业务内容</b>（设计草案 §1#4 红线）：本控制器只碰 platform_* 与
 * {@code kb_document} 的 PLATFORM 行，没有读用户简历/问答/记忆的接口。
 */
@RestController
@RequestMapping("/api/v1/admin/platform")
@Validated
public class AdminPlatformController {

    private final PlatformAdminService adminService;
    private final PlatformDocumentService documentService;
    private final PlatformCatalogService catalogService;

    public AdminPlatformController(PlatformAdminService adminService,
                                   PlatformDocumentService documentService,
                                   PlatformCatalogService catalogService) {
        this.adminService = adminService;
        this.documentService = documentService;
        this.catalogService = catalogService;
    }

    // ── 视图 ────────────────────────────────────────────────────

    public record CompanyView(String id, String name, String tier, String industry, String tags,
                              String status) {

        static CompanyView from(PlatformCompanyEntity c) {
            return new CompanyView(c.getId(), c.getName(), c.getTier(), c.getIndustry(), c.getTags(),
                    c.getStatus());
        }
    }

    public record PositionView(String id, String name) {

        static PositionView from(PlatformPositionEntity p) {
            return new PositionView(p.getId(), p.getName());
        }
    }

    public record DocumentView(String id, String name, String docType, String tags, String sourceNote,
                               String companyId, String status, Integer indexVersion,
                               Integer chunkCount, String errorMessage, LocalDateTime createdAt) {

        static DocumentView from(KbDocumentEntity d) {
            return new DocumentView(d.getId(), d.getName(), d.getDocType(), d.getTags(), d.getSourceNote(),
                    d.getCompanyId(), d.getStatus(), d.getIndexVersion(), d.getChunkCount(),
                    d.getErrorMessage(), d.getCreatedAt());
        }
    }

    // ── 公司 ────────────────────────────────────────────────────

    /** 公司列表，**含已下架**（管理视角需要看到全量；用户侧目录恒只返回在架） */
    @GetMapping("/companies")
    public ApiResponse<List<CompanyView>> companies() {
        return ApiResponse.ok(adminService.listCompanies().stream().map(CompanyView::from).toList());
    }

    public record CompanyRequest(@NotBlank String name, @NotBlank String tier,
                                 String industry, String tags) {
    }

    /** 部分更新：字段可空，只覆盖传了的 */
    public record CompanyUpdateRequest(String name, String tier, String industry, String tags) {
    }

    @PostMapping("/companies")
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<CompanyView> createCompany(@RequestBody @Validated CompanyRequest request) {
        return ApiResponse.ok(CompanyView.from(adminService.createCompany(
                request.name(), request.tier(), request.industry(), request.tags())));
    }

    /** 部分更新：只覆盖传了的字段 */
    @PutMapping("/companies/{id}")
    public ApiResponse<CompanyView> updateCompany(@PathVariable String id,
                                                  @RequestBody CompanyUpdateRequest request) {
        return ApiResponse.ok(CompanyView.from(adminService.updateCompany(
                id, request.name(), request.tier(), request.industry(), request.tags())));
    }

    /** 下架：用户侧目录与面试选司立刻不可见；历史面试靠快照照常可读 */
    @PostMapping("/companies/{id}/archive")
    public ApiResponse<Void> archiveCompany(@PathVariable String id) {
        adminService.archiveCompany(id);
        return ApiResponse.ok(null);
    }

    @PostMapping("/companies/{id}/activate")
    public ApiResponse<CompanyView> activateCompany(@PathVariable String id) {
        return ApiResponse.ok(CompanyView.from(adminService.activateCompany(id)));
    }

    // ── 岗位与关联 ──────────────────────────────────────────────

    @GetMapping("/positions")
    public ApiResponse<List<PositionView>> positions() {
        return ApiResponse.ok(catalogService.positions().stream().map(PositionView::from).toList());
    }

    public record PositionRequest(@NotBlank String name) {
    }

    @PostMapping("/positions")
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<PositionView> createPosition(@RequestBody @Validated PositionRequest request) {
        return ApiResponse.ok(PositionView.from(adminService.createPosition(request.name())));
    }

    @GetMapping("/companies/{id}/positions")
    public ApiResponse<List<String>> companyPositions(@PathVariable String id) {
        return ApiResponse.ok(adminService.positionIdsOf(id));
    }

    /** 关联岗位（幂等：重复关联按成功处理） */
    @PutMapping("/companies/{id}/positions/{positionId}")
    public ApiResponse<Void> attachPosition(@PathVariable String id, @PathVariable String positionId) {
        adminService.attachPosition(id, positionId);
        return ApiResponse.ok(null);
    }

    @DeleteMapping("/companies/{id}/positions/{positionId}")
    public ApiResponse<Void> detachPosition(@PathVariable String id, @PathVariable String positionId) {
        adminService.detachPosition(id, positionId);
        return ApiResponse.ok(null);
    }

    // ── 平台面经（文档）────────────────────────────────────────

    @GetMapping("/documents")
    public ApiResponse<List<DocumentView>> documents(
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String companyId,
            @RequestParam(required = false, defaultValue = "0") int limit) {
        return ApiResponse.ok(documentService.list(status, companyId, limit).stream()
                .map(DocumentView::from).toList());
    }

    public record DocumentRequest(
            @NotBlank String name,
            /** MARKDOWN / PLAIN_TEXT / INTERVIEW（面经用 INTERVIEW） */
            @NotBlank String docType,
            /** 关联公司；可空 = 通用考点 */
            String companyId,
            String tags,
            /** 来源/授权备注，**合规必填**（设计草案 §8） */
            @NotBlank String sourceNote,
            @NotBlank String content
    ) {
    }

    /** 导入并**同步索引**（切分 → 嵌入 → 向量 + Chunk → READY）；失败落 FAILED 并返回原因 */
    @PostMapping("/documents")
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<DocumentView> importDocument(@RequestBody @Validated DocumentRequest request) {
        return ApiResponse.ok(DocumentView.from(documentService.importDocument(
                request.name(), request.docType(), request.companyId(), request.tags(),
                request.sourceNote(), request.content())));
    }

    /** 重新索引（换嵌入模型后必须执行，否则向量语义与新模型不兼容） */
    @PostMapping("/documents/{id}/reindex")
    public ApiResponse<DocumentView> reindexDocument(@PathVariable String id) {
        return ApiResponse.ok(DocumentView.from(documentService.reindex(id)));
    }

    /** 一键下架：status → ARCHIVED + 清 Chunk + 尽力清向量 */
    @PostMapping("/documents/{id}/archive")
    public ApiResponse<Void> archiveDocument(@PathVariable String id) {
        documentService.archive(id);
        return ApiResponse.ok(null);
    }
}
