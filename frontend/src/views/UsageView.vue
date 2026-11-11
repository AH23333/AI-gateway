<template>
    <div class="page-container">
        <PageHeader title="用量统计" :subtitle="isAdmin ? '全站所有用户的 API 调用明细' : '我的 API 调用明细'" />
        <el-card shadow="never" class="table-card">
            <el-table :data="data" v-loading="loading" stripe>
                <el-table-column prop="traceId" label="Trace ID" min-width="150" show-overflow-tooltip>
                    <template #default="{ row }">
                        <code class="trace-code">{{ row.traceId }}</code>
                    </template>
                </el-table-column>
                <el-table-column v-if="isAdmin" prop="userId" label="用户ID" width="80" align="right" />
                <el-table-column prop="modelCode" label="模型" min-width="130" />
                <el-table-column prop="promptTokens" label="输入 Tokens" width="105" align="right" />
                <el-table-column prop="completionTokens" label="输出 Tokens" width="105" align="right" />
                <el-table-column prop="totalTokens" label="总 Tokens" width="100" align="right">
                    <template #default="{ row }">
                        <el-tag size="small" type="primary" effect="plain">{{ row.totalTokens }}</el-tag>
                    </template>
                </el-table-column>
                <el-table-column prop="costAmount" label="费用" width="95" align="right">
                    <template #default="{ row }">
                        {{ row.costAmount ? '¥' + Number(row.costAmount).toFixed(4) : '-' }}
                    </template>
                </el-table-column>
                <el-table-column prop="latencyMs" label="耗时(ms)" width="95" align="right" />
                <el-table-column prop="ttftMs" label="首字(ms)" width="90" align="right">
                    <template #default="{ row }">{{ row.ttftMs ?? '-' }}</template>
                </el-table-column>
                <el-table-column label="状态" width="80" align="center">
                    <template #default="{ row }">
                        <el-tag :type="row.success === 1 ? 'success' : 'danger'" size="small" effect="plain">
                            {{ row.success === 1 ? '成功' : '失败' }}
                        </el-tag>
                    </template>
                </el-table-column>
                <el-table-column label="降级" width="70" align="center">
                    <template #default="{ row }">
                        <el-tag v-if="row.fallbackUsed === 1" type="warning" size="small" effect="plain">降级</el-tag>
                        <span v-else class="muted">-</span>
                    </template>
                </el-table-column>
                <el-table-column label="时间" min-width="150">
                    <template #default="{ row }">{{ formatUtcTime(row.gmtCreate) }}</template>
                </el-table-column>
            </el-table>
            <div class="pagination-wrapper">
                <el-pagination v-model:current-page="page" v-model:page-size="size" :total="total"
                    :page-sizes="[10, 20, 50]" layout="total, sizes, prev, pager, next" @change="refresh" />
            </div>
        </el-card>
    </div>
</template>

<script setup lang="ts">
import { onMounted } from 'vue'
import PageHeader from '@/components/common/PageHeader.vue'
import { useTable } from '@/composables/useTable'
import { getUserUsage, getUsage } from '@/api/admin'
import type { InvokeLog } from '@/api/types'
import { formatUtcTime } from '@/utils/format'
import { useUserStore } from '@/stores/user'

const store = useUserStore()
const isAdmin = store.isAdmin

const { data, loading, page, size, total, refresh } = useTable<InvokeLog>({
    fetchFn: (params) => (isAdmin ? getUsage(params) : getUserUsage(params)),
})

onMounted(refresh)
</script>

<style scoped>
.trace-code {
    font-family: var(--font-mono);
    font-size: 12px;
}

.muted {
    color: var(--color-text-light);
}
</style>
