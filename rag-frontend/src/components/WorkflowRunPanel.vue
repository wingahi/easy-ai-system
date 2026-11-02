<template>
  <div class="wf-panel">
    <div class="wf-head">
      <div>
        <div class="wf-title">🧩 工作流运行</div>
        <div v-if="run?.runId" class="wf-sub">运行 #{{ run.runId }}</div>
      </div>
      <el-tag size="small" :type="statusType">{{ statusText }}</el-tag>
    </div>

    <template v-if="run">
      <el-progress :percentage="progress" :stroke-width="6" :show-text="false" style="margin:10px 0 6px" />
      <div class="wf-meta">节点 {{ doneCount }}/{{ run.totalNodes || nodes.length }}</div>

      <div v-if="run.error" class="wf-error">❌ {{ run.error }}</div>

      <div class="wf-nodes">
        <div v-for="(n, i) in nodes" :key="(n.nodeId || i) + '_' + i" class="wf-node" :class="nodeClass(n.status)">
          <div class="wf-node-main">
            <span class="wf-node-name">{{ n.nodeName || n.nodeId }}</span>
            <el-tag size="small" :type="nodeStatusType(n.status)">{{ nodeStatusText(n.status) }}</el-tag>
          </div>
          <div class="wf-node-meta">
            <span class="wf-node-type">{{ n.nodeType }}</span>
            <span v-if="n.iteration">第 {{ n.iteration }} 轮</span>
            <span v-if="n.durationMs != null">{{ n.durationMs }}ms</span>
          </div>
          <div v-if="n.error" class="wf-node-err">{{ n.error }}</div>
        </div>
      </div>

      <!-- HITL 人工确认 -->
      <div v-if="run.status === 'PAUSED'" class="wf-hitl">
        <div class="wf-hitl-title">✋ 等待人工确认</div>
        <div v-if="prompt" class="wf-hitl-prompt">{{ prompt }}</div>
        <el-input v-model="hitlInput" type="textarea" :rows="3"
                  placeholder="输入确认/补充内容后继续执行" />
        <div class="wf-hitl-actions">
          <el-button type="primary" size="small" :loading="confirming" @click="confirm">
            确认并继续
          </el-button>
        </div>
      </div>

      <el-button v-if="run.status === 'RUNNING' || run.status === 'PAUSED'"
                 size="small" type="danger" plain style="margin-top:10px"
                 @click="$emit('cancel', run.runId)">
        取消运行
      </el-button>
    </template>

    <el-empty v-else description="发送消息后展示工作流执行轨迹" :image-size="60" />
  </div>
</template>

<script setup>
import { ref, computed, watch } from 'vue'

const props = defineProps({
  run: { type: Object, default: null },
  prompt: { type: String, default: '' },
  confirming: { type: Boolean, default: false }
})
const emit = defineEmits(['confirm', 'cancel'])

const hitlInput = ref('')

// 切换运行时清空上一次的确认输入
watch(() => props.run?.runId, () => { hitlInput.value = '' })

const nodes = computed(() => props.run?.nodes || [])
const doneCount = computed(() => nodes.value.filter(n =>
  n.status === 'SUCCESS' || n.status === 'FAILED' || n.status === 'SKIPPED').length)
const progress = computed(() => {
  const total = props.run?.totalNodes || nodes.value.length
  if (!total) return 0
  return Math.min(100, Math.round(doneCount.value * 100 / total))
})

const statusText = computed(() => {
  const map = {
    RUNNING: '执行中', PAUSED: '暂停', COMPLETED: '已完成',
    FAILED: '失败', CANCELLED: '已取消'
  }
  return map[props.run?.status] || '空闲'
})
const statusType = computed(() => ({
  RUNNING: 'primary', PAUSED: 'warning', COMPLETED: 'success',
  FAILED: 'danger', CANCELLED: 'info'
}[props.run?.status] || 'info'))

const nodeStatusText = (s) => ({
  PENDING: '等待', RUNNING: '执行中', SUCCESS: '成功',
  FAILED: '失败', SKIPPED: '跳过', HITL_PENDING: '待确认'
}[s] || s || '-')
const nodeStatusType = (s) => ({
  PENDING: 'info', RUNNING: 'primary', SUCCESS: 'success',
  FAILED: 'danger', SKIPPED: 'info', HITL_PENDING: 'warning'
}[s] || 'info')
const nodeClass = (s) => ({
  'is-running': s === 'RUNNING',
  'is-failed': s === 'FAILED',
  'is-hitl': s === 'HITL_PENDING'
})

const confirm = () => {
  emit('confirm', hitlInput.value.trim())
}
</script>

<style scoped lang="scss">
.wf-panel { margin-bottom: 16px; }
.wf-head { display: flex; justify-content: space-between; align-items: center; }
.wf-title { font-size: 14px; font-weight: 600; color: #303133; }
.wf-sub { font-size: 12px; color: #909399; margin-top: 2px; }
.wf-meta { font-size: 12px; color: #909399; }
.wf-error {
  margin-top: 8px; padding: 8px 10px; font-size: 12px; line-height: 1.5;
  color: #f56c6c; background: #fef0f0; border-radius: 6px;
}
.wf-nodes { margin-top: 10px; display: flex; flex-direction: column; gap: 8px; }
.wf-node {
  border: 1px solid #ebeef5; border-radius: 6px; padding: 8px 10px;
  &.is-running { border-color: #409eff; background: #ecf5ff; }
  &.is-failed { border-color: #f56c6c; background: #fef0f0; }
  &.is-hitl { border-color: #e6a23c; background: #fdf6ec; }
}
.wf-node-main { display: flex; justify-content: space-between; align-items: center; gap: 8px; }
.wf-node-name { font-size: 13px; color: #303133; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.wf-node-meta { display: flex; gap: 8px; margin-top: 4px; font-size: 11px; color: #909399; }
.wf-node-type { text-transform: uppercase; }
.wf-node-err { margin-top: 4px; font-size: 12px; color: #f56c6c; line-height: 1.5; }
.wf-hitl {
  margin-top: 12px; padding: 10px; border-radius: 6px;
  background: #fdf6ec; border: 1px solid #faecd8;
}
.wf-hitl-title { font-size: 13px; color: #e6a23c; font-weight: 600; margin-bottom: 6px; }
.wf-hitl-prompt { font-size: 12px; color: #606266; line-height: 1.6; margin-bottom: 8px; white-space: pre-wrap; }
.wf-hitl-actions { margin-top: 8px; text-align: right; }
</style>
