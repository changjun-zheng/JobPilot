<script setup lang="ts">
import { reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, type FormInstance, type FormRules } from 'element-plus'
import * as adminApi from '@/api/admin'
import { useAdminAuthStore } from '@/stores/auth'

const route = useRoute()
const router = useRouter()
const auth = useAdminAuthStore()

const formRef = ref<FormInstance>()
const form = reactive({ username: '', password: '' })
const submitting = ref(false)

const rules: FormRules = {
  username: [{ required: true, message: '请输入管理员账号', trigger: 'blur' }],
  password: [{ required: true, message: '请输入密码', trigger: 'blur' }],
}

async function submit() {
  const el = formRef.value
  if (!el) return
  const valid = await el.validate().catch(() => false)
  if (!valid) return

  submitting.value = true
  try {
    const { accessToken } = await adminApi.adminLogin(form.username, form.password)
    auth.setToken(accessToken)
    ElMessage.success('登录成功')
    const redirect = route.query.redirect
    router.push(typeof redirect === 'string' && redirect ? redirect : { name: 'companies' })
  } catch {
    /* 失败原因由响应拦截器统一弹出 */
  } finally {
    submitting.value = false
  }
}
</script>

<template>
  <div class="login-wrap">
    <el-card class="login-card">
      <div class="brand">
        <span class="brand-name">JobPilot <span class="brand-accent">管理后台</span></span>
      </div>
      <p class="sub">平台内容维护 · 公司与面经</p>

      <el-form
        ref="formRef"
        :model="form"
        :rules="rules"
        label-position="top"
        @submit.prevent="submit"
      >
        <el-form-item label="管理员账号" prop="username">
          <el-input v-model="form.username" placeholder="管理员账号" autocomplete="username" />
        </el-form-item>
        <el-form-item label="密码" prop="password">
          <el-input
            v-model="form.password"
            type="password"
            show-password
            placeholder="请输入密码"
            autocomplete="current-password"
            @keyup.enter="submit"
          />
        </el-form-item>
        <el-button type="warning" class="submit-btn" :loading="submitting" @click="submit">
          登录
        </el-button>
      </el-form>

      <p class="hint">
        管理面需后端配置 <code>JOBPILOT_ADMIN_*</code> 环境变量后启用；未配置时登录不可用。
      </p>
    </el-card>
  </div>
</template>

<style scoped>
.login-wrap {
  min-height: 100vh;
  display: flex;
  align-items: center;
  justify-content: center;
  padding: 24px;
}
.login-card {
  width: 100%;
  max-width: 400px;
}
.brand {
  text-align: center;
  margin-bottom: 4px;
}
.brand-name {
  font-weight: 700;
  font-size: 22px;
  color: #0f1115;
}
.brand-accent {
  color: #d97706;
}
.sub {
  text-align: center;
  color: #5b6472;
  font-size: 13px;
  margin: 4px 0 20px;
}
.submit-btn {
  width: 100%;
}
.hint {
  margin: 16px 0 0;
  font-size: 12px;
  color: #94a3b8;
  line-height: 1.7;
}
.hint code {
  font-family: Consolas, monospace;
}
</style>
