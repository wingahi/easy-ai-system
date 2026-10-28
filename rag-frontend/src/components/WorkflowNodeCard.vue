<template>
  <div class="wf-node" :class="[`t-${(data.nodeType || '').toLowerCase()}`, `s-${(data.status || 'IDLE').toLowerCase()}`, { selected }]">
    <Handle type="target" :position="Position.Left" />
    <div class="wf-node-head">
      <span class="wf-node-icon">{{ icon }}</span>
      <span class="wf-node-title" :title="data.name">{{ data.name || data.nodeType }}</span>
    </div>
    <div class="wf-node-foot">
      <span class="wf-node-kind">{{ kindLabel }}</span>
      <span v-if="data.status && data.status !== 'IDLE'" class="wf-node-status">{{ statusLabel }}</span>
    </div>
    <Handle
      v-for="h in sourceHandles"
      :key="h.id"
      type="source"
      :position="Position.Right"
      :id="h.id"
      :style="{ top: h.top }" />
  </div>
</template>

<script setup>
import { computed } from 'vue'
import { Handle, Position } from '@vue-flow/core'

const props = defineProps({
  id: { type: String, default: '' },
  data: { type: Object, default: () => ({}) },
  selected: { type: Boolean, default: false }
})

const ICONS = {
  START: '▶', END: '■', LLM: '✨', RETRIEVAL: '🔍',
  TOOL: '🔧', CONDITION: '◆', LOOP: '↻', HITL: '👤', VARIABLE: '𝑥'
}
const LABELS = {
  START: '开始', END: '结束', LLM: '大模型', RETRIEVAL: '知识库检索',
  TOOL: '工具调用', CONDITION: '条件分支', LOOP: '循环', HITL: '人工确认', VARIABLE: '变量赋值'
}
const STATUS = {
  RUNNING: '执行中', SUCCESS: '成功', FAILED: '失败',
  HITL_PENDING: '待确认', SKIPPED: '跳过'
}

const icon = computed(() => ICONS[props.data.nodeType] || '●')
const kindLabel = computed(() => LABELS[props.data.nodeType] || props.data.nodeType)
const statusLabel = computed(() => STATUS[props.data.status] || props.data.status)
const sourceHandles = computed(() => {
  const type = props.data.nodeType
  if (type === 'CONDITION') return [{ id: 'true', top: '35%' }, { id: 'false', top: '72%' }]
  if (type === 'LOOP') return [{ id: 'loop', top: '35%' }, { id: 'done', top: '72%' }]
  if (type === 'END') return []
  return [{ id: 'default', top: '50%' }]
})
</script>

<style scoped lang="scss">
.wf-node {
  width: 180px;
  border-radius: 8px;
  background: #fff;
  border: 1px solid #dcdfe6;
  box-shadow: 0 2px 6px rgba(0, 0, 0, 0.06);
  font-size: 12px;
  overflow: hidden;
  transition: box-shadow 0.15s, border-color 0.15s;

  &.selected {
    border-color: #409eff;
    box-shadow: 0 0 0 2px rgba(64, 158, 255, 0.24);
  }

  .wf-node-head {
    display: flex;
    align-items: center;
    gap: 6px;
    padding: 8px 10px;
    color: #fff;
    font-weight: 600;
  }
  .wf-node-icon { font-size: 13px; }
  .wf-node-title {
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
  }
  .wf-node-foot {
    display: flex;
    justify-content: space-between;
    padding: 5px 10px;
    color: #909399;
    background: #fff;
    border-top: 1px solid #f0f0f0;
  }
  .wf-node-status { color: #409eff; }

  /* 类型色 */
  &.t-start .wf-node-head { background: #67c23a; }
  &.t-end .wf-node-head { background: #909399; }
  &.t-llm .wf-node-head { background: #409eff; }
  &.t-retrieval .wf-node-head { background: #e6a23c; }
  &.t-tool .wf-node-head { background: #8e44ad; }
  &.t-condition .wf-node-head { background: #f56c6c; }
  &.t-loop .wf-node-head { background: #16a085; }
  &.t-hitl .wf-node-head { background: #d46b08; }
  &.t-variable .wf-node-head { background: #606266; }

  /* 运行状态 */
  &.s-running { border-color: #e6a23c; box-shadow: 0 0 0 2px rgba(230, 162, 60, 0.35); }
  &.s-success { border-color: #67c23a; }
  &.s-failed { border-color: #f56c6c; }
  &.s-hitl_pending { border-color: #d46b08; box-shadow: 0 0 0 2px rgba(212, 107, 8, 0.35); }
  &.s-skipped { opacity: 0.6; }
}
</style>
