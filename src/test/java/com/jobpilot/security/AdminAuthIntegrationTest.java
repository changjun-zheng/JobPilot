package com.jobpilot.security;

import com.jobpilot.support.MySqlIntegrationTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 双认证轴（设计草案 §1#5）：管理令牌与用户令牌**互不认账**。
 * <p>
 * 本类专属地启用管理面（username/password/jwt-secret），其余集成测试跑在「管理面未配置」的
 * 上下文里——那正是「不配置即禁用」默认态的证据。
 */
@SpringBootTest
@ActiveProfiles("local")
@AutoConfigureMockMvc
@ContextConfiguration(classes = com.jobpilot.agent.AgentChatServiceIntegrationTest.TestPorts.class)
class AdminAuthIntegrationTest extends MySqlIntegrationTestBase {

    private static final String ADMIN_USER = "ops";
    private static final String ADMIN_PASS = "s3cret-admin-pass";
    private static final String ADMIN_SECRET = "test-only-admin-secret-at-least-32-bytes";

    @DynamicPropertySource
    static void adminProps(DynamicPropertyRegistry registry) {
        registry.add("jobpilot.admin.username", () -> ADMIN_USER);
        registry.add("jobpilot.admin.password", () -> ADMIN_PASS);
        registry.add("jobpilot.admin.jwt-secret", () -> ADMIN_SECRET);
    }

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private AdminAuthService adminAuthService;
    @Autowired
    private JwtService userJwtService;

    @Test
    void adminLoginIssuesTokenThatWorksOnAdminPaths() throws Exception {
        String token = login();

        mockMvc.perform(get("/api/v1/admin/platform/companies")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());
    }

    @Test
    void adminLoginRejectsWrongCredentials() throws Exception {
        mockMvc.perform(post("/api/v1/admin/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"ops\",\"password\":\"wrong\"}"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(post("/api/v1/admin/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"nobody\",\"password\":\"" + ADMIN_PASS + "\"}"))
                .andExpect(status().isUnauthorized());
    }

    /** 用户令牌拿到管理面来用：issuer/密钥都对不上 → 401 */
    @Test
    void userTokenCannotAccessAdminPaths() throws Exception {
        String userToken = userJwtService.issue("some-tenant");

        mockMvc.perform(get("/api/v1/admin/platform/companies")
                        .header("Authorization", "Bearer " + userToken))
                .andExpect(status().isUnauthorized());
    }

    /** 管理令牌拿到用户面来用：租户拦截器按租户密钥验签失败 → 401 */
    @Test
    void adminTokenCannotAccessUserPaths() throws Exception {
        String adminToken = login();

        mockMvc.perform(get("/api/v1/knowledge/documents")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void adminPathsRequireToken() throws Exception {
        mockMvc.perform(get("/api/v1/admin/platform/companies"))
                .andExpect(status().isUnauthorized());
    }

    private String login() throws Exception {
        String body = mockMvc.perform(post("/api/v1/admin/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + ADMIN_USER + "\",\"password\":\"" + ADMIN_PASS + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.accessToken").isNotEmpty())
                .andReturn().getResponse().getContentAsString();
        return com.jayway.jsonpath.JsonPath.read(body, "$.data.accessToken");
    }
}
