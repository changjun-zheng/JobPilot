package com.jobpilot.support;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.MySQLContainer;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;

/**
 * 集成测试基类（I-1 顺带清理项）：MySQL 来源二选一，**默认走本机**。
 * <ul>
 *   <li><b>默认（不设 {@value #DOCKER_SWITCH_ENV}）</b>：什么都不注册，用
 *       {@code application-local.yml} 的本机 MySQL；<b>完全不连接 Docker 引擎</b>。</li>
 *   <li><b>设了 {@value #DOCKER_SWITCH_ENV}=true</b>：起一个共享的 MySQL 8.4 容器；
 *       每个 Spring 上下文启动前清空业务表，让 Flyway 从零迁移——context 按配置缓存复用，
 *       若不清表，V3/V4 的 ALTER 会撞「列已存在」。</li>
 * </ul>
 *
 * <h3>为什么用开关而不是「探测 Docker 是否可用」</h3>
 * 曾经写成 {@code isDockerAvailable()} 自动探测，代价是：只要 Docker Desktop 开着，
 * 测试就会去连它——而本机启动 Docker 非常卡。更糟的是探测失败会被静默吞掉
 * （{@code catch (Throwable) { return false; }}），于是「Docker 在跑但连接不兼容」
 * 与「Docker 没装」被当成同一件事，测试照跑照绿，容器分支带病发布而无人察觉。
 * <p>
 * 改成显式开关后：<b>不设环境变量就绝不会碰 Docker</b>，本机卡顿问题从根上消失；
 * 「容器分支本次没跑」也变成一个明确的事实（不是靠猜），启动时打 INFO 说明用的哪个来源。
 * <p>
 * CI（{@code .github/workflows/ci.yml}）显式设置该开关，因此它仍然是「任何机器都能跑」的。
 *
 * <p>清表语句是字面量而非拼接：表的集合是封闭的（全部业务表 + flyway 历史），动态拼标识符
 * 既没有必要，也过不了安全扫描（标识符无法参数化，拼接是唯一写法）。
 * 新增业务表时必须同步补进 {@link #resetSchema()}，否则容器分支会因残留行而假失败。
 */
public abstract class MySqlIntegrationTestBase {

    /**
     * 开启容器 MySQL 的开关。未设置或非 {@code true} 一律用本机库。
     * <p>本机开发<b>不要</b>设置它——启动 Docker Desktop 很卡。
     */
    static final String DOCKER_SWITCH_ENV = "JOBPILOT_TEST_DOCKER";

    private static final Logger log = LoggerFactory.getLogger(MySqlIntegrationTestBase.class);

    private static final boolean USE_CONTAINER = "true".equalsIgnoreCase(
            System.getenv(DOCKER_SWITCH_ENV));

    private static final MySQLContainer<?> MYSQL = createContainer();

    private static MySQLContainer<?> createContainer() {
        if (!USE_CONTAINER) {
            log.info("集成测试使用本机 MySQL（application-local.yml）；"
                    + "如需容器可设环境变量 {}=true", DOCKER_SWITCH_ENV);
            return null;
        }
        // 探测失败必须炸：走到这里说明调用方明确要求了容器，悄悄退回本机库会让「容器分支已验证」变成假象
        boolean available = DockerClientFactory.instance().isDockerAvailable();
        if (!available) {
            throw new IllegalStateException(
                    "已设置 " + DOCKER_SWITCH_ENV + "=true，但 Docker 不可用。"
                            + "请启动 Docker，或取消该环境变量改用本机 MySQL。");
        }
        log.info("集成测试使用 Testcontainers MySQL 容器");
        MySQLContainer<?> container = new MySQLContainer<>("mysql:8.4")
                .withDatabaseName("jobpilot")
                .withUsername("jobpilot")
                .withPassword("jobpilot");
        container.start();
        return container;
    }

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        if (MYSQL == null) {
            return; // 本机模式：交给 application-local.yml 或 CI 注入的数据源
        }
        resetSchema();
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
        // CI 没有 application-local.yml 与密钥环境变量时，上下文也必须能起
        registry.add("jobpilot.security.jwt-secret",
                () -> "test-only-secret-that-is-at-least-32-bytes!");
    }

    private static void resetSchema() {
        try (Connection connection = DriverManager.getConnection(
                MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword());
             Statement statement = connection.createStatement()) {
            statement.execute("SET FOREIGN_KEY_CHECKS = 0");
            statement.execute("DROP TABLE IF EXISTS usage_record");
            statement.execute("DROP TABLE IF EXISTS user_memory");
            statement.execute("DROP TABLE IF EXISTS job_application");
            statement.execute("DROP TABLE IF EXISTS agent_approval_draft");
            statement.execute("DROP TABLE IF EXISTS agent_trace_step");
            statement.execute("DROP TABLE IF EXISTS agent_trace");
            statement.execute("DROP TABLE IF EXISTS kb_chunk");
            statement.execute("DROP TABLE IF EXISTS kb_document");
            statement.execute("DROP TABLE IF EXISTS user_credential");
            statement.execute("DROP TABLE IF EXISTS user_account");
            statement.execute("DROP TABLE IF EXISTS flyway_schema_history");
            statement.execute("SET FOREIGN_KEY_CHECKS = 1");
        } catch (Exception e) {
            throw new IllegalStateException("重置测试容器 schema 失败", e);
        }
    }
}
