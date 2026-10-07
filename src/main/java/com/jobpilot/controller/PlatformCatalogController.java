package com.jobpilot.controller;

import com.jobpilot.common.ApiResponse;
import com.jobpilot.domain.PlatformCompanyEntity;
import com.jobpilot.domain.PlatformPositionEntity;
import com.jobpilot.platform.PlatformCatalogService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 平台公司与岗位目录（**全局数据，所有用户可读**）。
 * <p>
 * 只读、无租户：数据本身不归属任何用户。要求登录（不在 public-paths 白名单里），
 * 但登录用户看到的是**同一份**平台目录。
 * <p>
 * 本切片没有写入接口——平台内容的导入/上下架属管理端（后续）。
 */
@RestController
@RequestMapping("/api/v1/companies")
public class PlatformCatalogController {

    private final PlatformCatalogService catalogService;

    public PlatformCatalogController(PlatformCatalogService catalogService) {
        this.catalogService = catalogService;
    }

    public record CompanyView(String id, String name, String tier, String industry, String tags) {

        static CompanyView from(PlatformCompanyEntity c) {
            return new CompanyView(c.getId(), c.getName(), c.getTier(), c.getIndustry(), c.getTags());
        }
    }

    public record PositionView(String id, String name) {

        static PositionView from(PlatformPositionEntity p) {
            return new PositionView(p.getId(), p.getName());
        }
    }

    /** {@code position} = 岗位名（按岗位反查公司）；{@code q} = 公司名关键词；都缺省则返回全部上架公司 */
    @GetMapping
    public ApiResponse<List<CompanyView>> companies(
            @RequestParam(required = false) String position,
            @RequestParam(required = false) String q) {
        return ApiResponse.ok(catalogService.companies(position, q).stream()
                .map(CompanyView::from).toList());
    }

    /** 全部岗位（供界面下拉） */
    @GetMapping("/positions")
    public ApiResponse<List<PositionView>> positions() {
        return ApiResponse.ok(catalogService.positions().stream().map(PositionView::from).toList());
    }
}
