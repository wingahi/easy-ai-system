<template>
  <div class="agent-plan-tree">
    <div class="plan-header">
      <div class="plan-title">
        <el-icon :size="16"><Operation /></el-icon>
        <span>Agent 执行计划</span>
      </div>
      <div class="plan-status">
        <el-tag :type="statusTagType" size="small" effect="dark">{{ planStatusText }}</el-tag>
      </div>
    </div>

    <div v-if="planId" class="plan-meta">
      <div class="goal" :title="userGoal">🎯 {{ userGoal || '正在规划...' }}</div>
      <div class="meta-row">
        <span>步骤 {{ completedCount }}/{{ steps.length }}</span>
        <span v-if="reflectionTotal > 0">· 反思 {{ reflectionTotal }} 次</span>
      </div>
    </div>

    <div v-else class="plan-empty">等待 Agent 规划...</div>

    <!-- 步骤树 -->
    <div v-if="steps.length" class="steps-list">
      <div v-for="(step, idx) in steps" :key="step.stepId || step.id || idx"
           :class="['step-node', step.status?.toLowerCase(), { active: activeStepId === step.stepId }]">
        <div class="step-connector">
          <div class="connector-dot" :class="step.status?.toLowerCase()" />
          <div v-if="idx < steps.length - 1" class="connector-line" />
        </div>

        <div class="step-body">
          <div class="step-title-row">
            <span class="step-index">{{ step.stepIndex != null ? step.stepIndex + 1 : idx + 1 }}</span>
            <span class="step-title">{{ step.title }}</span>
            <el-tag v-if="step.status === 'RUNNING' || step.status === 'HITL_PENDING'"
                    size="small" type="warning" class="step-spinner">
              <el-icon class="is-loading"><Loading /></el-icon>
              {{ step.status === 'RUNNING' ? '执行中' : '等待确认' }}
            </el-tag>
            <el-tag v-else-if="step.status === 'SUCCESS'" size="small" type="success">✓ 成功</el-tag>
            <el-tag v-else-if="step.status === 'FAILED'" size="small" type="danger">✗ 失败</el-tag>
            <el-tag v-else-if="step.status === 'SKIPPED'" size="small" type="info">跳过</el-tag>
            <el-tag v-else-if="step.status === 'PENDING' || step.status === 'READY'" size="small" type="info">待执行</el-tag>
            <el-tag v-else-if="step.status" size="small">{{ step.status }}</el-tag>
          </div>

          <div v-if="step.description" class="step-desc">{{ step.description }}</div>

          <!-- 反思分数 -->
          <div v-if="step.reflectionScore != null" class="step-reflection">
            <el-tag v-if="step.reflectionScore >= reflectionThreshold" size="small" type="success">
              反思 {{ step.reflectionScore.toFixed(1) }} 分 ✓
            </el-tag>
            <el-tag v-else size="small" type="danger">
              反思 {{ step.reflectionScore.toFixed(1) }} 分 ↓ 已重试 {{ step.retryCount || 0 }} 次
            </el-tag>
            <span v-if="step.reflectionFeedback" class="reflection-feedback" :title="step.reflectionFeedback">
              {{ step.reflectionFeedback }}
            </span>
          </div>

          <!-- 步骤产出摘要 -->
          <div v-if="step.resultSummary" class="step-summary" :title="step.resultSummary">
            📋 {{ step.resultSummary.length > 80 ? step.resultSummary.slice(0, 80) + '...' : step.resultSummary }}
          </div>

          <!-- 工具调用 -->
          <div v-if="step.actionTool" class="step-tool">
            🔧 调用工具: <el-tag size="small">{{ step.actionTool }}</el-tag>
          </div>

          <!-- 控制按钮 -->
          <div v-if="showControls(step)" class="step-controls">
            <template v-if="step.status === 'HITL_PENDING'">
              <el-button size="small" type="primary" @click="openHitlDialog(step)">✋ 输入确认</el-button>
            </template>
            <template v-else-if="planStatus === 'RUNNING' || planStatus === 'PAUSED'">
              <el-button size="small" link type="warning" :disabled="step.status !== 'FAILED'"
                         @click="$emit('retry-step', step)">🔄 重试</el-button>
              <el-button size="small" link type="info" :disabled="step.status === 'RUNNING' || step.status === 'SUCCESS'"
                         @click="$emit('skip-step', step)">⏭ 跳过</el-button>
            </template>
          </div>
        </div>
      </div>
    </div>

    <!-- 底部控制 -->
    <div v-if="planId && (planStatus === 'RUNNING' || planStatus === 'PAUSED')" class="plan-controls">
      <el-button size="small" type="danger" plain @click="$emit('cancel-plan', planId)">⛔ 取消整个计划</el-button>
      <el-button v-if="planStatus === 'PAUSED'" size="small" type="primary" plain @click="$emit('refresh-plan', planId)">
        🔄 刷新状态
      </el-button>
    </div>

    <!-- HITL 输入弹窗 -->
    <el-dialog v-model="hitlDialog.visible" title="👋 人工确认步骤" width="480px" append-to-body>
      <div v-if="hitlDialog.step">
        <div class="hitl-step-info">
          <div class="hitl-title">{{ hitlDialog.step.title }}</div>
          <div v-if="hitlDialog.step.description" class="hitl-desc">{{ hitlDialog.step.description }}</div>
        </div>
        <el-input v-model="hitlDialog.input" type="textarea" :rows="3"
                  placeholder="请输入确认信息或补充参数（可留空）" />
      </div>
      <template #footer>
        <el-button @click="hitlDialog.visible = false">取消</el-button>
        <el-button type="primary" :loading="hitlDialog.submitting" @click="confirmHitl">确认继续</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { computed, reactive } from 'vue'
import { ElMessage } from 'element-plus'
import { Operation, Loading } from '@element-plus/icons-vue'
import { agentHitlConfirm } from '../api'

const props = defineProps({
  planId: { type: [Number, String], default: null },
  planStatus: { type: String, default: '' },   // PLANNING / RUNNING / PAUSED / COMPLETED / FAILED / CANCELLED
  userGoal: { type: String, default: '' },
  steps: { type: Array, default: () => [] },
  activeStepId: { type: String, default: null },
  reflectionThreshold: { type: Number, default: 7.0 }
})

const emit = defineEmits(['hitl-confirm', 'cancel-plan', 'retry-step', 'skip-step', 'refresh-plan'])

const hitlDialog = reactive({
  visible: false,
  step: null,
  input: '',
  submitting: false
})

const statusTagType = computed(() => ({
  PLANNING: 'info',
  RUNNING: 'warning',
  PAUSED: 'warning',
  COMPLETED: 'success',
  FAILED: 'danger',
  CANCELLED: 'info'
}[props.planStatus] || 'info'))

const planStatusText = computed(() => ({
  PLANNING: '📝 规划中',
  RUNNING: '⚡ 执行中',
  PAUSED: '✋ 已暂停 (HITL)',
  COMPLETED: '✅ 已完成',
  FAILED: '❌ 已失败',
  CANCELLED: '⛔ 已取消'
}[props.planStatus] || props.planStatus || '—'))

const completedCount = computed(() =>
  props.steps.filter(s => s.status === 'SUCCESS' || s.status === 'SKIPPED').length
)

const reflectionTotal = computed(() =>
  props.steps.reduce((sum, s) => sum + (s.retryCount || 0), 0)
)

const showControls = (step) => {
  if (!props.planId) return false
  if (step.status === 'HITL_PENDING') return true
  return props.planStatus === 'RUNNING' || props.planStatus === 'PAUSED'
}

const openHitlDialog = (step) => {
  hitlDialog.step = step
  hitlDialog.input = ''
  hitlDialog.visible = true
}

const confirmHitl = async () => {
  if (!hitlDialog.step) return
  hitlDialog.submitting = true
  try {
    await agentHitlConfirm(hitlDialog.step.id, hitlDialog.input)
    ElMessage.success('已确认，Agent 继续执行')
    hitlDialog.visible = false
    emit('hitl-confirm', hitlDialog.step, hitlDialog.input)
  } catch (e) {
    ElMessage.error('确认失败: ' + (e.message || ''))
  } finally {
    hitlDialog.submitting = false
  }
}
</script>

<style scoped lang="scss">
.agent-plan-tree {
  background: #fafbfc;
  border-radius: 8px;
  padding: 14px;
  font-size: 13px;
}
.plan-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 10px;
  .plan-title {
    display: flex;
    align-items: center;
    gap: 6px;
    font-weight: 600;
    color: #303133;
  }
}
.plan-meta {
  background: #fff;
  border: 1px solid #ebeef5;
  border-radius: 6px;
  padding: 10px 12px;
  margin-bottom: 12px;
  .goal {
    color: #303133;
    font-weight: 500;
    line-height: 1.5;
    font-size: 12px;
  }
  .meta-row {
    margin-top: 6px;
    font-size: 11px;
    color: #909399;
    display: flex;
    gap: 8px;
  }
}
.plan-empty {
  color: #c0c4cc;
  font-size: 12px;
  padding: 12px 0;
  text-align: center;
}
.steps-list {
  display: flex;
  flex-direction: column;
}
.step-node {
  display: flex;
  gap: 8px;
  min-height: 40px;
  &.active .step-body {
    background: #ecf5ff;
    border-color: #409eff;
  }
}
.step-connector {
  display: flex;
  flex-direction: column;
  align-items: center;
  width: 14px;
  flex-shrink: 0;
}
.connector-dot {
  width: 12px;
  height: 12px;
  border-radius: 50%;
  background: #dcdfe6;
  margin-top: 4px;
  flex-shrink: 0;
  transition: all 0.2s;
  &.success { background: #67c23a; box-shadow: 0 0 0 3px rgba(103,194,58,0.2); }
  &.failed { background: #f56c6c; box-shadow: 0 0 0 3px rgba(245,108,108,0.2); }
  &.running, &.hitl_pending {
    background: #e6a23c;
    box-shadow: 0 0 0 3px rgba(230,162,60,0.2);
    animation: pulse 1.5s infinite;
  }
}
@keyframes pulse {
  0%, 100% { transform: scale(1); }
  50% { transform: scale(1.2); }
}
.connector-line {
  width: 2px;
  flex: 1;
  background: #dcdfe6;
  margin-top: 2px;
}
.step-body {
  flex: 1;
  background: #fff;
  border: 1px solid #ebeef5;
  border-radius: 6px;
  padding: 8px 10px;
  margin-bottom: 6px;
  transition: all 0.15s;
}
.step-title-row {
  display: flex;
  align-items: center;
  gap: 6px;
  flex-wrap: wrap;
  .step-index {
    background: #606266;
    color: #fff;
    font-size: 11px;
    padding: 1px 6px;
    border-radius: 10px;
    font-weight: 600;
  }
  .step-title {
    font-weight: 500;
    color: #303133;
    flex: 1;
  }
  .step-spinner {
    display: inline-flex;
    align-items: center;
    gap: 3px;
  }
}
.step-desc {
  font-size: 11px;
  color: #909399;
  margin-top: 4px;
  line-height: 1.5;
}
.step-reflection {
  margin-top: 6px;
  display: flex;
  align-items: center;
  gap: 6px;
  flex-wrap: wrap;
  .reflection-feedback {
    font-size: 11px;
    color: #909399;
    font-style: italic;
  }
}
.step-summary {
  margin-top: 6px;
  font-size: 11px;
  color: #606266;
  background: #f5f7fa;
  padding: 4px 8px;
  border-radius: 4px;
  line-height: 1.5;
}
.step-tool {
  margin-top: 4px;
  font-size: 11px;
  color: #606266;
  display: flex;
  align-items: center;
  gap: 4px;
}
.step-controls {
  margin-top: 8px;
  display: flex;
  gap: 6px;
}
.plan-controls {
  margin-top: 12px;
  padding-top: 10px;
  border-top: 1px dashed #ebeef5;
  display: flex;
  gap: 8px;
}
.hitl-step-info {
  background: #fdf6ec;
  border: 1px solid #faecd8;
  border-radius: 6px;
  padding: 12px;
  margin-bottom: 12px;
  .hitl-title { font-weight: 600; color: #e6a23c; margin-bottom: 6px; }
  .hitl-desc { font-size: 12px; color: #606266; line-height: 1.6; }
}
</style>
