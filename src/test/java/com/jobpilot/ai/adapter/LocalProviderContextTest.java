package com.jobpilot.ai.adapter;

import com.jobpilot.support.MySqlIntegrationTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * provider-path=ollama 时**不需要任何云端 key** 也能起上下文（CI / 别人 clone 无 .env 的常态）。
 * <p>
 * 三处开关一起覆盖成 ollama——只改一处会命中 {@link AiProviderConsistencyCheck} 的漂移断言，
 * 那正是它存在的意义。这条把「本地路径零依赖可启动」钉死。
 */
@SpringBootTest(properties = {
        "jobpilot.agent.provider-path=ollama",
        "spring.ai.model.chat=ollama",
        "spring.ai.model.embedding=ollama"
})
class LocalProviderContextTest extends MySqlIntegrationTestBase {

    @Test
    void contextLoadsWithoutCloudKeys() {
    }
}
