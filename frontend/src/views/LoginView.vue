<template>
  <div class="login-page">
    <div class="login-container">
      <!-- 左侧：网关身份区（终端风格） -->
      <div class="login-brand">
        <div class="brand-content">
          <div class="brand-head">
            <div class="brand-mark">
              <svg viewBox="0 0 32 32" fill="none">
                <path d="M5 16h7M20 16h7M20 16l5.5-6.5M20 16l5.5 6.5" stroke="#2dd4bf" stroke-width="1.6" stroke-linecap="round"/>
                <rect x="12.5" y="12.5" width="7" height="7" rx="1.2" stroke="#2dd4bf" stroke-width="1.6" fill="rgba(45,212,191,0.12)"/>
                <circle cx="5" cy="16" r="1.8" fill="#2dd4bf"/>
                <circle cx="27" cy="16" r="1.8" fill="#2dd4bf"/>
                <circle cx="25.5" cy="9.5" r="1.4" fill="#2dd4bf" fill-opacity="0.7"/>
                <circle cx="25.5" cy="22.5" r="1.4" fill="#2dd4bf" fill-opacity="0.7"/>
              </svg>
            </div>
            <div class="brand-name mono">{{ publicSettings?.siteName ?? 'AI Gateway' }}</div>
          </div>
          <p class="brand-desc">企业级 AI 接口网关管理平台<br/>多模型统一接入 · 智能路由 · 计量计费</p>
          <div class="brand-term mono">
            <div class="term-line"><span class="term-prompt">$</span>curl /v1/chat/completions \</div>
            <div class="term-line term-indent">-H "Authorization: Bearer sk-•••" \</div>
            <div class="term-line term-indent">-d '{"model":"auto","messages":[…]}'</div>
            <div class="term-line term-ok">→ 200 OK · glm-4.5-flash · 342ms</div>
            <div class="term-line term-ok">→ routed: auto · circuit: closed</div>
          </div>
          <div class="brand-meta mono">
            <span>Java 21</span>
            <span>Spring Boot 3.2</span>
            <span>MySQL 8</span>
            <span>Redis</span>
          </div>
        </div>
      </div>

      <!-- 右侧表单区 -->
      <div class="login-form-area">
        <div class="form-card">
          <div class="form-header">
            <div class="form-kicker mono">CONSOLE ACCESS</div>
            <h2>{{ mode === 'login' ? '欢迎回来' : '创建账号' }}</h2>
            <p>{{ mode === 'login' ? '登录您的账号以继续' : '注册一个新账号开始使用' }}</p>
          </div>

          <div v-if="publicSettings?.announcement" class="announce-banner">{{ publicSettings.announcement }}</div>

          <div class="mode-tabs">
            <button :class="{ active: mode === 'login' }" @click="switchMode('login')">登录</button>
            <button :class="{ active: mode === 'register' }" @click="switchMode('register')">注册</button>
          </div>

          <!-- 登录表单 -->
          <el-form v-if="mode === 'login'" ref="loginFormRef" :model="loginForm" :rules="loginRules" @submit.prevent="handleLogin">
            <el-form-item prop="username">
              <el-input
                v-model="loginForm.username"
                placeholder="用户名"
                :prefix-icon="User"
                size="large"
                class="custom-input"
              />
            </el-form-item>
            <el-form-item prop="password">
              <el-input
                v-model="loginForm.password"
                type="password"
                placeholder="密码"
                :prefix-icon="Lock"
                size="large"
                show-password
                class="custom-input"
                @keyup.enter="handleLogin"
              />
            </el-form-item>
            <el-button type="primary" size="large" :loading="loading" class="submit-btn" @click="handleLogin">
              登 录
            </el-button>
          </el-form>

          <!-- 注册表单 -->
          <el-form v-else ref="registerFormRef" :model="registerForm" :rules="registerRules" @submit.prevent="handleRegister">
            <el-form-item prop="username">
              <el-input
                v-model="registerForm.username"
                placeholder="用户名"
                :prefix-icon="User"
                size="large"
                class="custom-input"
              />
            </el-form-item>
            <el-form-item prop="email">
              <el-input
                v-model="registerForm.email"
                placeholder="邮箱地址"
                :prefix-icon="Message"
                size="large"
                class="custom-input"
              />
            </el-form-item>
            <el-form-item prop="password">
              <el-input
                v-model="registerForm.password"
                type="password"
                placeholder="密码（至少6位）"
                :prefix-icon="Lock"
                size="large"
                show-password
                class="custom-input"
              />
            </el-form-item>
            <el-form-item prop="confirmPassword">
              <el-input
                v-model="registerForm.confirmPassword"
                type="password"
                placeholder="确认密码"
                :prefix-icon="Lock"
                size="large"
                show-password
                class="custom-input"
                @keyup.enter="handleRegister"
              />
            </el-form-item>
            <el-button type="primary" size="large" :loading="loading" class="submit-btn" @click="handleRegister">
              注 册
            </el-button>
          </el-form>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref, reactive, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { User, Lock, Message } from '@element-plus/icons-vue'
import type { FormInstance, FormRules } from 'element-plus'
import { useUserStore } from '@/stores/user'
import { getPublicSettings } from '@/api/auth'
import type { PublicSettings } from '@/api/types'

const router = useRouter()
const userStore = useUserStore()

// 站点公开设置（免认证 /settings/public）：站点名 + 公告横幅
const publicSettings = ref<PublicSettings | null>(null)
onMounted(async () => {
  const resp = await getPublicSettings().catch(() => null)
  publicSettings.value = resp?.data ?? null
  if (publicSettings.value?.siteName) {
    document.title = publicSettings.value.siteName
  }
})

const mode = ref<'login' | 'register'>('login')
const loading = ref(false)
const loginFormRef = ref<FormInstance>()
const registerFormRef = ref<FormInstance>()

const loginForm = reactive({ username: '', password: '' })
const registerForm = reactive({ username: '', email: '', password: '', confirmPassword: '' })

const loginRules: FormRules = {
  username: [{ required: true, message: '请输入用户名', trigger: 'blur' }],
  password: [{ required: true, message: '请输入密码', trigger: 'blur' }],
}

const registerRules: FormRules = {
  username: [
    { required: true, message: '请输入用户名', trigger: 'blur' },
    { min: 3, max: 20, message: '用户名长度 3-20 位', trigger: 'blur' },
  ],
  email: [
    { required: true, message: '请输入邮箱', trigger: 'blur' },
    { type: 'email', message: '请输入正确的邮箱格式', trigger: 'blur' },
  ],
  password: [
    { required: true, message: '请输入密码', trigger: 'blur' },
    { min: 6, message: '密码至少6位', trigger: 'blur' },
  ],
  confirmPassword: [
    { required: true, message: '请确认密码', trigger: 'blur' },
    {
      validator: (_rule, value, callback) => {
        if (value !== registerForm.password) callback(new Error('两次输入的密码不一致'))
        else callback()
      },
      trigger: 'blur',
    },
  ],
}

function switchMode(m: 'login' | 'register') {
  mode.value = m
}

async function handleLogin() {
  const valid = await loginFormRef.value?.validate().catch(() => false)
  if (!valid) return
  loading.value = true
  try {
    await userStore.login({
      username: loginForm.username.trim(),
      password: loginForm.password.trim(),
    })
    router.push('/dashboard')
  } catch { /* ignored */ } finally {
    loading.value = false
  }
}

async function handleRegister() {
  const valid = await registerFormRef.value?.validate().catch(() => false)
  if (!valid) return
  loading.value = true
  try {
    await userStore.register({
      username: registerForm.username,
      email: registerForm.email,
      password: registerForm.password,
    })
    router.push('/dashboard')
  } catch { /* ignored */ } finally {
    loading.value = false
  }
}
</script>

<style scoped>
.login-page {
  min-height: 100vh;
  display: flex;
  align-items: center;
  justify-content: center;
  position: relative;
  overflow: hidden;
  background:
    linear-gradient(rgba(255, 255, 255, 0.022) 1px, transparent 1px),
    linear-gradient(90deg, rgba(255, 255, 255, 0.022) 1px, transparent 1px),
    #0d1117;
  background-size: 32px 32px, 32px 32px, auto;
}

/* 左上角一抹青绿微光 */
.login-page::before {
  content: '';
  position: absolute;
  top: -180px;
  left: -120px;
  width: 480px;
  height: 480px;
  border-radius: 50%;
  background: radial-gradient(circle, rgba(20, 184, 166, 0.13), transparent 65%);
  pointer-events: none;
}

.login-container {
  display: flex;
  max-width: 940px;
  width: 100%;
  min-height: 540px;
  background: #11161d;
  border: 1px solid #232b35;
  border-radius: var(--radius-xl);
  overflow: hidden;
  box-shadow: var(--shadow-xl);
  z-index: 1;
  margin: 20px;
}

/* 左侧品牌区 */
.login-brand {
  flex: 1.05;
  background: #0d1117;
  border-right: 1px solid #232b35;
  display: flex;
  align-items: center;
  justify-content: center;
  padding: 48px 40px;
}

.brand-content {
  width: 100%;
  max-width: 340px;
}

.brand-head {
  display: flex;
  align-items: center;
  gap: 12px;
  margin-bottom: 16px;
}

.brand-mark svg {
  width: 34px;
  height: 34px;
  display: block;
}

.brand-name {
  font-size: 19px;
  font-weight: 700;
  color: #f0f6fc;
  letter-spacing: 0.01em;
}

.brand-desc {
  font-size: 12.5px;
  color: rgba(240, 246, 252, 0.45);
  line-height: 1.8;
  margin-bottom: 24px;
}

/* 终端示例块 */
.brand-term {
  border: 1px solid #232b35;
  border-radius: var(--radius);
  background: #0a0e13;
  padding: 14px 16px;
  margin-bottom: 22px;
}

.term-line {
  font-size: 11.5px;
  line-height: 1.9;
  color: rgba(240, 246, 252, 0.55);
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.term-prompt {
  color: #2dd4bf;
  margin-right: 6px;
}

.term-indent {
  padding-left: 14px;
}

.term-ok {
  color: rgba(52, 211, 153, 0.85);
}

.brand-meta {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
}

.brand-meta span {
  font-size: 10.5px;
  color: rgba(240, 246, 252, 0.4);
  border: 1px solid #232b35;
  border-radius: 3px;
  padding: 3px 8px;
  letter-spacing: 0.04em;
}

/* 右侧表单区 */
.login-form-area {
  flex: 1;
  display: flex;
  align-items: center;
  justify-content: center;
  padding: 48px 40px;
}

.form-card {
  width: 100%;
  max-width: 340px;
}

.form-header {
  margin-bottom: 24px;
}

.form-kicker {
  font-size: 10.5px;
  font-weight: 600;
  letter-spacing: 0.12em;
  color: #2dd4bf;
  margin-bottom: 8px;
}

.form-header h2 {
  font-size: 21px;
  font-weight: 700;
  color: #f0f6fc;
  margin-bottom: 6px;
}

.form-header p {
  font-size: 12.5px;
  color: rgba(240, 246, 252, 0.4);
}

/* 站点公告横幅（系统设置中心 /settings/public） */
.announce-banner {
  font-size: 12px;
  line-height: 1.7;
  color: #5eead4;
  background: rgba(45, 212, 191, 0.08);
  border: 1px solid rgba(45, 212, 191, 0.35);
  border-radius: 5px;
  padding: 9px 12px;
  margin-bottom: 18px;
  white-space: pre-wrap;
}

.mode-tabs {
  display: flex;
  gap: 2px;
  background: #0d1117;
  border: 1px solid #232b35;
  border-radius: 5px;
  padding: 3px;
  margin-bottom: 24px;
}

.mode-tabs button {
  flex: 1;
  padding: 7px;
  background: none;
  border: none;
  font-size: 12.5px;
  font-weight: 600;
  color: rgba(240, 246, 252, 0.4);
  cursor: pointer;
  border-radius: 3px;
  transition: all 0.15s;
  font-family: inherit;
}

.mode-tabs button.active {
  background: rgba(45, 212, 191, 0.14);
  color: #5eead4;
}

.submit-btn {
  width: 100%;
  height: 42px;
  font-size: 14px;
  font-weight: 600;
  border-radius: 5px;
  margin-top: 4px;
  letter-spacing: 0.02em;
}

/* 自定义输入框（深色） */
:deep(.custom-input .el-input__wrapper) {
  background: rgba(255, 255, 255, 0.04);
  border: 1px solid #2a333e;
  border-radius: 5px;
  box-shadow: none;
  transition: all 0.15s;
}

:deep(.custom-input .el-input__wrapper:hover) {
  border-color: #39434f;
}

:deep(.custom-input .el-input__wrapper.is-focus) {
  border-color: #14b8a6;
  box-shadow: 0 0 0 3px rgba(20, 184, 166, 0.12);
}

:deep(.custom-input .el-input__inner) {
  color: #f0f6fc;
}

:deep(.custom-input .el-input__inner::placeholder) {
  color: rgba(240, 246, 252, 0.28);
}

:deep(.custom-input .el-input__prefix) {
  color: rgba(240, 246, 252, 0.3);
}

/* 浏览器自动填充（已保存的账号密码）会以自带白底覆盖输入框样式，
   且普通 background 无法覆盖。双层内阴影：上层复刻输入框本身的
   rgba(255,255,255,0.04)，底层为面板合成后的基色，视觉上与未回填时一致 */
:deep(.custom-input .el-input__inner:-webkit-autofill),
:deep(.custom-input .el-input__inner:-webkit-autofill:hover),
:deep(.custom-input .el-input__inner:-webkit-autofill:focus) {
  -webkit-box-shadow:
    0 0 0 1000px rgba(255, 255, 255, 0.04) inset,
    0 0 0 1000px #11161d inset;
  -webkit-text-fill-color: #f0f6fc;
  caret-color: #f0f6fc;
}

/* Firefox 的自动填充背景可直接覆盖 */
:deep(.custom-input .el-input__inner:autofill) {
  background-color: transparent;
  color: #f0f6fc;
}

@media (max-width: 768px) {
  .login-container {
    flex-direction: column;
    max-width: 420px;
  }
  .login-brand {
    padding: 32px 24px;
    border-right: none;
    border-bottom: 1px solid #232b35;
  }
  .login-form-area {
    padding: 32px 24px;
  }
}
</style>
