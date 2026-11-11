<template>
    <div class="page-container">
        <PageHeader title="API Key 管理" subtitle="管理您的 API Key，用于外部程序调用 AI 接口">
            <template #action>
                <el-button type="primary" @click="showCreateDialog = true">
                    <el-icon>
                        <Plus />
                    </el-icon>
                    创建 Key
                </el-button>
            </template>
        </PageHeader>

        <el-card shadow="never" class="table-card">
            <el-alert type="info" :closable="false" show-icon class="security-tip"
                title="安全提示：Key 明文仅在创建时展示一次，关闭弹窗后无法再次查看，请立即复制保存。" />
            <el-table :data="keys" v-loading="loading" stripe>
                <el-table-column prop="id" label="ID" width="80" />
                <el-table-column prop="keyName" label="名称" min-width="150" />
                <el-table-column prop="keyPrefix" label="Key 前缀" min-width="180">
                    <template #default="{ row }">
                        <div class="key-cell">
                            <code>{{ row.keyPrefix }}****</code>
                        </div>
                    </template>
                </el-table-column>
                <el-table-column prop="status" label="状态" width="100">
                    <template #default="{ row }">
                        <el-tag :type="row.status === 1 ? 'success' : 'danger'" size="small">
                            {{ row.status === 1 ? '有效' : '已吊销' }}
                        </el-tag>
                    </template>
                </el-table-column>
                <el-table-column label="创建时间" width="170">
                    <template #default="{ row }">{{ formatUtcTime(row.gmtCreate) }}</template>
                </el-table-column>
                <el-table-column label="操作" width="100" fixed="right">
                    <template #default="{ row }">
                        <el-popconfirm v-if="row.status === 1" title="确定吊销此 Key？吊销后使用该 Key 的调用会立即失败"
                            @confirm="handleRevoke(row.id)">
                            <template #reference>
                                <el-button type="danger" text size="small">吊销</el-button>
                            </template>
                        </el-popconfirm>
                        <span v-else class="muted">-</span>
                    </template>
                </el-table-column>
            </el-table>
        </el-card>

        <!-- 创建 Key 弹窗 -->
        <el-dialog v-model="showCreateDialog" title="创建 API Key" width="420px" :close-on-click-modal="false">
            <el-form :model="newKeyForm" label-position="top" @submit.prevent="handleCreateKey">
                <el-form-item label="Key 名称" required>
                    <el-input v-model="newKeyForm.keyName" placeholder="如：生产环境、测试环境" maxlength="50" />
                </el-form-item>
            </el-form>
            <template #footer>
                <el-button @click="showCreateDialog = false">取消</el-button>
                <el-button type="primary" :loading="creating" @click="handleCreateKey">创建</el-button>
            </template>
        </el-dialog>

        <!-- 创建成功：明文仅展示一次 -->
        <el-dialog v-model="showKeyDialog" title="创建成功 —— 请立即保存 Key" width="560px"
            :close-on-click-modal="false" :show-close="false">
            <el-alert type="warning" :closable="false" show-icon class="security-tip"
                title="完整 Key 仅此一次展示，关闭后无法再次查看！" />
            <div class="full-key-box">
                <code>{{ createdKey }}</code>
                <el-button type="primary" size="small" @click="copyKey(createdKey)">
                    <el-icon>
                        <CopyDocument />
                    </el-icon>
                    复制
                </el-button>
            </div>
            <template #footer>
                <el-button type="primary" @click="showKeyDialog = false">我已保存，关闭</el-button>
            </template>
        </el-dialog>
    </div>
</template>

<script setup lang="ts">
import { ref, reactive, onMounted } from 'vue'
import { ElMessage } from 'element-plus'
import PageHeader from '@/components/common/PageHeader.vue'
import { getUserApiKeys, createUserApiKey, revokeUserApiKey } from '@/api/admin'
import type { ApiKeyVO } from '@/api/types'
import { formatUtcTime } from '@/utils/format'

const keys = ref<ApiKeyVO[]>([])
const loading = ref(false)

const showCreateDialog = ref(false)
const creating = ref(false)
const newKeyForm = reactive({ keyName: '' })

// 创建成功后一次性展示的明文
const showKeyDialog = ref(false)
const createdKey = ref('')

async function refresh() {
    loading.value = true
    try {
        const resp = await getUserApiKeys()
        keys.value = resp.data ?? []
    } catch { /* ignore */ } finally {
        loading.value = false
    }
}

async function handleCreateKey() {
    if (!newKeyForm.keyName.trim()) {
        ElMessage.warning('请输入 Key 名称')
        return
    }
    creating.value = true
    try {
        const resp = await createUserApiKey({ keyName: newKeyForm.keyName.trim() })
        // v2 契约：明文 fullKey 仅创建响应返回一次
        createdKey.value = resp.data?.fullKey || ''
        showCreateDialog.value = false
        showKeyDialog.value = true
        newKeyForm.keyName = ''
        refresh()
    } catch { /* ignore */ } finally {
        creating.value = false
    }
}

async function handleRevoke(id: number) {
    try {
        await revokeUserApiKey(id)
        ElMessage.success('已吊销')
        refresh()
    } catch { /* ignore */ }
}

function copyKey(key: string) {
    navigator.clipboard.writeText(key).then(() => ElMessage.success('已复制'))
}

onMounted(refresh)
</script>

<style scoped>
.security-tip {
    margin-bottom: 16px;
}

.key-cell code {
    font-family: var(--font-mono);
    font-size: 12px;
    background: #f3f4f6;
    padding: 2px 8px;
    border-radius: 4px;
    word-break: break-all;
}

.full-key-box {
    display: flex;
    align-items: center;
    gap: 12px;
}

.full-key-box code {
    flex: 1;
    font-family: var(--font-mono);
    font-size: 13px;
    background: #f3f4f6;
    padding: 10px 12px;
    border-radius: 8px;
    word-break: break-all;
}

.muted {
    color: var(--color-text-light);
}
</style>
