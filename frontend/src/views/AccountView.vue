<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { Refresh } from '@element-plus/icons-vue'
import { getUsageSummary, type UsageSummary } from '@/api/usage'

const summary = ref<UsageSummary>()
const loading = ref(false)
const range = ref<[string, string] | null>(null)

const DIM_LABEL: Record<string, string> = {
  EMBEDDING: 'Embedding 调用',
  LLM_TOKEN: 'LLM token',
  AGENT_RUN: 'Agent 调用',
}
const SCEN_LABEL: Record<string, string> = {
  ASK: '引用问答',
  SEARCH: '纯检索',
  AGENT: 'Agent',
  INGEST: '文档导入',
  EVAL: '评测',
}

/** 0 显示成「—」：区分「这一维度不产生该项」与「有数值」。不写 0 免得误导 */
function fmt(value: number | undefined): string {
  return value ? value.toLocaleString('zh-CN') : '—'
}

async function load() {
  loading.value = true
  try {
    const params: { from?: string; to?: string } = {}
    if (range.value) {
      params.from = range.value[0]
      params.to = range.value[1]
    }
    summary.value = await getUsageSummary(params)
  } catch {
    /* 拦截器已提示 */
  } finally {
    loading.value = false
  }
}

onMounted(load)
</script>

<template>
  <div class="account">
    <el-card>
      <template #header>
        <div class="card-head">
          <span>用量</span>
          <div class="filters">
            <el-date-picker
              v-model="range"
              type="daterange"
              value-format="YYYY-MM-DD"
              start-placeholder="开始日期"
              end-placeholder="结束日期"
              unlink-panels
              @change="load"
            />
            <el-button :icon="Refresh" :loading="loading" @click="load">刷新</el-button>
          </div>
        </div>
      </template>

      <div class="storage">
        <el-statistic title="文档数" :value="summary?.storage.documents ?? 0" />
        <el-statistic title="正文字符数" :value="summary?.storage.chars ?? 0" />
      </div>

      <el-table v-loading="loading" :data="summary?.dimensions ?? []" border>
        <el-table-column type="expand">
          <template #default="{ row }">
            <div class="scenarios">
              <el-table v-if="row.scenarios.length" :data="row.scenarios" size="small" border>
                <el-table-column label="场景">
                  <template #default="{ row: sc }">{{ SCEN_LABEL[sc.scenario] ?? sc.scenario }}</template>
                </el-table-column>
                <el-table-column label="调用">
                  <template #default="{ row: sc }">{{ fmt(sc.calls) }}</template>
                </el-table-column>
                <el-table-column label="字符数">
                  <template #default="{ row: sc }">{{ fmt(sc.chars) }}</template>
                </el-table-column>
                <el-table-column label="输入 token">
                  <template #default="{ row: sc }">{{ fmt(sc.promptTokens) }}</template>
                </el-table-column>
                <el-table-column label="输出 token">
                  <template #default="{ row: sc }">{{ fmt(sc.completionTokens) }}</template>
                </el-table-column>
              </el-table>
              <span v-else class="muted">区间内该维度无计量行</span>
            </div>
          </template>
        </el-table-column>
        <el-table-column label="维度">
          <template #default="{ row }">{{ DIM_LABEL[row.dimension] ?? row.dimension }}</template>
        </el-table-column>
        <el-table-column label="调用次数">
          <template #default="{ row }">{{ fmt(row.calls) }}</template>
        </el-table-column>
        <el-table-column label="字符数">
          <template #default="{ row }">{{ fmt(row.chars) }}</template>
        </el-table-column>
        <el-table-column label="输入 token">
          <template #default="{ row }">{{ fmt(row.promptTokens) }}</template>
        </el-table-column>
        <el-table-column label="输出 token">
          <template #default="{ row }">{{ fmt(row.completionTokens) }}</template>
        </el-table-column>
        <el-table-column label="迭代 / 工具">
          <template #default="{ row }">{{ fmt(row.iterations) }} / {{ fmt(row.toolCalls) }}</template>
        </el-table-column>
        <el-table-column label="token 缺失">
          <template #default="{ row }">
            <el-tag v-if="row.tokenUnavailable" type="warning" size="small">
              {{ row.tokenUnavailable }}
            </el-tag>
            <span v-else class="muted">—</span>
          </template>
        </el-table-column>
      </el-table>

      <el-alert
        class="note"
        type="info"
        :closable="false"
        title="本地模型零成本但仍计量（切换云端时口径不变）；「token 缺失」表示供应商未返回，不做估算。"
      />
      <el-alert
        class="note"
        type="warning"
        :closable="false"
        show-icon
        title="配额与限流属 I-5"
        description="阈值需真实用量数据校准，本期只做「可归集」。"
      />
    </el-card>

    <el-card>
      <template #header>数据管理</template>
      <div class="actions">
        <el-button disabled>导出我的数据</el-button>
        <el-button disabled type="danger" plain>注销账号</el-button>
        <span class="muted">数据导出与注销属 I-5，待实现。</span>
      </div>
    </el-card>
  </div>
</template>

<style scoped>
.account {
  flex: 1;
  min-height: 0;
  display: flex;
  flex-direction: column;
  gap: 16px;
  overflow-y: auto;
}
.card-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
}
.filters {
  display: flex;
  gap: 8px;
}
.storage {
  display: flex;
  gap: 48px;
  margin-bottom: 16px;
}
.scenarios {
  padding: 8px 16px 12px;
}
.note {
  margin-top: 12px;
}
.actions {
  display: flex;
  align-items: center;
  gap: 12px;
}
.muted {
  color: #94a3b8;
  font-size: 13px;
}
</style>
