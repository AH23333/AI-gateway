import { createRouter, createWebHistory } from 'vue-router'
import { useUserStore } from '@/stores/user'

const router = createRouter({
  history: createWebHistory(),
  routes: [
    {
      path: '/login',
      name: 'login',
      component: () => import('@/views/LoginView.vue'),
      meta: { title: '登录', noAuth: true },
    },
    {
      path: '/',
      component: () => import('@/components/layout/AppLayout.vue'),
      redirect: '/dashboard',
      children: [
        {
          path: 'dashboard',
          name: 'dashboard',
          component: () => import('@/views/DashboardView.vue'),
          meta: { title: '仪表盘', icon: 'Odometer' },
        },
        {
          path: 'chat',
          name: 'chat',
          component: () => import('@/views/ChatView.vue'),
          meta: { title: 'AI 对话', icon: 'ChatDotRound' },
        },
        {
          path: 'history',
          name: 'history',
          component: () => import('@/views/HistoryView.vue'),
          meta: { title: '对话历史', icon: 'Clock' },
        },
        {
          path: 'api-keys',
          name: 'apiKeys',
          component: () => import('@/views/ApiKeysView.vue'),
          meta: { title: 'API Key', icon: 'Key' },
        },
        {
          path: 'usage',
          name: 'usage',
          component: () => import('@/views/UsageView.vue'),
          meta: { title: '用量统计', icon: 'DataAnalysis' },
        },
        {
          path: 'admin/users',
          name: 'adminUsers',
          component: () => import('@/views/admin/UsersView.vue'),
          meta: { title: '用户管理', icon: 'UserFilled', requireAdmin: true },
        },
        {
          path: 'admin/models',
          name: 'adminModels',
          component: () => import('@/views/admin/ModelsView.vue'),
          meta: { title: '模型管理', icon: 'Cpu', requireAdmin: true },
        },
        {
          path: 'admin/routing',
          name: 'adminRouting',
          component: () => import('@/views/admin/RoutingView.vue'),
          meta: { title: '智能路由', icon: 'Compass', requireAdmin: true },
        },
        {
          path: 'admin/monitor',
          name: 'adminMonitor',
          component: () => import('@/views/admin/MonitorView.vue'),
          meta: { title: '系统监控', icon: 'Monitor', requireAdmin: true },
        },
        {
          path: 'admin/settings',
          name: 'adminSettings',
          component: () => import('@/views/admin/SettingsView.vue'),
          meta: { title: '系统设置', icon: 'Setting', requireAdmin: true },
        },
        {
          path: 'admin/audit-logs',
          name: 'adminAuditLogs',
          component: () => import('@/views/admin/AuditLogView.vue'),
          meta: { title: '操作审计', icon: 'Tickets', requireAdmin: true },
        },
      ],
    },
  ],
})

// 导航守卫：未登录跳转登录页，管理员路由校验角色
// 访问受保护页面前，主动检查 token 是否过期并尝试刷新
router.beforeEach(async (to, _from, next) => {
  document.title = (to.meta.title as string) || 'AI Gateway'
  if (to.meta.noAuth) {
    next()
    return
  }
  const userStore = useUserStore()
  if (!userStore.token) {
    const ok = await userStore.initAuth()
    if (!ok) {
      next('/login')
      return
    }
  }
  if (!userStore.token) {
    next('/login')
  } else if (to.meta.requireAdmin && !userStore.isAdmin) {
    next('/dashboard')
  } else {
    next()
  }
})

export default router