<template>
  <div class="model-selector-wrapper">
    <button class="model-trigger" @click="visible = !visible">
      <el-icon :size="14"><Cpu /></el-icon>
      <span>{{ currentLabel }}</span>
      <el-icon :size="12" class="arrow" :class="{ open: visible }"><ArrowDown /></el-icon>
    </button>
    <div v-if="visible" class="model-dropdown" @click.stop>
      <div
        v-for="m in models"
        :key="m.name"
        class="model-option"
        :class="{ active: m.name === modelValue }"
        @click="select(m.name)"
      >
        <div class="model-option-main">
          <span class="model-name">{{ m.displayName || m.name }}</span>
          <span class="model-id">{{ m.name }}</span>
        </div>
        <el-icon v-if="m.name === modelValue" :size="16" color="var(--color-primary)"><Check /></el-icon>
      </div>
    </div>
    <div v-if="visible" class="model-overlay" @click="visible = false" />
  </div>
</template>

<script setup lang="ts">
import { ref, computed } from 'vue'

const props = defineProps<{
  models: Array<{ name: string; displayName: string }>
  modelValue: string
}>()

const emit = defineEmits<{ 'update:modelValue': [val: string] }>()

const visible = ref(false)

const currentLabel = computed(() => {
  const m = props.models.find(m => m.name === props.modelValue)
  return m?.displayName || m?.name || '选择模型'
})

function select(name: string) {
  emit('update:modelValue', name)
  visible.value = false
}
</script>

<style scoped>
.model-selector-wrapper {
  position: relative;
}

.model-trigger {
  display: flex;
  align-items: center;
  gap: 6px;
  padding: 5px 10px;
  border: 1px solid var(--color-border);
  border-radius: 4px;
  background: var(--color-bg);
  color: var(--color-text-secondary);
  font-size: 12px;
  font-family: inherit;
  cursor: pointer;
  transition: all 0.15s;
}

.model-trigger:hover {
  background: var(--color-primary-10);
  border-color: var(--color-primary-light);
  color: var(--color-primary);
}

.arrow {
  transition: transform 0.2s;
}

.arrow.open {
  transform: rotate(180deg);
}

.model-dropdown {
  position: absolute;
  bottom: calc(100% + 8px);
  left: 0;
  background: var(--color-card);
  border: 1px solid var(--color-border);
  border-radius: var(--radius-lg);
  box-shadow: var(--shadow-lg);
  min-width: 220px;
  z-index: 100;
  overflow: hidden;
  animation: dropIn 0.15s ease;
}

@keyframes dropIn {
  from { opacity: 0; transform: translateY(4px); }
  to { opacity: 1; transform: translateY(0); }
}

.model-option {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 10px 14px;
  cursor: pointer;
  transition: background 0.1s;
}

.model-option:hover {
  background: var(--color-bg);
}

.model-option.active {
  background: var(--color-primary-10);
}

.model-option-main {
  display: flex;
  flex-direction: column;
  gap: 2px;
}

.model-name {
  font-size: 13px;
  font-weight: 500;
  color: var(--color-text);
}

.model-id {
  font-size: 11px;
  color: var(--color-text-light);
  font-family: var(--font-mono);
}

.model-overlay {
  position: fixed;
  inset: 0;
  z-index: 99;
}
</style>