<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import * as adminApi from '@/api/admin'

const loading = ref(false)
const companies = ref<adminApi.AdminCompany[]>([])
const positions = ref<adminApi.AdminPosition[]>([])

// 档位与后端 jobpilot.interview.tiers 对应（后端未暴露接口，前端写死）
const TIERS = [
  { value: 'BIG_TECH', label: '大厂（困难）' },
  { value: 'MID_TECH', label: '中厂（中等）' },
  { value: 'STARTUP', label: '初创（简单）' },
]

const dialogVisible = ref(false)
const editingId = ref<string | null>(null)
const form = reactive({ name: '', tier: 'BIG_TECH', industry: '', tags: '' })
const submitting = ref(false)

const isEditing = computed(() => editingId.value !== null)

async function load() {
  loading.value = true
  try {
    const [cs, ps] = await Promise.all([adminApi.listCompanies(), adminApi.listPositions()])
    companies.value = cs
    positions.value = ps
  } catch {
    /* 拦截器已提示 */
  } finally {
    loading.value = false
  }
}

onMounted(load)

function openCreate() {
  editingId.value = null
  Object.assign(form, { name: '', tier: 'BIG_TECH', industry: '', tags: '' })
  dialogVisible.value = true
}

function openEdit(row: adminApi.AdminCompany) {
  editingId.value = row.id
  Object.assign(form, {
    name: row.name,
    tier: row.tier,
    industry: row.industry ?? '',
    tags: row.tags ?? '',
  })
  dialogVisible.value = true
}

async function submit() {
  if (!form.name.trim()) {
    ElMessage.warning('请输入公司名')
    return
  }
  submitting.value = true
  try {
    if (isEditing.value && editingId.value) {
      await adminApi.updateCompany(editingId.value, {
        name: form.name,
        tier: form.tier,
        industry: form.industry,
        tags: form.tags,
      })
      ElMessage.success('已保存')
    } else {
      await adminApi.createCompany({
        name: form.name,
        tier: form.tier,
        industry: form.industry || undefined,
        tags: form.tags || undefined,
      })
      ElMessage.success('已创建')
    }
    dialogVisible.value = false
    await load()
  } catch {
    /* 拦截器已提示 */
  } finally {
    submitting.value = false
  }
}

async function toggleStatus(row: adminApi.AdminCompany) {
  const archiving = row.status === 'ACTIVE'
  if (archiving) {
    try {
      await ElMessageBox.confirm(
        `下架「${row.name}」？用户侧目录与面试选司将不再显示它（历史面试不受影响）。`,
        '下架公司',
        { type: 'warning' },
      )
    } catch {
      return
    }
  }
  try {
    if (archiving) {
      await adminApi.archiveCompany(row.id)
    } else {
      await adminApi.activateCompany(row.id)
    }
    ElMessage.success(archiving ? '已下架' : '已上架')
    await load()
  } catch {
    /* 拦截器已提示 */
  }
}

const tierLabel = (tier: string) => TIERS.find((t) => t.value === tier)?.label ?? tier

/** el-table 插槽的 row 是宽松类型，窄化到 AdminCompany */
const rowAsCompany = (row: unknown) => row as adminApi.AdminCompany
</script>

<template>
  <div class="page">
    <el-card class="panel">
      <template #header>
        <div class="head">
          <span>公司与岗位</span>
          <el-button type="primary" @click="openCreate">新建公司</el-button>
        </div>
      </template>

      <el-table v-loading="loading" :data="companies" border>
        <el-table-column label="公司" prop="name" min-width="160" />
        <el-table-column label="难度档位" width="150">
          <template #default="{ row }">{{ tierLabel(row.tier) }}</template>
        </el-table-column>
        <el-table-column label="行业" prop="industry" width="140">
          <template #default="{ row }">{{ row.industry ?? '—' }}</template>
        </el-table-column>
        <el-table-column label="标签" prop="tags" min-width="120">
          <template #default="{ row }">{{ row.tags ?? '—' }}</template>
        </el-table-column>
        <el-table-column label="状态" width="100">
          <template #default="{ row }">
            <el-tag :type="row.status === 'ACTIVE' ? 'success' : 'info'" size="small">
              {{ row.status === 'ACTIVE' ? '在架' : '已下架' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="150" fixed="right">
          <template #default="{ row }">
            <el-button link type="primary" @click="openEdit(rowAsCompany(row))">编辑</el-button>
            <el-button link :type="row.status === 'ACTIVE' ? 'warning' : 'success'" @click="toggleStatus(rowAsCompany(row))">
              {{ row.status === 'ACTIVE' ? '下架' : '上架' }}
            </el-button>
          </template>
        </el-table-column>
        <template #empty>
          <el-empty description="还没有公司，点右上角新建" :image-size="60" />
        </template>
      </el-table>

      <p class="hint">
        用户端只显示<b>在架</b>公司。公司×岗位的关联编辑界面不在本期（后端接口
        <code>PUT /api/v1/admin/platform/companies/{id}/positions/{positionId}</code> 已具备）。
      </p>
      <p class="hint">岗位字典当前共 {{ positions.length }} 条。</p>
    </el-card>

    <el-dialog v-model="dialogVisible" :title="isEditing ? '编辑公司' : '新建公司'" width="480">
      <el-form label-width="90px">
        <el-form-item label="公司名" required>
          <el-input v-model="form.name" placeholder="如：字节跳动" />
        </el-form-item>
        <el-form-item label="难度档位" required>
          <el-select v-model="form.tier" class="wfull">
            <el-option v-for="t in TIERS" :key="t.value" :value="t.value" :label="t.label" />
          </el-select>
        </el-form-item>
        <el-form-item label="行业">
          <el-input v-model="form.industry" placeholder="如：互联网（可选）" />
        </el-form-item>
        <el-form-item label="标签">
          <el-input v-model="form.tags" placeholder="逗号分隔（可选）" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="submitting" @click="submit">保存</el-button>
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
