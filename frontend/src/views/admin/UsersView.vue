<template>
    <div class="page-container">
        <PageHeader title="用户管理" subtitle="管理平台用户及配额" />

        <el-card shadow="never" class="table-card">
            <el-table :data="data" v-loading="loading" stripe>
                <el-table-column prop="id" label="ID" width="80" />
                <el-table-column prop="username" label="用户名" min-width="120" />
                <el-table-column prop="email" label="邮箱" min-width="180" />
                <el-table-column prop="role" label="角色" width="100">
                    <template #default="{ row }">
                        <el-tag :type="row.role === 'ADMIN' ? 'danger' : 'info'" size="small">
                            {{ row.role === 'ADMIN' ? '管理员' : '普通用户' }}
                        </el-tag>
                    </template>
                </el-table-column>
                <el-table-column prop="status" label="状态" width="100">
                    <template #default="{ row }">
                        <el-tag :type="row.status === 1 ? 'success' : 'info'" size="small">
                            {{ row.status === 1 ? '正常' : '禁用' }}
                        </el-tag>
                    </template>
                </el-table-column>
                <el-table-column prop="dailyQuota" label="日配额" width="100" align="right">
                    <template #default="{ row }">
                        {{ row.dailyQuota?.toLocaleString() }}
                    </template>
                </el-table-column>
                <el-table-column prop="monthlyQuota" label="月配额" width="100" align="right">
                    <template #default="{ row }">
                        {{ row.monthlyQuota?.toLocaleString() }}
                    </template>
                </el-table-column>
                <el-table-column prop="usedDaily" label="今日已用" width="100" align="right">
                    <template #default="{ row }">
                        {{ row.usedDaily?.toLocaleString() }}
                    </template>
                </el-table-column>
                <el-table-column prop="gmtCreate" label="注册时间" width="170" />
                <el-table-column label="操作" width="280" fixed="right">
                    <template #default="{ row }">
                        <el-button text type="primary" size="small" @click="openQuotaDialog(row)">配额</el-button>
                        <el-popconfirm :title="row.role === 'ADMIN' ? '确定降级为普通用户？' : '确定升级为管理员？'" @confirm="toggleRole(row)">
                            <template #reference>
                                <el-button text :type="row.role === 'ADMIN' ? 'warning' : 'success'" size="small">
                                    {{ row.role === 'ADMIN' ? '降级' : '升级' }}
                                </el-button>
                            </template>
                        </el-popconfirm>
                        <el-popconfirm :title="row.status === 1 ? '确定禁用该用户？' : '确定启用该用户？'" @confirm="toggleStatus(row)">
                            <template #reference>
                                <el-button text :type="row.status === 1 ? 'danger' : 'success'" size="small">
                                    {{ row.status === 1 ? '禁用' : '启用' }}
                                </el-button>
                            </template>
                        </el-popconfirm>
                    </template>
                </el-table-column>
            </el-table>
            <div class="pagination-wrapper">
                <el-pagination v-model:current-page="page" v-model:page-size="size" :total="total"
                    :page-sizes="[10, 20, 50]" layout="total, sizes, prev, pager, next" @change="refresh" />
            </div>
        </el-card>

        <el-dialog v-model="showQuotaDialog" title="修改配额" width="400px">
            <el-form :model="quotaForm" label-position="top">
                <el-form-item label="日配额（Token数）">
                    <el-input-number v-model="quotaForm.dailyQuota" :min="0" :max="99999999" style="width: 100%" />
                </el-form-item>
                <el-form-item label="月配额（Token数）">
                    <el-input-number v-model="quotaForm.monthlyQuota" :min="0" :max="99999999" style="width: 100%" />
                </el-form-item>
            </el-form>
            <template #footer>
                <el-button @click="showQuotaDialog = false">取消</el-button>
                <el-button type="primary" :loading="saving" @click="saveQuota">保存</el-button>
            </template>
        </el-dialog>
    </div>
</template>

<script setup lang="ts">
import { ref, reactive, onMounted } from 'vue'
import { ElMessage } from 'element-plus'
import PageHeader from '@/components/common/PageHeader.vue'
import { useTable } from '@/composables/useTable'
import { getUsers, updateUserStatus, updateUserQuota, updateUserRole } from '@/api/admin'
import type { User } from '@/api/types'

const { data, loading, page, size, total, refresh } = useTable<User>({
    fetchFn: getUsers,
})

const saving = ref(false)
const showQuotaDialog = ref(false)
const editingUser = ref<User | null>(null)
const quotaForm = reactive({ dailyQuota: 100000, monthlyQuota: 3000000 })

async function toggleRole(row: User) {
    try {
        const newRole = row.role === 'ADMIN' ? 'USER' : 'ADMIN'
        await updateUserRole(row.id, newRole)
        ElMessage.success(newRole === 'ADMIN' ? '已升级为管理员' : '已降级为普通用户')
        refresh()
    } catch { /* ignore */ }
}

async function toggleStatus(row: User) {
    try {
        const newStatus = row.status === 1 ? 0 : 1
        await updateUserStatus(row.id, newStatus)
        ElMessage.success(newStatus === 1 ? '已启用' : '已禁用')
        refresh()
    } catch { /* ignore */ }
}

function openQuotaDialog(row: User) {
    editingUser.value = row
    quotaForm.dailyQuota = row.dailyQuota || 100000
    quotaForm.monthlyQuota = row.monthlyQuota || 3000000
    showQuotaDialog.value = true
}

async function saveQuota() {
    if (!editingUser.value) return
    saving.value = true
    try {
        await updateUserQuota(editingUser.value.id, quotaForm.dailyQuota, quotaForm.monthlyQuota)
        ElMessage.success('配额已更新')
        showQuotaDialog.value = false
        refresh()
    } catch { /* ignore */ } finally {
        saving.value = false
    }
}

onMounted(refresh)
</script>