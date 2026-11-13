<template>
  <!-- 有写权限：可点击切换可见性 -->
  <el-dropdown v-if="canEdit" trigger="click" :disabled="loading" @command="onCommand">
    <span class="vis-trigger">
      <el-tag :type="meta.tagType" :size="size" effect="light">{{ meta.label }}</el-tag>
      <el-icon v-if="!loading" class="vis-caret"><ArrowDown /></el-icon>
      <el-icon v-else class="vis-caret is-loading"><Loading /></el-icon>
    </span>
    <template #dropdown>
      <el-dropdown-menu>
        <el-dropdown-item
          v-for="o in VISIBILITY_OPTIONS"
          :key="o.value"
          :command="o.value"
          :disabled="o.value === current"
        >
          <div class="vis-option">
            <span>{{ o.label }}</span>
            <span class="vis-hint">{{ o.hint }}</span>
          </div>
        </el-dropdown-item>
      </el-dropdown-menu>
    </template>
  </el-dropdown>

  <!-- 无写权限：只展示徽标，并说明原因 -->
  <el-tooltip v-else :content="readonlyReason" placement="top">
    <span class="vis-trigger is-readonly">
      <el-tag :type="meta.tagType" :size="size" effect="plain">{{ meta.label }}</el-tag>
    </span>
  </el-tooltip>
</template>

<script setup>
import { computed } from 'vue'
import { VISIBILITY_OPTIONS, visibilityMeta } from '../utils/visibility'

const props = defineProps({
  visibility: { type: String, default: 'PRIVATE' },
  canEdit: { type: Boolean, default: false },
  loading: { type: Boolean, default: false },
  size: { type: String, default: 'small' },
  readonlyReason: { type: String, default: '仅创建者或管理员可修改可见性' }
})

const emit = defineEmits(['change'])

const current = computed(() => visibilityMeta(props.visibility).value)
const meta = computed(() => visibilityMeta(props.visibility))

const onCommand = (value) => {
  if (value === current.value) return
  emit('change', value)
}
</script>

<style scoped>
.vis-trigger {
  display: inline-flex;
  align-items: center;
  gap: 2px;
  cursor: pointer;
  outline: none;
}
.vis-trigger.is-readonly {
  cursor: default;
}
.vis-caret {
  font-size: 12px;
  color: #909399;
}
.vis-caret.is-loading {
  animation: vis-spin 1s linear infinite;
}
@keyframes vis-spin {
  from { transform: rotate(0deg); }
  to { transform: rotate(360deg); }
}
.vis-option {
  display: flex;
  flex-direction: column;
  line-height: 1.4;
}
.vis-hint {
  font-size: 12px;
  color: #909399;
}
</style>
