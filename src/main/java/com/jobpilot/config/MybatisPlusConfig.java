package com.jobpilot.config;

import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.handler.TenantLineHandler;
import com.baomidou.mybatisplus.extension.plugins.inner.TenantLineInnerInterceptor;
import com.jobpilot.security.UserContext;
import net.sf.jsqlparser.expression.Expression;
import net.sf.jsqlparser.expression.StringValue;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Set;

/**
 * 租户隔离的落地点（ARCHITECTURE.md §1.7）。
 * <p>
 * 「共享库共享表 + 租户键强制注入」：拦截器在 SQL 改写阶段为每张业务表追加 {@code user_id = ?}，
 * 业务代码接触不到这个条件，因此漏不掉。这是本项目的**架构不变量**，不参与「时间紧张就砍」的取舍。
 * <p>
 * <b>注意：这里只注册租户拦截器，没有分页拦截器。</b>
 * {@code PaginationInnerInterceptor} 仅在 mapper 方法带 {@code IPage} 参数时才生效，
 * 当前没有任何分页查询，提前加就是死配置。将来引入分页时**必须加在租户拦截器之后**——
 * 顺序反了，count 语句会在租户条件注入前生成，统计数字会跨租户。
 */
@Configuration(proxyBeanMethods = false)
public class MybatisPlusConfig {

    /**
     * 无租户语义的表——拦截器**不会**给它们注入 {@code user_id}。<b>两种用途，别混</b>：
     * <ol>
     *   <li><b>认证表</b>（{@code user_account} / {@code user_credential}）：登录要在「知道你是谁」之前
     *       按邮箱反查账号，天然跨租户；</li>
     *   <li><b>平台内容表</b>（{@code platform_*}）：它们**根本没有 {@code user_id} 列**——是全局数据，
     *       所有用户可见。拦截器不检查表结构，会给非豁免表盲加 {@code user_id = ?}，
     *       表没这列就直接报 {@code Unknown column}，所以必须豁免。</li>
     * </ol>
     * 注意第二种**不是**「随便塞表进来」的借口：业务表仍必须带 {@code user_id} 且**不**在此列。
     * 平台内容与租户内容共存的表（{@code kb_document} / {@code kb_chunk}）靠 {@code owner} 列区分，
     * 由 {@code CHECK (owner='PLATFORM' OR user_id IS NOT NULL)} 在数据库层保证「用户行必有租户」。
     */
    private static final Set<String> TENANT_EXEMPT_TABLES = Set.of(
            "user_account", "user_credential",
            "platform_company", "platform_position", "platform_company_position");

    @Bean
    public MybatisPlusInterceptor mybatisPlusInterceptor() {
        MybatisPlusInterceptor interceptor = new MybatisPlusInterceptor();
        interceptor.addInnerInterceptor(new TenantLineInnerInterceptor(new TenantLineHandler() {

            @Override
            public String getTenantIdColumn() {
                return "user_id";
            }

            /**
             * 当前租户。缺失直接抛异常，**不返回 null**。
             * <p>
             * 返回 null 会让拦截器拼出 {@code user_id = null}，等价于无过滤——静默的全租户可见。
             * 抛异常是刻意的 fail-loud：这条路径正常不可达（认证拦截器已拒绝未认证请求），
             * 触达即说明存在漏设上下文的执行路径（典型的如后台线程），应当立刻暴露。
             */
            @Override
            public Expression getTenantId() {
                // 值来自 UserContext，而 UserContext 只由认证拦截器写入服务端签发的 JWT subject，
                // 是受控的 UUID，不含引号——因此内联为字面量是安全的。
                // 若将来 tenant id 改为用户可控字符串，此处必须换成参数绑定。
                return new StringValue(UserContext.require());
            }

            @Override
            public boolean ignoreTable(String tableName) {
                return TENANT_EXEMPT_TABLES.contains(tableName.toLowerCase());
            }
        }));
        return interceptor;
    }
}
