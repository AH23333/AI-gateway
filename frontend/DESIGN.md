# AI Gateway 前端规划文档

## 一、技术选型

| 类别     | 技术                       | 选型理由                               |
| -------- | -------------------------- | -------------------------------------- |
| 框架     | Vue 3.4+ (Composition API) | 主流响应式框架，生态成熟               |
| 构建     | Vite 5                     | 极速冷启动，HMR 热更新                 |
| 语言     | TypeScript                 | 类型安全，减少运行时错误               |
| 组件库   | Element Plus               | 中文社区最活跃的 Vue3 组件库，文档完善 |
| 路由     | Vue Router 4               | SPA 路由管理                           |
| 状态管理 | Pinia                      | Vue3 官方推荐，简洁的 API              |
| HTTP     | Axios                      | 拦截器支持 Token 自动刷新              |
| Markdown | markdown-it + highlight.js | AI 回复渲染代码块+语法高亮             |
| 图标     | @element-plus/icons-vue    | 与 Element Plus 配套                   |
| 日期     | dayjs                      | 轻量，2KB，替代 moment                 |
| 代码规范 | ESLint + Prettier          | 保证代码风格统一                       |

## 二、项目结构

```
ai-gateway-frontend/
├── index.html
├── package.json
├── vite.config.ts
├── tsconfig.json
├── .env.development          # 开发环境变量（API_BASE）
├── .env.production           # 生产环境变量
│
├── public/
│   └── favicon.ico
│
└── src/
    ├── main.ts               # 入口：挂载 App、注册插件
    ├── App.vue               # 根组件：布局路由出口
    │
    ├── api/                  # API 层
    │   ├── request.ts        # Axios 实例 + 拦截器（Token 刷新）
    │   ├── auth.ts           # 认证接口
    │   ├── chat.ts           # 对话接口
    │   ├── admin.ts          # 管理后台接口
    │   └── types.ts          # 接口类型定义
    │
    ├── router/
    │   └── index.ts          # 路由配置 + 导航守卫
    │
    ├── stores/               # Pinia 状态管理
    │   ├── user.ts           # 用户信息 + Token
    │   ├── chat.ts           # 对话消息 + 模型列表
    │   └── app.ts            # 全局 UI 状态（侧边栏、主题）
    │
    ├── views/                # 页面组件
    │   ├── LoginView.vue     # 登录/注册页
    │   ├── DashboardView.vue # 仪表盘首页
    │   ├── ChatView.vue      # AI 对话（核心页面）
    │   ├── ApiKeysView.vue   # API Key 管理
    │   ├── UsageView.vue     # 用量统计
    │   └── admin/
    │       ├── UsersView.vue # 用户管理
    │       └── ModelsView.vue# 模型管理
    │
    ├── components/           # 公共组件
    │   ├── layout/
    │   │   ├── AppLayout.vue     # 主布局（侧边栏+顶栏+内容）
    │   │   ├── Sidebar.vue       # 侧边栏导航
    │   │   └── Navbar.vue        # 顶部栏（用户信息、退出）
    │   ├── chat/
    │   │   ├── ChatMessage.vue   # 单条消息气泡
    │   │   ├── ChatInput.vue     # 输入框（模型选择+发送）
    │   │   ├── ModelSelector.vue # 模型下拉选择器
    │   │   └── MarkdownContent.vue # Markdown 渲染组件
    │   └── common/
    │       ├── StatCard.vue      # 统计卡片
    │       └── EmptyState.vue    # 空状态占位
    │
    ├── composables/          # 组合式函数
    │   └── useChat.ts        # 对话逻辑封装
    │
    ├── utils/
    │   └── format.ts         # 格式化工具（数字、日期、文件大小）
    │
    └── styles/
        ├── variables.css     # CSS 变量（主题色、间距）
        └── global.css        # 全局样式重置
```

## 三、路由设计

| 路径            | 页面          | 权限   | 说明                                 |
| --------------- | ------------- | ------ | ------------------------------------ |
| `/login`        | LoginView     | 公开   | 登录/注册，已登录自动跳转 `/`        |
| `/`             | DashboardView | 需登录 | 仪表盘：统计卡片、最近调用、系统信息 |
| `/chat`         | ChatView      | 需登录 | AI 对话：消息列表、输入框、模型选择  |
| `/api-keys`     | ApiKeysView   | 需登录 | API Key 的创建、查看、吊销           |
| `/usage`        | UsageView     | 需登录 | 用量统计表格 + 分页                  |
| `/admin/users`  | UsersView     | 需登录 | 管理后台：用户列表、禁用/启用        |
| `/admin/models` | ModelsView    | 需登录 | 管理后台：模型列表、启用/禁用        |

## 四、组件树

```
App.vue
├── LoginView              (路由 /login)
│
└── AppLayout              (路由 /, /chat, /api-keys, /usage, /admin/*)
    ├── Sidebar
    │   ├── Logo + 品牌名
    │   ├── NavItem × 5    (仪表盘/对话/APIKey/用量/管理)
    │   └── UserInfo        (头像+用户名+退出)
    │
    ├── Navbar (可选：面包屑)
    │
    └── <router-view>
        ├── DashboardView
        │   ├── StatCard × 4
        │   ├── 最近调用表格
        │   └── 系统信息卡片
        │
        ├── ChatView
        │   ├── ChatMessage × N    (消息气泡列表)
        │   │   └── MarkdownContent (AI 回复渲染)
        │   ├── EmptyState
        │   └── ChatInput
        │       └── ModelSelector
        │
        ├── ApiKeysView
        │   ├── 创建 Key 表单
        │   └── Key 列表表格
        │
        ├── UsageView
        │   └── 用量表格 + 分页
        │
        ├── UsersView (admin)
        │   └── 用户表格 + 分页
        │
        └── ModelsView (admin)
            └── 模型表格 + 分页
```

## 五、状态管理 (Pinia)

### userStore

```typescript
{
  token: string,          // AccessToken
  refreshToken: string,   // RefreshToken
  userId: number,
  username: string,
  // 操作
  login(username, password),
  register(username, email, password),
  logout(),
  refreshToken(),
  restoreFromStorage(),   // 页面刷新恢复
}
```

### chatStore

```typescript
{
  messages: Message[],        // 当前对话消息
  models: Model[],            // 可用模型列表
  currentModel: string,      // 当前选中模型
  isSending: boolean,        // 是否正在发送
  // 操作
  sendMessage(content),
  loadModels(),
  clearMessages(),
}
```

### appStore

```typescript
{
  sidebarCollapsed: boolean,
  theme: 'light' | 'dark',
  // 操作
  toggleSidebar(),
  toggleTheme(),
}
```

## 六、API 层设计

### Axios 拦截器链

```
请求拦截器:
  1. 添加 Content-Type: application/json
  2. 添加 Authorization: Bearer {token}
  3. 记录请求开始时间

响应拦截器:
  1. 提取 data 字段
  2. 如果 401 → 尝试 refreshToken → 重试原请求
  3. 如果 refresh 也失败 → 清空 Token → 跳转登录页
  4. 统一错误提示（ElMessage.error）
```

### 接口清单

```typescript
// auth
POST /auth/register        { username, email, password } → TokenResponse
POST /auth/login           { username, password }         → TokenResponse
POST /auth/refresh         { refreshToken }               → TokenResponse
POST /auth/logout          { refreshToken }               → void

// chat
POST /v1/chat/completions  { model, messages }            → ChatResponse

// admin
GET  /admin/users          ?page&size                     → Page<User>
PUT  /admin/users/:id/status { status }                   → void
PUT  /admin/users/:id/quota  { dailyQuota, monthlyQuota } → void
GET  /admin/models         ?page&size                     → Page<Model>
POST /admin/models         ModelConfig                    → void
PUT  /admin/models/:id     ModelConfig                    → void
PUT  /admin/models/:id/status { status }                  → void
POST /admin/apikeys        { name }                       → ApiKey
GET  /admin/apikeys        ?page&size                     → Page<ApiKey>
PUT  /admin/apikeys/:id/revoke                             → void
GET  /admin/usage          ?page&size                     → Page<Usage>
```

## 七、视觉效果对标

对标产品：ChatGPT / Poe / 通义千问 / 文心一言

| 视觉要素 | 设计决策                                        |
| -------- | ----------------------------------------------- |
| 配色     | 主色 #6366F1（Indigo），浅色/深色双主题         |
| 布局     | 左侧导航 + 右侧内容，对话页面居中最大宽度 800px |
| 字体     | 系统字体栈，代码块用 JetBrains Mono             |
| 消息气泡 | 用户消息右对齐紫色背景，AI 消息左对齐灰色背景   |
| Markdown | 标题、列表、代码块、表格完整支持                |
| 代码块   | 深色背景 + 语法高亮 + 一键复制按钮              |
| 加载态   | 打字机动画（三个点跳动），模拟流式输出          |
| 空状态   | 品牌 Logo + 引导文案                            |
| 过渡动画 | 页面切换 fade，消息出现 slide-up                |
| 响应式   | 移动端侧边栏折叠为汉堡菜单                      |

## 八、开发阶段

| 阶段          | 内容                              | 文件                                              |
| ------------- | --------------------------------- | ------------------------------------------------- |
| 1. 项目初始化 | Vite + Vue3 + TS + Element Plus   | package.json, vite.config.ts, main.ts             |
| 2. 基础设施   | 路由、状态管理、Axios 封装、布局  | router/, stores/, api/, layout/                   |
| 3. 认证模块   | 登录/注册页面 + Token 管理        | LoginView, userStore, auth.ts                     |
| 4. 仪表盘     | 统计卡片 + 最近调用               | DashboardView, StatCard                           |
| 5. AI 对话 ⭐ | 消息列表 + 输入框 + Markdown 渲染 | ChatView, ChatMessage, ChatInput, MarkdownContent |
| 6. API Key    | 创建/查看/吊销                    | ApiKeysView                                       |
| 7. 用量统计   | 表格 + 分页                       | UsageView                                         |
| 8. 管理后台   | 用户管理 + 模型管理               | UsersView, ModelsView                             |
| 9. 优化       | 暗色主题、响应式、动画            | 全局样式+组件微调                                 |
