<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import * as adminApi from '@/api/admin'

const loading = ref(false)
const documents = ref<adminApi.AdminDocument[]>([])
const companies = ref<adminApi.AdminCompany[]>([])

const filters = reactive({ status: '', companyId: '' })

const dialogVisible = ref(false)
const submitting = ref(false)
const form = reactive({
  name: '',
  docType: 'INTERVIEW',
  companyId: '',
  tags: '',
  sourceNote: '',
  content: '',
})

const DOC_TYPES = [
  { value: 'INTERVIEW', label: '面经（INTERVIEW）' },
  { value: 'MARKDOWN', label: 'Markdown' },
  { value: 'PLAIN_TEXT', label: '纯文本' },
]

const STATUS_LABEL: Record<string, string> = {
  PENDING: '待索引',
  READY: '就绪',
  FAILED: '失败',
  ARCHIVED: '已下架',
}

// 只有在架公司能挂面经（后端同样校验）
const activeCompanies = ref<adminApi.AdminCompany[]>([])

async function load() {
  loading.value = true
  try {
    const [docs, cs] = await Promise.all([
      adminApi.listDocuments({
        status: filters.status || undefined,
        companyId: filters.companyId || undefined,
      }),
      adminApi.listCompanies(),
    ])
    documents.value = docs
    companies.value = cs
    activeCompanies.value = cs.filter((c) => c.status === 'ACTIVE')
  } catch {
    /* 拦截器已提示 */
  } finally {
    loading.value = false
  }
}

onMounted(load)

function openImport() {
  Object.assign(form, {
    name: '',
    docType: 'INTERVIEW',
    companyId: '',
    tags: '',
    sourceNote: '',
    content: '',
  })
  dialogVisible.value = true
}

const companyName = (id: string | null) =>
  id ? (companies.value.find((c) => c.id === id)?.name ?? id) : '—'

/** el-table 插槽的 row 是宽松类型，窄化到 AdminDocument */
const rowAsDoc = (row: unknown) => row as adminApi.AdminDocument

async function submit() {
  if (!form.name.trim()) {
    ElMessage.warning('请输入文档名')
    return
  }
  if (!form.sourceNote.trim()) {
    ElMessage.warning('请填写来源/授权备注（合规必填）')
    return
  }
  if (!form.content.trim()) {
    ElMessage.warning('请输入面经正文')
    return
  }
  submitting.value = true
  try {
    const doc = await adminApi.importDocument({
      name: form.name,
      docType: form.docType,
      companyId: form.companyId || undefined,
      tags: form.tags || undefined,
      sourceNote: form.sourceNote,
      content: form.content,
    })
    if (doc.status === 'READY') {
      ElMessage.success(`导入成功，已索引 ${doc.chunkCount ?? 0} 个片段`)
    } else {
      ElMessage.error(`索引失败：${doc.errorMessage ?? '未知原因'}`)
    }
    dialogVisible.value = false
    await load()
  } catch {
    /* 拦截器已提示 */
  } finally {
    submitting.value = false
  }
}

async function reindex(row: adminApi.AdminDocument) {
  try {
    const doc = await adminApi.reindexDocument(row.id)
    ElMessage.success(doc.status === 'READY' ? '已重建索引' : `重建失败：${doc.errorMessage ?? ''}`)
    await load()
  } catch {
    /* 拦截器已提示 */
  }
}

async function archive(row: adminApi.AdminDocument) {
  try {
    await ElMessageBox.confirm(
      `下架「${row.name}」？其片段与向量会被清除，用户面试将不再检索到它。`,
      '下架面经',
      { type: 'warning' },
    )
  } catch {
    return
  }
  try {
    await adminApi.archiveDocument(row.id)
    ElMessage.success('已下架')
    await load()
  } catch {
    /* 拦截器已提示 */
  }
}
</script>

<template>
  <div class="page">
    <el-card class="panel">
      <template #header>
        <div class="head">
          <span>平台面经</span>
          <el-button type="warning" @click="openImport">导入面经</el-button>
        </div>
      </template>

      <div class="filters">
        <el-select v-model="filters.status" placeholder="全部状态" clearable class="w160" @change="load">
          <el-option v-for="(label, value) in STATUS_LABEL" :key="value" :value="value" :label="label" />
        </el-select>
        <el-select
          v-model="filters.companyId"
          placeholder="全部公司"
          clearable
          filterable
          class="w200"
          @change="load"
        >
          <el-option v-for="c in companies" :key="c.id" :value="c.id" :label="c.name" />
        </el-select>
        <el-button @click="load">刷新</el-button>
      </div>

      <el-table v-loading="loading" :data="documents" border>
        <el-table-column label="名称" prop="name" min-width="200" show-overflow-tooltip />
        <el-table-column label="公司" width="140">
          <template #default="{ row }">{{ companyName(row.companyId) }}</template>
        </el-table-column>
        <el-table-column label="类型" width="120" prop="docType" />
        <el-table-column label="状态" width="100">
          <template #default="{ row }">
            <el-tag
              :type="row.status === 'READY' ? 'success' : row.status === 'FAILED' ? 'danger' : 'info'"
              size="small"
            >
              {{ STATUS_LABEL[row.status] ?? row.status }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="片段" width="80" prop="chunkCount" />
        <el-table-column label="来源/授权" prop="sourceNote" min-width="160" show-overflow-tooltip />
        <el-table-column label="操作" width="170" fixed="right">
          <template #default="{ row }">
            <el-button
              link
              type="primary"
              :disabled="row.status === 'ARCHIVED'"
              @click="reindex(rowAsDoc(row))"
            >
              重建
            </el-button>
            <el-button
              link
              type="warning"
              :disabled="row.status === 'ARCHIVED'"
              @click="archive(rowAsDoc(row))"
            >
              下架
            </el-button>
          </template>
        </el-table-column>
        <template #empty>
          <el-empty description="还没有平台面经，点右上角导入" :image-size="60" />
        </template>
      </el-table>

      <p class="hint">
        导入为<b>同步索引</b>（切分 → 嵌入 → 写入向量与片段），大文档会多等几秒。换过嵌入模型后需对每篇「重建索引」。
      </p>
    </el-card>

    <el-dialog v-model="dialogVisible" title="导入平台面经" width="640">
      <el-form label-width="100px">
        <el-form-item label="文档名" required>
          <el-input v-model="form.name" placeholder="如：字节跳动 · 后端一面面经" />
        </el-form-item>
        <el-form-item label="类型" required>
          <el-select v-model="form.docType" class="wfull">
            <el-option v-for="t in DOC_TYPES" :key="t.value" :value="t.value" :label="t.label" />
          </el-select>
        </el-form-item>
        <el-form-item label="关联公司">
          <el-select v-model="form.companyId" clearable filterable placeholder="不选 = 通用考点" class="wfull">
            <el-option v-for="c in activeCompanies" :key="c.id" :value="c.id" :label="c.name" />
          </el-select>
        </el-form-item>
        <el-form-item label="标签">
          <el-input v-model="form.tags" placeholder="逗号分隔（可选）" />
        </el-form-item>
        <el-form-item label="来源/授权" required>
          <el-input v-model="form.sourceNote" placeholder="如：牛客网授权转载 / 用户投稿并授权" />
        </el-form-item>
        <el-form-item label="正文" required>
          <el-input
            v-model="form.content"
            type="textarea"
            :rows="10"
            resize="none"
            placeholder="粘贴面经原文（支持 Markdown 章节）"
          />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialogVisible = false">取消</el-button>
        <el-button type="warning" :loading="submitting" @click="submit">导入并索引</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.page {
  display: flex;
  flex-direction: column;
  gap: 16px;
}
.head {
  display: flex;
  align-items: center;
  justify-content: space-between;
}
.filters {
  display: flex;
  gap: 12px;
  margin-bottom: 16px;
}
.w160 {
  width: 160px;
}
.w200 {
  width: 200px;
}
.wfull {
  width: 100%;
}
.hint {
  color: #94a3b8;
  font-size: 12.5px;
  margin: 12px 0 0;
  line-height: 1.7;
}
</style>
