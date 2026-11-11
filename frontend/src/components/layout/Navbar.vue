<template>
  <header class="navbar">
    <div class="navbar-left">
      <el-button class="sidebar-toggle" text @click="$emit('toggleSidebar')" v-if="showToggle">
        <el-icon :size="18"><Expand /></el-icon>
      </el-button>
      <nav class="crumbs" aria-label="breadcrumb">
        <span class="crumb-root">gateway</span>
        <span class="crumb-sep">/</span>
        <span class="crumb-current">{{ currentTitle }}</span>
      </nav>
    </div>
    <div class="navbar-right">
      <slot name="right" />
    </div>
  </header>
</template>

<script setup lang="ts">
import { computed } from 'vue'
import { useRoute } from 'vue-router'

defineProps<{ showToggle?: boolean }>()
defineEmits<{ toggleSidebar: [] }>()

const route = useRoute()
const currentTitle = computed(() => route.meta.title as string)
</script>

<style scoped>
.navbar {
  height: 48px;
  background: var(--color-card);
  border-bottom: 1px solid var(--color-border);
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 0 20px;
  flex-shrink: 0;
}

.navbar-left {
  display: flex;
  align-items: center;
  gap: 10px;
}

/* 面包屑：gateway / 当前页 */
.crumbs {
  display: flex;
  align-items: center;
  gap: 8px;
  font-family: var(--font-mono);
  font-size: 12.5px;
  min-width: 0;
}

.crumb-root {
  color: var(--color-text-light);
}

.crumb-sep {
  color: var(--color-border);
}

.crumb-current {
  color: var(--color-text);
  font-weight: 600;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.sidebar-toggle {
  color: var(--color-text-secondary) !important;
  border-radius: 4px !important;
}

.sidebar-toggle:hover {
  color: var(--color-text) !important;
  background: var(--color-bg) !important;
}
</style>
