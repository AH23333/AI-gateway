<template>
    <div class="page-container">
        <PageHeader title="操作审计" subtitle="管理端写操作留痕（who / when / what / 结果）" />

        <!-- 筛选栏 -->
        <el-card shadow="never" class="filter-card">
            <el-form inline class="filter-form" @submit.prevent="handleSearch">
                <el-form-item label="操作人">
                    <el-input v-model="filters.username" placeholder="用户名（精确）" clearable style="width: 140px"
                        @keyup.enter="handleSearch" @clear="handleSearch" />
                </el-form-item>
                <el-form-item label="动作">
                    <el-select v-model="filters.action" placeholder="全部动作" clearable style="width: 180px"
                        @change="handleSearch">
                        <el-option v-for="a in actionOptions" :key="a.value" :label="a.label" :value="a.value" />
                    </el-select>
                </el-form-item>
                <el-form-item label="结果">
                    <el-select v-model="filters.success" placeholder="全部" clearable style="width: 100px"
                        @change="handleSearch">
                        <el-option label="成功" :value="1" />
                        <el-option label="失败" :value="0" />
                    </el-select>
                </el-form-item>
                <el-form-item label="时间范围">
                    <el-date-picker v-model="filters.range" type="datetimerange"
                        start-placeholder="开始时间" end-placeholder="结束时间"
                        :default-time="[new Date(2000, 0, 1, 0, 0, 0), new Date(2000, 0, 1, 23, 59, 59)]"
                        style="width: 340px" @change="handleSearch" />
                </el-form-item>
                <el-form-item>
                    <el-button type="primary" @click="handleSearch">查询</el-button>
                    <el-button @click="handleReset">重置</el-button>
                </el-form-item>
            </el-form>
        </el-card>

        <!-- 审计列表 -->
        <el-card shadow="never" class="table-card">
            <el-table :data="data" v-loading="loading" stripe row-key="id">
                <el-table-column prop="id" label="ID" width="70" />
                <el-table-column prop="createdAt" label="时间" width="165" />
                <el-table-column label="操作人" width="130">
                    <template #default="{ row }">
                        <span>{{ row.username }}</span>
                        <span class="muted mono"> #{{ row.userId }}</span>
                    </template>
                </el-table-column>
                <el-table-column label="动作" min-width="170">
                    <template #default="{ row }">
                        <el-tag size="small" effect="plain" :type="actionTagType(row.action)">{{ row.actionDesc }}</el-tag>
                    </template>
                </el-table-column>
                <el-table-column label="请求" min-width="240">
                    <template #default="{ row }">
                        <el-tag size="small" effect="plain" :type="methodTagType(row.httpMethod)">{{ row.httpMethod }}</el-tag>
                        <span class="mono uri-text">{{ row.uri }}</span>
                    </template>
                </el-table-column>
                <el-table-column label="参数摘要" min-width="220" show-overflow-tooltip>
                    <template #default="{ row }">
                        <span class="mono">{{ row.params || '-' }}</span>
                    </template>
                </el-table-column>
                <el-table-column label="结果" width="100" align="center">
                    <template #default="{ row }">
                        <el-tooltip v-if="row.success === 0 && row.errorMsg" :content="row.errorMsg" placement="top">
                            <el-tag type="danger" size="small" effect="plain">失败</el-tag>
                        </el-tooltip>
                        <el-tag v-else type="success" size="small" effect="plain">成功</el-tag>
                    </template>
                </el-table-column>
                <el-table-column label="耗时" width="90" align="right">
                    <template #default="{ row }">{{ row.costMs != null ? row.costMs + ' ms' : '-' }}</template>
                </el-table-column>
                <el-table-column prop="ip" label="IP" width="120" />
            </el-table>
            <div class="pagination-wrapper">
                <el-pagination v-model:current-page="page" v-model:page-size="size" :total="total"
                    :page-sizes="[10, 20, 50]" layout="total, sizes, prev, pager, next" @change="refresh" />
            </div>
        </el-card>
    </div>
</template>

<script setup lang="ts">
import { reactive, onMounted } from 'vue'
import PageHeader from '@/components/common/PageHeader.vue'
import { useTable } from '@/composables/useTable'
import { getAuditLogs } from '@/api/admin'
import type { AuditLog, AuditLogQuery } from '@/api/types'

/** 动作枚举（与后端 @Audited 声明一一对应） */
const actionOptions = [
    { value: 'USER_QUOTA', label: '调整用户配额' },
    { value: 'USER_STATUS', label: '启用/禁用账号' },
    { value: 'USER_ROLE', label: '变更用户角色' },
    { value: 'APIKEY_REVOKE', label: '吊销 API Key' },
    { value: 'MODEL_CREATE', label: '新增模型' },
    { value: 'MODEL_UPDATE', label: '编辑模型' },
    { value: 'MODEL_DELETE', label: '删除模型' },
    { value: 'MODEL_PRICE_ADD', label: '新增价格版本' },
    { value: 'ROUTING_SWITCH', label: '路由总开关切换' },
    { value: 'ROUTING_WEIGHTS', label: '路由打分权重更新' },
    { value: 'SETTING_UPDATE', label: '更新系统设置' },
]

const filters = reactive<{
    username: string
    action: string
    success: number | undefined
    range: [Date, Date] | null
}>({
    username: '',
    action: '',
    success: undefined,
    range: null,
})

/** 组装检索参数（时间范围 → yyyy-MM-dd HH:mm:ss） */
function buildQuery(p: { page: number; size: number }): AuditLogQuery {
    const q: AuditLogQuery = { page: p.page, size: p.size }
    if (filters.username.trim()) q.username = filters.username.trim()
    if (filters.action) q.action = filters.action
    if (filters.success !== undefined && filters.success !== null) q.success = filters.success
    if (filters.range && filters.range.length === 2) {
        q.startTime = formatTime(filters.range[0])
        q.endTime = formatTime(filters.range[1])
    }
    return q
}

const { data, loading, page, size, total, refresh, reset } = useTable<AuditLog>({
    fetchFn: (p) => getAuditLogs(buildQuery(p)),
})

function handleSearch() {
    page.value = 1
    refresh()
}

function handleReset() {
    filters.username = ''
    filters.action = ''
    filters.success = undefined
    filters.range = null
    reset()
}

function formatTime(d: Date): string {
    const p = (n: number) => String(n).padStart(2, '0')
    return `${d.getFullYear()}-${p(d.getMonth() + 1)}-${p(d.getDate())} ${p(d.getHours())}:${p(d.getMinutes())}:${p(d.getSeconds())}`
}

function actionTagType(action: string): 'primary' | 'warning' | 'danger' | 'info' {
    if (action === 'SETTING_UPDATE' || action === 'ROUTING_SWITCH' || action === 'ROUTING_WEIGHTS') return 'warning'
    if (action === 'MODEL_DELETE' || action === 'APIKEY_REVOKE') return 'danger'
    if (action.startsWith('MODEL_')) return 'primary'
    return 'info'
}

function methodTagType(method: string): 'success' | 'warning' | 'danger' | 'info' {
    if (method === 'POST') return 'success'
    if (method === 'PUT') return 'warning'
    if (method === 'DELETE') return 'danger'
    return 'info'
}

onMounted(refresh)
</script>

<style scoped>
.filter-card {
    margin-bottom: 20px;
}

.filter-form :deep(.el-form-item) {
    margin-bottom: 0;
}

.mono {
    font-family: var(--font-mono);
    font-size: 12px;
}

.muted {
    color: var(--color-text-light);
}

.uri-text {
    margin-left: 6px;
}

.pagination-wrapper {
    display: flex;
    justify-content: flex-end;
}
</style>
