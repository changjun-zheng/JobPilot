<script setup lang="ts">
import { reactive, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, type FormInstance, type FormRules } from 'element-plus'
import * as authApi from '@/api/auth'
import { useAuthStore } from '@/stores/auth'

const route = useRoute()
const router = useRouter()
const auth = useAuthStore()

const tab = ref<'login' | 'register'>('login')
const submitting = ref(false)

// ── 登录 ──────────────────────────────────────────────
const loginRef = ref<FormInstance>()
const loginForm = reactive({ email: '', password: '' })
const loginRules: FormRules = {
  email: [
    { required: true, message: '请输入邮箱', trigger: 'blur' },
    { type: 'email', message: '邮箱格式不正确', trigger: 'blur' },
  ],
  password: [{ required: true, message: '请输入密码', trigger: 'blur' }],
}

// ── 注册 ──────────────────────────────────────────────
const registerRef = ref<FormInstance>()
const registerForm = reactive({ email: '', password: '', confirm: '', consent: false })

const validateConfirm = (_rule: unknown, value: string, callback: (error?: Error) => void) => {
  if (value !== registerForm.password) {
    callback(new Error('两次输入的密码不一致'))
  } else {
    callback()
  }
}

const registerRules: FormRules = {
  email: [
    { required: true, message: '请输入邮箱', trigger: 'blur' },
    { type: 'email', message: '邮箱格式不正确', trigger: 'blur' },
  ],
  password: [
    { required: true, message: '请输入密码', trigger: 'blur' },
    { min: 8, message: '密码至少 8 位', trigger: 'blur' },
  ],
  confirm: [
    { required: true, message: '请再次输入密码', trigger: 'blur' },
    { validator: validateConfirm, trigger: 'blur' },
  ],
}

// 隐私政策：注册前必须可见
const privacy = ref<authApi.PrivacyNotice | null>(null)
const showPrivacy = ref(false)

watch(tab, async (value) => {
  if (value === 'register' && !privacy.value) {
    try {
      privacy.value = await authApi.getPrivacyNotice()
    } catch {
      /* 拦截器已弹错误提示；隐私政策拿不到时注册会被 consent 校验挡住 */
    }
  }
})

function redirectAfterAuth() {
  const redirect = route.query.redirect
  router.push(typeof redirect === 'string' && redirect ? redirect : { name: 'chat' })
}

async function submitLogin() {
  const form = loginRef.value
  if (!form) return
  const valid = await form.validate().catch(() => false)
  if (!valid) return

  submitting.value = true
  try {
    const { accessToken } = await authApi.login(loginForm.email, loginForm.password)
    auth.setToken(accessToken)
    ElMessage.success('登录成功')
    redirectAfterAuth()
  } catch {
    /* 失败原因（邮箱/密码不正确等）由响应拦截器统一弹出 */
  } finally {
    submitting.value = false
  }
}

async function submitRegister() {
  const form = registerRef.value
  if (!form) return
  const valid = await form.validate().catch(() => false)
  if (!valid) return
  if (!registerForm.consent) {
    ElMessage.warning('请先阅读并同意隐私政策与数据用途说明')
    return
  }

  submitting.value = true
  try {
    const { accessToken } = await authApi.register(registerForm.email, registerForm.password)
    auth.setToken(accessToken)
    ElMessage.success('注册成功，已自动登录')
    redirectAfterAuth()
  } catch {
    /* 已注册 / 密码太短等由响应拦截器统一弹出 */
  } finally {
    submitting.value = false
  }
}
</script>

<template>
  <div class="login-wrap">
    <el-card class="login-card">
      <div class="brand">
        <img src="/logo-mark.svg" alt="" width="40" height="40" class="brand-mark" />
        <span class="brand-name"><span class="brand-accent">Job</span>Pilot</span>
      </div>
      <p class="sub">求职 Copilot · 你的知识库与投递助手</p>

      <el-tabs v-model="tab" stretch>
        <el-tab-pane label="登录" name="login">
          <el-form
            ref="loginRef"
            :model="loginForm"
            :rules="loginRules"
            label-position="top"
            @submit.prevent="submitLogin"
          >
            <el-form-item label="邮箱" prop="email">
              <el-input v-model="loginForm.email" placeholder="you@example.com" autocomplete="username" />
            </el-form-item>
            <el-form-item label="密码" prop="password">
              <el-input
                v-model="loginForm.password"
                type="password"
                show-password
                placeholder="请输入密码"
                autocomplete="current-password"
                @keyup.enter="submitLogin"
              />
            </el-form-item>
            <el-button type="primary" class="submit" :loading="submitting" @click="submitLogin">
              登录
            </el-button>
          </el-form>
        </el-tab-pane>

        <el-tab-pane label="注册" name="register">
          <el-form
            ref="registerRef"
            :model="registerForm"
            :rules="registerRules"
            label-position="top"
            @submit.prevent="submitRegister"
          >
            <el-form-item label="邮箱" prop="email">
              <el-input v-model="registerForm.email" placeholder="you@example.com" autocomplete="username" />
            </el-form-item>
            <el-form-item label="密码" prop="password">
              <el-input
                v-model="registerForm.password"
                type="password"
                show-password
                placeholder="至少 8 位"
                autocomplete="new-password"
              />
            </el-form-item>
            <el-form-item label="确认密码" prop="confirm">
              <el-input
                v-model="registerForm.confirm"
                type="password"
                show-password
                placeholder="再次输入密码"
                autocomplete="new-password"
              />
            </el-form-item>

            <el-checkbox v-model="registerForm.consent" class="consent">
              我已阅读并同意
              <el-link type="primary" :underline="false" @click="showPrivacy = !showPrivacy">
                隐私政策与数据用途说明
              </el-link>
            </el-checkbox>
            <el-collapse-transition>
              <div v-show="showPrivacy" class="privacy-box">
                <div class="privacy-version" v-if="privacy">版本 {{ privacy.version }}</div>
                <pre class="privacy-text">{{ privacy?.text ?? '加载中…' }}</pre>
              </div>
            </el-collapse-transition>

            <el-button type="primary" class="submit" :loading="submitting" @click="submitRegister">
              注册并登录
            </el-button>
          </el-form>
        </el-tab-pane>
      </el-tabs>
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
  width: 440px;
}
.brand {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 8px;
}
.brand-mark {
  display: block;
}
.brand-name {
  font-size: 22px;
  font-weight: 700;
  letter-spacing: -0.01em;
  color: #0f1115;
}
.brand-accent {
  color: #0b5fff;
}
.sub {
  margin: 4px 0 18px;
  text-align: center;
  color: #5b6472;
  font-size: 13px;
}
.submit {
  width: 100%;
  margin-top: 8px;
}
.consent {
  margin-bottom: 4px;
}
.privacy-box {
  margin: 8px 0;
  padding: 10px 12px;
  background: #f6f7fb;
  border: 1px solid #e6e9f0;
  border-radius: 8px;
}
.privacy-version {
  font-size: 12px;
  color: #5b6472;
  margin-bottom: 6px;
}
.privacy-text {
  margin: 0;
  max-height: 160px;
  overflow: auto;
  white-space: pre-wrap;
  word-break: break-word;
  font-family: inherit;
  font-size: 12.5px;
  color: #1f2430;
}
</style>
