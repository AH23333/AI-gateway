<template>
  <aside class="sidebar" :class="{ collapsed: !visible }">
    <!-- 头部：品牌 + 折叠按钮 -->
    <div class="sidebar-header">
      <div class="brand" @click="$router.push('/dashboard')">
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
        <span class="brand-text mono">AI Gateway</span>
        <span class="brand-tag mono">v2.0</span>
      </div>
      <el-button class="collapse-toggle" text size="small" @click="visible = !visible">
        <el-icon :size="16"><Fold /></el-icon>
      </el-button>
    </div>

    <!-- 新建对话 -->
    <div class="new-chat-section">
      <button class="new-chat-btn mono" @click="handleNewChat">
        <el-icon :size="15"><Plus /></el-icon>
        <span>新建对话</span>
      </button>
    </div>

    <!-- 对话历史 -->
    <div class="conversations-section">
      <div class="section-label" v-if="chatStore.conversations.length > 0">对话历史</div>
      <div class="conversations-list">
        <div
          v-for="conv in sortedConversations"
          :key="conv.id"
          class="conv-item"
          :class="{ active: conv.id === chatStore.currentConversationId }"
          @click="chatStore.switchConversation(conv.id)"
        >
          <span class="conv-glyph mono">#</span>
          <span class="conv-title">{{ conv.title }}</span>
          <el-popconfirm
            title="删除此对话？"
            width="180"
            @confirm.stop="chatStore.deleteConversation(conv.id)"
          >
            <template #reference>
              <button class="conv-delete" @click.stop><el-icon :size="13"><Delete /></el-icon></button>
            </template>
          </el-popconfirm>
        </div>
      </div>
    </div>

    <!-- 导航菜单 -->
    <nav class="sidebar-nav">
      <div class="section-label">导航</div>
      <router-link
        v-for="item in navItems"
        :key="item.path"
        :to="item.path"
        class="nav-item"
        :class="{ active: $route.path.startsWith(item.path) }"
      >
        <el-icon :size="16"><component :is="item.icon" /></el-icon>
        <span>{{ item.label }}</span>
      </router-link>
      <!-- 管理员菜单 -->
      <template v-if="userStore.isAdmin">
        <div class="section-label admin">管理</div>
        <router-link
          v-for="item in adminNavItems"
          :key="item.path"
          :to="item.path"
          class="nav-item"
          :class="{ active: $route.path.startsWith(item.path) }"
        >
          <el-icon :size="16"><component :is="item.icon" /></el-icon>
          <span>{{ item.label }}</span>
        </router-link>
      </template>
    </nav>

    <!-- 底部：网关状态 + 用户信息 -->
    <div class="sidebar-footer">
      <div class="service-status mono">
        <span class="status-dot online"></span>
        <span class="service-text">GATEWAY ONLINE</span>
        <span class="service-port">:10011</span>
      </div>
      <div class="footer-bar">
        <div class="user-info" @click="$router.push('/dashboard')">
          <el-avatar :size="26" icon="UserFilled" />
          <div class="user-meta">
            <span class="user-name">{{ userStore.username }}</span>
            <span class="user-role mono">{{ userStore.isAdmin ? 'ADMIN' : 'USER' }}</span>
          </div>
        </div>
        <el-button text class="logout-btn" @click="handleLogout" title="退出登录">
          <el-icon :size="15"><SwitchButton /></el-icon>
        </el-button>
      </div>
    </div>
  </aside>
</template>

<script setup lang="ts">
import { inject, computed, type Ref } from 'vue'
import { useRouter } from 'vue-router'
import { useUserStore } from '@/stores/user'
import { useChatStore } from '@/stores/chat'

const router = useRouter()
const userStore = useUserStore()
const chatStore = useChatStore()
const visible = inject<Ref<boolean>>('sidebarVisible')!

const sortedConversations = computed(() =>
  [...chatStore.conversations].sort((a, b) => b.updatedAt - a.updatedAt)
)

const navItems = [
  { path: '/dashboard', label: '仪表盘', icon: 'Odometer' },
  { path: '/chat', label: 'AI 对话', icon: 'ChatDotRound' },
  { path: '/history', label: '对话历史', icon: 'Clock' },
  { path: '/api-keys', label: 'API Key', icon: 'Key' },
  { path: '/usage', label: '用量统计', icon: 'DataAnalysis' },
]

const adminNavItems = [
  { path: '/admin/monitor', label: '系统监控', icon: 'Monitor' },
  { path: '/admin/routing', label: '智能路由', icon: 'Compass' },
  { path: '/admin/users', label: '用户管理', icon: 'UserFilled' },
  { path: '/admin/models', label: '模型管理', icon: 'Cpu' },
  { path: '/admin/settings', label: '系统设置', icon: 'Setting' },
]

function handleNewChat() {
  chatStore.createConversation()
  router.push('/chat')
}

async function handleLogout() {
  await userStore.logout()
  router.push('/login')
}
</script>

<style scoped>
.sidebar {
  width: var(--sidebar-width);
  height: 100vh;
  background: var(--color-bg-sidebar);
  color: #e6edf3;
  display: flex;
  flex-direction: column;
  transition: width var(--transition-normal);
  flex-shrink: 0;
  overflow: hidden;
  user-select: none;
}

.sidebar.collapsed {
  width: 0;
}

.sidebar-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 14px 12px 12px;
  border-bottom: 1px solid rgba(255, 255, 255, 0.07);
}

.brand {
  display: flex;
  align-items: center;
  gap: 9px;
  cursor: pointer;
  flex: 1;
  min-width: 0;
}

.brand-mark svg {
  width: 28px;
  height: 28px;
  display: block;
}

.brand-text {
  font-size: 13.5px;
  font-weight: 600;
  white-space: nowrap;
  letter-spacing: 0.02em;
  color: #f0f6fc;
}

.brand-tag {
  font-size: 10px;
  padding: 1px 5px;
  border: 1px solid rgba(45, 212, 191, 0.45);
  color: #2dd4bf;
  border-radius: 3px;
  line-height: 1.5;
  flex-shrink: 0;
}

.collapse-toggle {
  color: rgba(255, 255, 255, 0.45) !important;
  flex-shrink: 0;
}

.collapse-toggle:hover {
  color: #fff !important;
  background: rgba(255, 255, 255, 0.08) !important;
}

/* 新建对话 */
.new-chat-section {
  padding: 12px 12px 0;
}

.new-chat-btn {
  width: 100%;
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 7px;
  padding: 8px;
  background: var(--color-primary);
  color: #fff;
  border: none;
  border-radius: 4px;
  font-size: 12.5px;
  font-weight: 600;
  letter-spacing: 0.02em;
  cursor: pointer;
  transition: background var(--transition-fast);
}

.new-chat-btn:hover {
  background: var(--color-primary-hover);
}

/* 对话历史 */
.conversations-section {
  flex: 1;
  overflow-y: auto;
  padding: 6px 10px 0;
  min-height: 0;
}

.section-label {
  font-family: var(--font-mono);
  font-size: 10px;
  font-weight: 600;
  text-transform: uppercase;
  letter-spacing: 0.1em;
  color: rgba(255, 255, 255, 0.32);
  padding: 10px 8px 5px;
}

.section-label.admin {
  margin-top: 6px;
}

.conversations-list {
  display: flex;
  flex-direction: column;
  gap: 1px;
}

.conv-item {
  display: flex;
  align-items: center;
  gap: 7px;
  padding: 7px 8px;
  border-radius: 4px;
  border-left: 2px solid transparent;
  cursor: pointer;
  transition: background var(--transition-fast);
  color: rgba(255, 255, 255, 0.55);
  font-size: 12.5px;
}

.conv-item:hover {
  background: var(--color-bg-sidebar-item);
  color: rgba(255, 255, 255, 0.85);
}

.conv-item.active {
  background: var(--color-bg-sidebar-active);
  color: #fff;
  border-left-color: #2dd4bf;
}

.conv-glyph {
  color: rgba(255, 255, 255, 0.28);
  font-size: 11px;
  flex-shrink: 0;
}

.conv-title {
  flex: 1;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.conv-delete {
  flex-shrink: 0;
  background: none;
  border: none;
  color: rgba(255, 255, 255, 0.3);
  cursor: pointer;
  padding: 2px;
  border-radius: 3px;
  opacity: 0;
  transition: all var(--transition-fast);
  display: flex;
}

.conv-item:hover .conv-delete {
  opacity: 1;
}

.conv-delete:hover {
  color: #f87171;
  background: rgba(248, 113, 113, 0.12);
}

/* 导航 */
.sidebar-nav {
  padding: 4px 10px 10px;
  border-top: 1px solid rgba(255, 255, 255, 0.07);
  margin-top: 4px;
}

.nav-item {
  display: flex;
  align-items: center;
  gap: 9px;
  padding: 7px 8px;
  border-radius: 4px;
  border-left: 2px solid transparent;
  color: rgba(255, 255, 255, 0.55);
  text-decoration: none;
  font-size: 12.5px;
  transition: all var(--transition-fast);
  margin-bottom: 1px;
}

.nav-item:hover {
  background: var(--color-bg-sidebar-item);
  color: rgba(255, 255, 255, 0.88);
}

.nav-item.active {
  background: var(--color-bg-sidebar-active);
  color: #fff;
  border-left-color: #2dd4bf;
}

/* 底部 */
.sidebar-footer {
  padding: 10px 12px 12px;
  border-top: 1px solid rgba(255, 255, 255, 0.07);
}

.service-status {
  display: flex;
  align-items: center;
  gap: 7px;
  font-size: 10px;
  letter-spacing: 0.06em;
  margin-bottom: 10px;
}

.service-text {
  color: rgba(52, 211, 153, 0.9);
}

.service-port {
  color: rgba(255, 255, 255, 0.3);
  margin-left: auto;
}

.footer-bar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 6px;
}

.user-info {
  display: flex;
  align-items: center;
  gap: 8px;
  cursor: pointer;
  flex: 1;
  min-width: 0;
}

.user-meta {
  display: flex;
  flex-direction: column;
  overflow: hidden;
  line-height: 1.35;
}

.user-name {
  font-size: 12.5px;
  font-weight: 600;
  color: rgba(255, 255, 255, 0.88);
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.user-role {
  font-size: 10px;
  color: rgba(255, 255, 255, 0.35);
  letter-spacing: 0.08em;
}

.logout-btn {
  color: rgba(255, 255, 255, 0.4) !important;
  flex-shrink: 0;
}

.logout-btn:hover {
  color: #f87171 !important;
  background: rgba(248, 113, 113, 0.1) !important;
}
</style>
