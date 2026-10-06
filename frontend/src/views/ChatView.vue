<script setup lang="ts">
import { nextTick, onMounted, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Delete, Plus, Promotion } from '@element-plus/icons-vue'
import { runAgent } from '@/api/agent'
import {
  deleteConversation,
  getConversation,
  listConversations,
  type ConversationSummary,
  type MessageStep,
} from '@/api/conversation'

/** 界面里的一条消息。带 pending 的是「助手正在输入」的占位气泡 */
interface ChatItem {
  key: string
  role: 'USER' | 'ASSISTANT'
  content: string
  steps?: MessageStep[]
  draftIds?: string[]
  pending?: boolean
}

const conversations = ref<ConversationSummary[]>([])
const activeId = ref<string | null>(null)
const items = ref<ChatItem[]>([])
const input = ref('')
const sending = ref(false)
const loadingThread = ref(false)

const threadRef = ref<HTMLElement>()

async function scrollToBottom() {
  await nextTick()
  const el = threadRef.value
  if (el) {
    el.scrollTop = el.scrollHeight
  }
}

async function loadConversations() {
  conversations.value = await listConversations().catch(() => [])
}

onMounted(async () => {
  await loadConversations()
  // 默认打开最近一个会话；没有历史则停在「新会话」空态
  if (conversations.value.length > 0) {
    await selectConversation(conversations.value[0]!.id)
  }
})

async function selectConversation(id: string) {
  if (sending.value) return
  loadingThread.value = true
  activeId.value = id
  try {
    const detail = await getConversation(id)
    items.value = detail.messages.map((m) => ({
      key: m.id,
      role: m.role,
      content: m.content,
      steps: m.steps,
    }))
    await scrollToBottom()
  } catch {
    /* 拦截器已提示（如 404） */
  } finally {
    loadingThread.value = false
  }
}

function newChat() {
  if (sending.value) return
  activeId.value = null
  items.value = []
}

async function removeConversation(id: string) {
  try {
    await ElMessageBox.confirm('删除该会话及其全部消息？此操作不可恢复。', '删除会话', {
      type: 'warning',
    })
  } catch {
    return
  }
  try {
    await deleteConversation(id)
    ElMessage.success('已删除')
    if (activeId.value === id) {
      newChat()
    }
    await loadConversations()
  } catch {
    /* 拦截器已提示 */
  }
}

async function send() {
  const text = input.value.trim()
  if (!text || sending.value) return

  items.value.push({ key: `u-${Date.now()}`, role: 'USER', content: text })
  input.value = ''
  sending.value = true
  const pendingKey = `a-${Date.now()}`
  items.value.push({ key: pendingKey, role: 'ASSISTANT', content: '', pending: true })
  await scrollToBottom()

  try {
    const result = await runAgent(text, activeId.value ?? undefined)
    activeId.value = result.conversationId
    const idx = items.value.findIndex((i) => i.key === pendingKey)
    const answer: ChatItem = {
      key: pendingKey,
      role: 'ASSISTANT',
      content: result.answer,
      steps: result.steps,
      draftIds: result.draftIds,
    }
    if (idx >= 0) {
      items.value[idx] = answer
    } else {
      items.value.push(answer)
    }
    // 新建的会话需要出现在左侧列表里（标题由首条消息派生）
    await loadConversations()
  } catch {
    // 拦截器已提示失败原因；移除「正在输入」气泡，让用户可重试
    items.value = items.value.filter((i) => i.key !== pendingKey)
  } finally {
    sending.value = false
    await scrollToBottom()
  }
}
</script>

<template>
  <div class="chat">
    <!-- 左：会话列表 -->
    <aside class="sidebar">
      <el-button class="new-btn" type="primary" :icon="Plus" @click="newChat">新会话</el-button>
      <el-scrollbar class="conv-scroll">
        <div
          v-for="conv in conversations"
          :key="conv.id"
          class="conv"
          :class="{ active: conv.id === activeId }"
          @click="selectConversation(conv.id)"
        >
          <span class="conv-title">{{ conv.title }}</span>
          <el-icon class="conv-del" @click.stop="removeConversation(conv.id)"><Delete /></el-icon>
        </div>
        <el-empty v-if="conversations.length === 0" description="还没有会话" :image-size="60" />
      </el-scrollbar>
    </aside>

    <!-- 右：消息区 + 输入框 -->
    <section class="thread">
      <div ref="threadRef" v-loading="loadingThread" class="messages">
        <el-empty
          v-if="items.length === 0"
          description="开始新对话：问简历、贴 JD、记录投递都可以"
        />
        <div v-for="item in items" :key="item.key" class="msg" :class="item.role.toLowerCase()">
          <div class="bubble">
            <template v-if="item.pending">
              <span class="typing">正在思考…</span>
            </template>
            <template v-else>{{ item.content }}</template>
          </div>

          <!-- 工具调用摘要 -->
          <el-collapse v-if="item.steps && item.steps.length" class="steps">
            <el-collapse-item :title="`工具调用（${item.steps.length}）`">
              <div v-for="(s, i) in item.steps" :key="i" class="step">
                <span class="step-kind">{{ s.kind }}</span>
                <span class="step-name">{{ s.name }}</span>
                <el-tag size="small" :type="s.status === 'ok' ? 'success' : 'warning'">{{ s.status }}</el-tag>
                <span v-if="s.summary" class="step-summary">{{ s.summary }}</span>
              </div>
            </el-collapse-item>
          </el-collapse>

          <!-- HITL：产生了待审批草稿 -->
          <el-alert
            v-if="item.draftIds && item.draftIds.length"
            class="draft"
            type="warning"
            :closable="false"
            show-icon
            :title="`已生成 ${item.draftIds.length} 个待审批草稿`"
            description="副作用尚未执行，请前往审批中心确认。（审批中心页面待建）"
          />
        </div>
      </div>

      <div class="composer">
        <el-input
          v-model="input"
          type="textarea"
          :rows="3"
          resize="none"
          placeholder="输入问题，或粘贴一段 JD…（Enter 发送，Shift+Enter 换行）"
          @keydown.enter.exact.prevent="send"
        />
        <el-button
          type="primary"
          :icon="Promotion"
          :loading="sending"
          class="send-btn"
          @click="send"
        >
          发送
        </el-button>
      </div>
    </section>
  </div>
</template>

<style scoped>
.chat {
  flex: 1;
  min-height: 0;
  display: flex;
  gap: 16px;
}
.sidebar {
  width: 260px;
  display: flex;
  flex-direction: column;
  background: #fff;
  border: 1px solid #e6e9f0;
  border-radius: 12px;
  padding: 12px;
}
.new-btn {
  width: 100%;
  margin-bottom: 10px;
}
.conv-scroll {
  flex: 1;
  min-height: 0;
}
.conv {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 8px 10px;
  border-radius: 8px;
  cursor: pointer;
  font-size: 13.5px;
}
.conv:hover {
  background: #f6f7fb;
}
.conv.active {
  background: #eef2ff;
  color: #4f46e5;
}
.conv-title {
  flex: 1;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.conv-del {
  color: #94a3b8;
  visibility: hidden;
}
.conv:hover .conv-del {
  visibility: visible;
}
.conv-del:hover {
  color: #ef4444;
}

.thread {
  flex: 1;
  min-width: 0;
  display: flex;
  flex-direction: column;
  background: #fff;
  border: 1px solid #e6e9f0;
  border-radius: 12px;
}
.messages {
  flex: 1;
  min-height: 0;
  overflow-y: auto;
  padding: 20px;
}
.msg {
  margin-bottom: 16px;
  display: flex;
  flex-direction: column;
}
.msg.user {
  align-items: flex-end;
}
.msg.assistant {
  align-items: flex-start;
}
.bubble {
  max-width: 78%;
  padding: 10px 14px;
  border-radius: 10px;
  white-space: pre-wrap;
  word-break: break-word;
  line-height: 1.7;
  font-size: 14px;
}
.msg.user .bubble {
  background: #4f46e5;
  color: #fff;
}
.msg.assistant .bubble {
  background: #f6f7fb;
  color: #1f2430;
}
.typing {
  color: #5b6472;
}
.steps {
  max-width: 78%;
  margin-top: 6px;
}
.step {
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: 12.5px;
  color: #5b6472;
  padding: 3px 0;
}
.step-kind {
  font-family: Consolas, monospace;
  color: #94a3b8;
}
.step-name {
  font-weight: 600;
  color: #1f2430;
}
.step-summary {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.draft {
  max-width: 78%;
  margin-top: 8px;
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
</style>
