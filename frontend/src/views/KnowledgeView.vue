<script setup lang="ts">
import { onMounted, onUnmounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox, type FormInstance, type FormRules } from 'element-plus'
import { Delete, Refresh, RefreshLeft, Upload } from '@element-plus/icons-vue'
import {
  createDocument,
  deleteDocument,
  listDocuments,
  reindexDocument,
  type KbDocument,
} from '@/api/knowledge'

const docs = ref<KbDocument[]>([])
const loading = ref(false)
const statusFilter = ref('')

const STATUS_TAG: Record<string, 'success' | 'warning' | 'info' | 'danger'> = {
  READY: 'success',
  PROCESSING: 'warning',
  PENDING: 'info',
  FAILED: 'danger',
}
const STATUS_OPTIONS = [
  { value: '', label: '全部状态' },
  { value: 'READY', label: 'READY' },
  { value: 'PROCESSING', label: 'PROCESSING' },
  { value: 'PENDING', label: 'PENDING' },
  { value: 'FAILED', label: 'FAILED' },
]
const DOC_TYPE_LABEL: Record<string, string> = {
  MARKDOWN: 'Markdown',
  PLAIN_TEXT: '纯文本',
  INTERVIEW: '面经',
}

function fmtTime(value: string | null): string {
  return value ? new Date(value).toLocaleString('zh-CN', { hour12: false }) : '—'
}

// 索引是异步的（PENDING/PROCESSING），只在有进行中的文档时才轮询，避免无谓请求
let pollTimer: ReturnType<typeof setTimeout> | undefined
function clearPoll() {
  if (pollTimer) {
    clearTimeout(pollTimer)
    pollTimer = undefined
  }
}
function schedulePoll() {
  clearPoll()
  if (docs.value.some((d) => d.status === 'PENDING' || d.status === 'PROCESSING')) {
    pollTimer = setTimeout(() => load(false), 2500)
  }
}

async function load(showLoading = true) {
  if (showLoading) loading.value = true
  try {
    docs.value = await listDocuments({ status: statusFilter.value || undefined, limit: 100 })
  } catch {
    /* 拦截器已提示 */
  } finally {
    loading.value = false
    schedulePoll()
  }
}

onMounted(load)
onUnmounted(clearPoll)

// ── 导入 ──────────────────────────────────────────────
const importVisible = ref(false)
const importing = ref(false)
const importRef = ref<FormInstance>()
const importForm = reactive({ name: '', docType: '', tags: '', content: '' })
const importRules: FormRules = {
  name: [{ required: true, message: '请输入文档名', trigger: 'blur' }],
  content: [{ required: true, message: '请输入或粘贴正文', trigger: 'blur' }],
}

function openImport() {
  importVisible.value = true
}

async function submitImport() {
  const form = importRef.value
  if (!form) return
  const valid = await form.validate().catch(() => false)
  if (!valid) return

  importing.value = true
  try {
    await createDocument({
      name: importForm.name,
      content: importForm.content,
      docType: importForm.docType || undefined,
      tags: importForm.tags || undefined,
    })
    ElMessage.success('已提交导入，正在后台索引')
    importVisible.value = false
    Object.assign(importForm, { name: '', docType: '', tags: '', content: '' })
    await load()
  } catch {
    /* 拦截器已提示 */
  } finally {
    importing.value = false
  }
}

/** el-table 插槽的 row 是宽松类型，窄化到 KbDocument */
const rowAsDoc = (row: unknown) => row as KbDocument

async function reindex(row: KbDocument) {
  try {
    await reindexDocument(row.id)
    ElMessage.success('已重置为待索引')
    await load()
  } catch {
    /* 拦截器已提示 */
  }
}

async function remove(row: KbDocument) {
  try {
    await ElMessageBox.confirm(
      `删除文档「${row.name}」及其全部 Chunk 与向量？此操作不可恢复。`,
      '删除文档',
      { type: 'warning' },
    )
  } catch {
    return
  }
  try {
    await deleteDocument(row.id)
    ElMessage.success('已删除')
    await load()
  } catch {
    /* 拦截器已提示（如向量库不可用导致删除失败） */
  }
}
</script>

<template>
  <el-card class="kb">
    <template #header>
      <div class="head">
        <span>知识库</span>
        <div class="actions">
          <el-select v-model="statusFilter" class="status-filter" @change="load()">
            <el-option
              v-for="opt in STATUS_OPTIONS"
              :key="opt.value"
              :value="opt.value"
              :label="opt.label"
            />
          </el-select>
          <el-button :icon="Refresh" :loading="loading" @click="load()">刷新</el-button>
          <el-button type="primary" :icon="Upload" @click="openImport">导入文档</el-button>
        </div>
      </div>
    </template>

    <el-table v-loading="loading" :data="docs" border>
      <el-table-column label="名称" prop="name" min-width="180" show-overflow-tooltip />
      <el-table-column label="类型" width="100">
        <template #default="{ row }">{{ DOC_TYPE_LABEL[row.docType] ?? row.docType }}</template>
      </el-table-column>
      <el-table-column label="状态" width="130">
        <template #default="{ row }">
          <el-tag :type="STATUS_TAG[row.status] ?? 'info'" size="small">{{ row.status }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column label="Chunk" width="90">
        <template #default="{ row }">{{ row.chunkCount ?? '—' }}</template>
      </el-table-column>
      <el-table-column label="导入时间" width="170">
        <template #default="{ row }">{{ fmtTime(row.createdAt) }}</template>
      </el-table-column>
      <el-table-column label="失败原因" min-width="160" show-overflow-tooltip>
        <template #default="{ row }">
          <span v-if="row.status === 'FAILED'" class="err">{{ row.errorMessage || '—' }}</span>
          <span v-else class="muted">—</span>
        </template>
      </el-table-column>
      <el-table-column label="操作" width="160" fixed="right">
        <template #default="{ row }">
          <el-button
            text
            type="primary"
            :icon="RefreshLeft"
            :disabled="row.status === 'PENDING' || row.status === 'PROCESSING'"
            @click="reindex(rowAsDoc(row))"
          >
            重建
          </el-button>
          <el-button text type="danger" :icon="Delete" @click="remove(rowAsDoc(row))">删除</el-button>
        </template>
      </el-table-column>
      <template #empty>
        <el-empty description="还没有文档，点右上角「导入文档」粘贴一份简历或 JD" />
      </template>
    </el-table>

    <el-dialog v-model="importVisible" title="导入文档" width="560px">
      <el-form ref="importRef" :model="importForm" :rules="importRules" label-position="top">
        <el-form-item label="文档名" prop="name">
          <el-input v-model="importForm.name" placeholder="如：我的简历.md（.md/.markdown 会按 Markdown 切分）" />
        </el-form-item>
        <el-form-item label="类型">
          <el-radio-group v-model="importForm.docType">
            <el-radio value="">按文件名自动判断</el-radio>
            <el-radio value="MARKDOWN">Markdown</el-radio>
            <el-radio value="PLAIN_TEXT">纯文本</el-radio>
            <el-radio value="INTERVIEW">面经</el-radio>
          </el-radio-group>
        </el-form-item>
        <el-form-item label="标签（可选）">
          <el-input v-model="importForm.tags" placeholder="如：简历,面经" />
        </el-form-item>
        <el-form-item label="正文" prop="content">
          <el-input v-model="importForm.content" type="textarea" :rows="10" placeholder="粘贴文档正文…" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="importVisible = false">取消</el-button>
        <el-button type="primary" :loading="importing" @click="submitImport">提交导入</el-button>
      </template>
    </el-dialog>
  </el-card>
</template>

<style scoped>
.kb {
  flex: 1;
  min-height: 0;
  display: flex;
  flex-direction: column;
}
.head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
}
.actions {
  display: flex;
  gap: 8px;
}
.status-filter {
  width: 150px;
}
.err {
  color: #ef4444;
  font-size: 12.5px;
}
.muted {
  color: #94a3b8;
}
</style>
