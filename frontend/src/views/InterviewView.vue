<script setup lang="ts">
import { nextTick, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Promotion } from '@element-plus/icons-vue'
import { approveDraft } from '@/api/agent'
import {
  answerInterview,
  finishInterview,
  getReport,
  startInterview,
  type InterviewMessage,
  type ReportResult,
} from '@/api/interview'

type Stage = 'setup' | 'interview' | 'report'

const stage = ref<Stage>('setup')
const loading = ref(false)

// ── 设置 ──────────────────────────────────────────────
const setup = ref({ company: '', position: '', tier: 'BIG_TECH', difficultyOverride: '' })
// 档位列表与后端 jobpilot.interview.tiers 对应（后端暂未暴露接口，前端先写死）
const TIERS = [
  { value: 'BIG_TECH', label: '大厂（困难）' },
  { value: 'MID_TECH', label: '中厂（中等）' },
  { value: 'STARTUP', label: '初创（简单）' },
]
const DIFFICULTIES = [
  { value: '', label: '按档位预设' },
  { value: 'EASY', label: '简单' },
  { value: 'MEDIUM', label: '中等' },
  { value: 'HARD', label: '困难' },
]
const PHASE_LABEL: Record<string, string> = {
  BASIC: '基础题',
  PROJECT_DEEP_DIVE: '项目深挖',
  PRESSURE: '压力面',
}

// ── 面试 ──────────────────────────────────────────────
const sessionId = ref('')
const messages = ref<InterviewMessage[]>([])
const round = ref(1)
const totalRounds = ref(3)
const phase = ref('BASIC')
const answer = ref('')
const threadRef = ref<HTMLElement>()

// ── 报告 ──────────────────────────────────────────────
const report = ref<ReportResult | null>(null)
const selected = ref<string[]>([])
const approved = ref(false)

async function scrollToBottom() {
  await nextTick()
  const el = threadRef.value
  if (el) {
    el.scrollTop = el.scrollHeight
  }
}

async function begin() {
  if (!setup.value.company.trim()) {
    ElMessage.warning('请输入目标公司')
    return
  }
  loading.value = true
  try {
    const r = await startInterview({
      company: setup.value.company,
      position: setup.value.position || undefined,
      tier: setup.value.tier,
      difficultyOverride: setup.value.difficultyOverride || undefined,
    })
    sessionId.value = r.sessionId
    round.value = r.round
    totalRounds.value = r.totalRounds
    phase.value = r.phase
    messages.value = [{ role: 'INTERVIEWER', phase: r.phase, round: r.round, content: r.question ?? '' }]
    stage.value = 'interview'
    await scrollToBottom()
  } catch {
    /* 拦截器已提示 */
  } finally {
    loading.value = false
  }
}

async function send() {
  const text = answer.value.trim()
  if (!text || loading.value) return
  messages.value.push({ role: 'CANDIDATE', phase: phase.value, round: round.value, content: text })
  answer.value = ''
  loading.value = true
  await scrollToBottom()
  try {
    const r = await answerInterview(sessionId.value, text)
    if (r.finished) {
      await loadReport()
    } else {
      round.value = r.round
      phase.value = r.phase
      messages.value.push({ role: 'INTERVIEWER', phase: r.phase, round: r.round, content: r.question ?? '' })
      await scrollToBottom()
    }
  } catch {
    /* 提示 */
  } finally {
    loading.value = false
  }
}

async function endEarly() {
  try {
    await ElMessageBox.confirm('提前结束并生成评估报告？', '结束面试', { type: 'warning' })
  } catch {
    return
  }
  loading.value = true
  try {
    await finishInterview(sessionId.value)
    await loadReport()
  } catch {
    /* 提示 */
  } finally {
    loading.value = false
  }
}

async function loadReport() {
  const r = await getReport(sessionId.value)
  report.value = r
  selected.value = r.candidateIds.slice() // 默认全选
  stage.value = 'report'
}

async function approve() {
  if (!report.value?.draftId) return
  if (selected.value.length === 0) {
    ElMessage.warning('请至少选择一条弱点')
    return
  }
  loading.value = true
  try {
    await approveDraft(report.value.draftId, selected.value)
    approved.value = true
    ElMessage.success('已写入选中的弱点到长期记忆')
  } catch {
    /* 提示 */
  } finally {
    loading.value = false
  }
}

function restart() {
  stage.value = 'setup'
  messages.value = []
  report.value = null
  sessionId.value = ''
  selected.value = []
  approved.value = false
}
</script>

<template>
  <div class="interview">
    <!-- 设置 -->
    <el-card v-if="stage === 'setup'" class="panel-card">
      <template #header>开始一场模拟面试</template>
      <el-form label-width="96px" @submit.prevent="begin">
        <el-form-item label="目标公司" required>
          <el-input v-model="setup.company" placeholder="如：字节跳动" />
        </el-form-item>
        <el-form-item label="目标岗位">
          <el-input v-model="setup.position" placeholder="如：后端开发（可选）" />
        </el-form-item>
        <el-form-item label="公司档位">
          <el-select v-model="setup.tier" class="w220">
            <el-option v-for="t in TIERS" :key="t.value" :value="t.value" :label="t.label" />
          </el-select>
        </el-form-item>
        <el-form-item label="难度">
          <el-select v-model="setup.difficultyOverride" class="w220">
            <el-option v-for="d in DIFFICULTIES" :key="d.value" :value="d.value" :label="d.label" />
          </el-select>
        </el-form-item>
        <el-form-item>
          <el-button type="primary" :loading="loading" @click="begin">开始面试</el-button>
          <span class="hint">基于你的简历与知识库里的面经提问；三轮走完自动出报告。</span>
        </el-form-item>
      </el-form>
    </el-card>

    <!-- 面试 -->
    <div v-else-if="stage === 'interview'" class="thread-wrap">
      <div class="thread-head">
        <span>第 {{ round }}/{{ totalRounds }} 轮 · {{ PHASE_LABEL[phase] ?? phase }}</span>
        <el-button link type="warning" :disabled="loading" @click="endEarly">结束并出报告</el-button>
      </div>
      <div ref="threadRef" class="messages">
        <div v-for="(m, i) in messages" :key="i" class="msg" :class="m.role.toLowerCase()">
          <div class="bubble">{{ m.content }}</div>
        </div>
      </div>
      <div class="composer">
        <el-input
          v-model="answer"
          type="textarea"
          :rows="3"
          resize="none"
          placeholder="输入你的回答…（Enter 发送，Shift+Enter 换行）"
          @keydown.enter.exact.prevent="send"
        />
        <el-button type="primary" :icon="Promotion" :loading="loading" class="send-btn" @click="send">
          发送
        </el-button>
      </div>
    </div>

    <!-- 报告 -->
    <div v-else class="report-wrap">
      <el-card class="panel-card">
        <template #header>评估报告</template>
        <p class="summary">{{ report?.report.summary }}</p>
        <el-table v-if="report?.report.dimensions.length" :data="report.report.dimensions" border>
          <el-table-column label="维度" prop="name" width="140" />
          <el-table-column label="点评" prop="comment" />
          <el-table-column label="评分" width="90">
            <template #default="{ row }">{{ row.score ?? '—' }}</template>
          </el-table-column>
        </el-table>
      </el-card>

      <el-card class="panel-card">
        <template #header>值得长期记住的弱点</template>
        <template v-if="report && report.candidateIds.length">
          <el-checkbox-group v-model="selected">
            <div v-for="(w, i) in report.report.weaknesses" :key="i" class="weak">
              <el-checkbox :value="report.candidateIds[i]" :disabled="approved">
                {{ w.content }}
              </el-checkbox>
              <span v-if="w.note" class="weak-note">{{ w.note }}</span>
            </div>
          </el-checkbox-group>
          <el-button
            type="primary"
            class="approve-btn"
            :loading="loading"
            :disabled="approved"
            @click="approve"
          >
            {{ approved ? '已写入' : '写入选中的弱点' }}
          </el-button>
          <span class="hint">经你确认后才写入长期记忆（HITL）。</span>
        </template>
        <el-empty v-else description="本次面试没有识别出需要长期记住的弱点" :image-size="60" />
      </el-card>

      <el-button @click="restart">再面一场</el-button>
    </div>
  </div>
</template>

<style scoped>
.interview {
  flex: 1;
  min-height: 0;
  display: flex;
  flex-direction: column;
  overflow-y: auto;
}
.panel-card {
  margin-bottom: 16px;
}
.w220 {
  width: 220px;
}
.hint {
  color: #94a3b8;
  font-size: 12.5px;
  margin-left: 12px;
}
.thread-wrap {
  flex: 1;
  min-height: 0;
  display: flex;
  flex-direction: column;
  background: #fff;
  border: 1px solid #e6e9f0;
  border-radius: 12px;
}
.thread-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 12px 16px;
  border-bottom: 1px solid #e6e9f0;
  font-weight: 600;
}
.messages {
  flex: 1;
  min-height: 0;
  overflow-y: auto;
  padding: 20px;
}
.msg {
  margin-bottom: 14px;
  display: flex;
}
.msg.candidate {
  justify-content: flex-end;
}
.bubble {
  max-width: 78%;
  padding: 10px 14px;
  border-radius: 10px;
  white-space: pre-wrap;
  word-break: break-word;
  line-height: 1.7;
  font-size: 14px;
  background: #f6f7fb;
}
.msg.candidate .bubble {
  background: #4f46e5;
  color: #fff;
}
.composer {
  border-top: 1px solid #e6e9f0;
  padding: 12px;
  display: flex;
  gap: 10px;
  align-items: flex-end;
}
.send-btn {
  height: 40px;
}
.summary {
  margin: 0 0 12px;
  line-height: 1.8;
}
.weak {
  display: flex;
  align-items: baseline;
  gap: 8px;
  padding: 4px 0;
}
.weak-note {
  color: #94a3b8;
  font-size: 12.5px;
}
.approve-btn {
  margin-top: 12px;
}
</style>
