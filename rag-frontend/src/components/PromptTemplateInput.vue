<template>
  <div class="pt-input" :class="{ 'is-open': panelVisible || variableMode }">
    <!-- 候选面板：输入前缀后在输入框上方弹出（fixed 定位，避免被父级 overflow 裁剪） -->
    <Teleport to="body">
      <div v-if="panelVisible && !variableMode" class="pt-panel" :style="panelStyle" @mousedown.prevent>
        <div class="pt-panel-head">
          <span class="pt-title">Prompt 模板</span>
          <span class="pt-tip">↑↓ 选择 · Enter 填入 · Esc 关闭</span>
        </div>
        <div v-if="loading" class="pt-empty">加载中…</div>
        <div v-else-if="!candidates.length" class="pt-empty">
          没有匹配的模板（继续输入可过滤，或去 Prompt 模板中心新增）
        </div>
        <div v-else class="pt-list">
          <div v-for="(t, i) in candidates" :key="t.id"
               :class="['pt-item', { active: i === activeIndex }]"
               @mouseenter="activeIndex = i"
               @click="choose(t)">
            <div class="pt-item-main">
              <span class="pt-name">{{ t.name }}</span>
              <el-tag v-if="t.category" size="small" type="info">{{ t.category }}</el-tag>
              <span class="pt-ver">v{{ t.version }}</span>
            </div>
            <div v-if="t.description" class="pt-desc">{{ t.description }}</div>
            <div v-if="t.variables?.length" class="pt-vars">
              <span v-for="v in t.variables" :key="v" class="pt-var">{{ varToken(v) }}</span>
            </div>
          </div>
        </div>
      </div>
    </Teleport>

    <!-- 变量填写面板 -->
    <Teleport to="body">
      <div v-if="variableMode && !variableCollapsed" class="pt-panel pt-var-panel" :style="panelStyle" @mousedown.stop>
        <div class="pt-panel-head">
          <span class="pt-title">
            填写变量 {{ activeVarIndex + 1 }}/{{ missingVars.length }}
          </span>
          <span class="pt-tip">Tab / Enter 下一个 · Esc 收起</span>
        </div>
        <div class="pt-var-current">
          <span class="pt-var-label">占位符</span>
          <mark class="pt-var-token">{{ varToken(currentVar) }}</mark>
        </div>
        <div class="pt-var-row">
          <el-input v-model="varInputValue" size="small" ref="varInputRef"
                    :placeholder="`输入 ${currentVar} 的值（留空按空串处理）`"
                    @keydown.enter.prevent="commitAndNext"
                    @keydown.tab.prevent="commitAndNext" />
          <el-button size="small" type="primary" @click="commitAndNext">下一个</el-button>
        </div>
        <div class="pt-var-actions">
          <el-button size="small" type="primary" plain :disabled="!canSubmit" @click="skipAndSubmit">
            跳过变量直接发送
          </el-button>
          <span class="pt-var-note">留空 = 按后端 render 规则替换为空串</span>
        </div>
      </div>
    </Teleport>

    <!-- 变量面板收起后的提示条 -->
    <div v-if="variableMode && variableCollapsed" class="pt-var-bar">
      <el-tag size="small" type="warning">模板还有 {{ missingVars.length }} 个变量未填写</el-tag>
      <el-button link type="primary" size="small" @click="variableCollapsed = false">继续填写</el-button>
      <el-button v-if="canSubmit" link type="danger" size="small" @click="skipAndSubmit">跳过并发送</el-button>
    </div>

    <el-input ref="inputRef" type="textarea" :rows="rows" :model-value="modelValue"
              :placeholder="placeholder" :disabled="disabled"
              @input="onInput" @keydown="onKeydown" @blur="onBlur" />
  </div>
</template>

<script setup>
import { computed, nextTick, onBeforeUnmount, ref, watch } from 'vue'
import { promptTemplateSuggest } from '../api'
import { extractTemplateVariables, loadPromptTriggerConfig, promptVarRegExp, promptVarToken } from '../utils/promptTrigger'

const props = defineProps({
  modelValue: { type: String, default: '' },
  rows: { type: Number, default: 2 },
  placeholder: { type: String, default: '' },
  disabled: { type: Boolean, default: false },
  // 关闭后仅做内容插入，不进入变量填写（工作流等由引擎自行渲染变量的场景）
  variableFill: { type: Boolean, default: true }
})

const emit = defineEmits(['update:modelValue', 'submit'])

const inputRef = ref(null)
const varInputRef = ref(null)

// ============ 前缀联想状态 ============
const panelVisible = ref(false)
const candidates = ref([])
const loading = ref(false)
const activeIndex = ref(0)
const triggerStart = ref(0)
const triggerEnd = ref(0)
const keyword = ref('')
// 浮层使用 fixed 定位锚定到输入框，避免被 chat-main 的 overflow:hidden 裁剪
const panelStyle = ref({})

// ============ 变量填写状态 ============
const pendingVars = ref([])
const activeVarIndex = ref(0)
const varInputValue = ref('')
const variableCollapsed = ref(false)
const skipped = ref(false)

let searchTimer = null
let requestSeq = 0
let blurTimer = null

const getTextarea = () => inputRef.value?.textarea || null

const updatePanelStyle = () => {
  const el = getTextarea()
  if (!el) return
  const rect = el.getBoundingClientRect()
  const width = Math.min(560, Math.max(320, rect.width))
  const left = Math.min(Math.max(8, rect.left), Math.max(8, window.innerWidth - width - 8))
  const spaceAbove = rect.top - 16
  if (spaceAbove >= 240) {
    panelStyle.value = {
      left: `${Math.round(left)}px`,
      width: `${Math.round(width)}px`,
      bottom: `${Math.round(window.innerHeight - rect.top + 8)}px`,
      maxHeight: `${Math.min(300, Math.round(spaceAbove - 8))}px`
    }
  } else {
    panelStyle.value = {
      left: `${Math.round(left)}px`,
      width: `${Math.round(width)}px`,
      top: `${Math.round(rect.bottom + 8)}px`,
      maxHeight: `${Math.min(300, Math.round(window.innerHeight - rect.bottom - 24))}px`
    }
  }
}

const varToken = promptVarToken

// 仍留在输入框中的未填占位符（用户手动删除占位符也会被视为已处理）
const missingVars = computed(() => {
  const text = props.modelValue || ''
  return pendingVars.value.filter(v => promptVarRegExp(v, '').test(text))
})

const currentVar = computed(() => missingVars.value[activeVarIndex.value] || missingVars.value[0] || '')
const variableMode = computed(() => props.variableFill && missingVars.value.length > 0 && !skipped.value)
const canSubmit = computed(() => props.variableFill && !props.disabled)

watch(() => props.disabled, (v) => { if (v) closePanel() })
watch(() => props.modelValue, () => {
  // 输入内容被清空/占位符全部处理完时，退出变量填写状态
  if (!missingVars.value.length) {
    pendingVars.value = []
    skipped.value = false
    varInputValue.value = ''
  } else if (activeVarIndex.value >= missingVars.value.length) {
    activeVarIndex.value = 0
  }
})

const onInput = (val) => {
  emit('update:modelValue', val)
  if (variableMode.value) return // 变量填写期间不弹候选，避免与变量面板冲突
  detectTrigger()
}

// ============ 前缀检测 ============
const detectTrigger = () => {
  const cfg = loadPromptTriggerConfig()
  if (!cfg.enabled || props.disabled) return closePanel()
  const el = getTextarea()
  if (!el) return closePanel()
  const text = el.value || ''
  const pos = el.selectionStart ?? text.length
  // 存在选区时不触发，避免替换用户选中的文本
  if (el.selectionEnd !== pos) return closePanel()
  const before = text.slice(0, pos)
  const idx = before.lastIndexOf(cfg.prefix)
  if (idx < 0) return closePanel()
  // 前缀必须是行首或紧跟空白，避免在 URL、路径中误触发
  const prev = idx > 0 ? before[idx - 1] : ''
  if (prev && !/\s/.test(prev)) return closePanel()
  const kw = before.slice(idx + cfg.prefix.length)
  if (/\s/.test(kw) || kw.includes(cfg.prefix)) return closePanel()

  triggerStart.value = idx
  triggerEnd.value = pos
  keyword.value = kw
  activeIndex.value = 0
  panelVisible.value = true
  updatePanelStyle()
  // 清掉失焦延时关闭，避免重新聚焦并输入后又被上一次 blur 的定时器关掉
  clearTimeout(blurTimer)
  fetchCandidates(kw, cfg)
}

const fetchCandidates = (kw, cfg) => {
  if (!kw) {
    searchCandidates(kw, cfg)
    return
  }
  clearTimeout(searchTimer)
  searchTimer = setTimeout(() => searchCandidates(kw, cfg), 200)
}

const searchCandidates = async (kw, cfg) => {
  const mySeq = ++requestSeq
  loading.value = true
  try {
    const res = await promptTemplateSuggest({ query: kw || undefined, limit: cfg.maxCandidates })
    if (mySeq !== requestSeq) return
    candidates.value = res.data || []
    activeIndex.value = Math.min(activeIndex.value, Math.max(0, candidates.value.length - 1))
  } catch {
    if (mySeq === requestSeq) candidates.value = []
  } finally {
    if (mySeq === requestSeq) loading.value = false
  }
}

const closePanel = () => {
  panelVisible.value = false
  candidates.value = []
  clearTimeout(searchTimer)
  requestSeq++
}

const onBlur = () => {
  // 点击候选面板使用 mousedown.prevent，不会触发 textarea blur
  clearTimeout(blurTimer)
  blurTimer = setTimeout(() => closePanel(), 120)
}

// ============ 键盘 ============
const onKeydown = (e) => {
  if ((e.ctrlKey || e.metaKey) && e.key === 'Enter') {
    e.preventDefault()
    emit('submit')
    return
  }
  if (panelVisible.value && candidates.value.length) {
    if (e.key === 'ArrowDown') {
      e.preventDefault()
      activeIndex.value = (activeIndex.value + 1) % candidates.value.length
      return
    }
    if (e.key === 'ArrowUp') {
      e.preventDefault()
      activeIndex.value = (activeIndex.value - 1 + candidates.value.length) % candidates.value.length
      return
    }
    if (e.key === 'Enter' && !e.isComposing) {
      e.preventDefault()
      choose(candidates.value[activeIndex.value])
      return
    }
  }
  // 候选尚未返回时按 Enter 不插入换行，避免破坏正在输入的触发词
  if (panelVisible.value && e.key === 'Enter' && !e.isComposing) {
    e.preventDefault()
    return
  }
  if (e.key === 'Escape') {
    if (panelVisible.value) {
      e.preventDefault()
      closePanel()
      return
    }
    if (variableMode.value && !variableCollapsed.value) {
      e.preventDefault()
      variableCollapsed.value = true
    }
  }
}

// ============ 选中填入 ============
const choose = (tpl) => {
  const start = triggerStart.value
  const end = Math.max(triggerEnd.value, start)
  const content = tpl.content || ''
  const nextText = (props.modelValue || '').slice(0, start) + content + (props.modelValue || '').slice(end)
  emit('update:modelValue', nextText)
  closePanel()

  const vars = (tpl.variables?.length ? tpl.variables : extractTemplateVariables(content))
  pendingVars.value = [...vars]
  skipped.value = false
  variableCollapsed.value = false
  activeVarIndex.value = 0
  varInputValue.value = ''

  nextTick(() => {
    if (!props.variableFill || !pendingVars.value.length) {
      setCaret(start + content.length, nextText)
      return
    }
    // 高亮（选中）首个占位符，并把焦点交给变量输入框，便于直接输入
    updatePanelStyle()
    highlightVar(nextText, pendingVars.value[0])
    varInputRef.value?.focus?.()
  })
}

const setCaret = (pos, text) => {
  const el = getTextarea()
  if (!el) return
  el.focus()
  const p = Math.min(pos, (text ?? el.value).length)
  el.setSelectionRange(p, p)
}

const highlightVar = (text, name) => {
  const el = getTextarea()
  if (!el) return
  const m = promptVarRegExp(name, '').exec(text || el.value)
  if (!m) return
  el.focus()
  el.setSelectionRange(m.index, m.index + m[0].length)
}

const replaceVar = (name, value) => {
  const next = (props.modelValue || '').replace(promptVarRegExp(name), value ?? '')
  emit('update:modelValue', next)
  return next
}

const commitAndNext = () => {
  const name = currentVar.value
  if (!name) return
  const nextText = replaceVar(name, varInputValue.value)
  varInputValue.value = ''
  nextTick(() => {
    const rest = missingVars.value
    if (!rest.length) {
      pendingVars.value = []
      setCaret(nextText.length, nextText)
      return
    }
    activeVarIndex.value = Math.min(activeVarIndex.value, rest.length - 1)
    highlightVar(nextText, rest[activeVarIndex.value])
    varInputRef.value?.focus?.()
  })
}

const skipAndSubmit = () => {
  let text = props.modelValue || ''
  for (const v of missingVars.value) {
    text = text.replace(promptVarRegExp(v), '')
  }
  emit('update:modelValue', text)
  pendingVars.value = []
  skipped.value = true
  closePanel()
  // emit 同步更新父组件 v-model，可立即触发发送
  emit('submit')
}

const onDocumentMousedown = (e) => {
  if (!panelVisible.value) return
  if (e.target?.closest?.('.pt-panel')) return
  closePanel()
}
document.addEventListener('mousedown', onDocumentMousedown)
const onViewportResize = () => {
  if (panelVisible.value || (variableMode.value && !variableCollapsed.value)) updatePanelStyle()
}
window.addEventListener('resize', onViewportResize)
watch(() => variableCollapsed.value, (v) => {
  if (!v) nextTick(updatePanelStyle)
})
onBeforeUnmount(() => {
  clearTimeout(searchTimer)
  clearTimeout(blurTimer)
  document.removeEventListener('mousedown', onDocumentMousedown)
  window.removeEventListener('resize', onViewportResize)
})

/** 发送前把变量面板中已输入但未提交的值落到正文（供父组件 send 前调用） */
const flushPendingVar = () => {
  if (!variableMode.value) return
  const name = currentVar.value
  if (!name || !varInputValue.value) return
  replaceVar(name, varInputValue.value)
  varInputValue.value = ''
}

/** 是否仍有未填写的占位符（父组件据此拦截发送） */
const hasUnfilledVars = () => variableMode.value && missingVars.value.length > 0

defineExpose({ flushPendingVar, hasUnfilledVars })
</script>

<style scoped>
.pt-input {
  position: relative;
  flex: 1;
  min-width: 0;
}
.pt-input.is-open :deep(.el-textarea__inner) {
  box-shadow: 0 0 0 1px #409eff inset;
}
.pt-panel {
  position: fixed;
  z-index: 3000;
  overflow-y: auto;
  padding: 8px;
  background: #fff;
  border: 1px solid #e4e7ed;
  border-radius: 8px;
  box-shadow: 0 8px 24px rgba(0, 0, 0, 0.12);
  box-sizing: border-box;
}
.pt-panel-head {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 2px 4px 6px;
  border-bottom: 1px solid #f2f3f5;
  margin-bottom: 4px;
}
.pt-title { font-size: 13px; font-weight: 600; color: #303133; }
.pt-tip { font-size: 12px; color: #a8abb2; }
.pt-empty { padding: 14px 6px; font-size: 13px; color: #909399; text-align: center; }
.pt-item {
  padding: 8px;
  border-radius: 6px;
  cursor: pointer;
}
.pt-item.active { background: #ecf5ff; }
.pt-item-main { display: flex; align-items: center; gap: 6px; }
.pt-name { font-size: 13px; font-weight: 600; color: #303133; }
.pt-ver { margin-left: auto; font-size: 12px; color: #a8abb2; }
.pt-desc {
  margin-top: 2px;
  font-size: 12px;
  color: #909399;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.pt-vars { margin-top: 4px; display: flex; flex-wrap: wrap; gap: 4px; }
.pt-var {
  font-family: ui-monospace, SFMono-Regular, Menlo, monospace;
  font-size: 11px;
  color: #409eff;
  background: #ecf5ff;
  border-radius: 4px;
  padding: 1px 5px;
}
.pt-var-panel { /* 宽度由 panelStyle 统一控制 */ }
.pt-var-current { display: flex; align-items: center; gap: 6px; padding: 6px 4px 2px; }
.pt-var-label { font-size: 12px; color: #909399; }
.pt-var-token {
  font-family: ui-monospace, SFMono-Regular, Menlo, monospace;
  font-size: 12px;
  background: #fff3cd;
  color: #8a6d3b;
  padding: 1px 5px;
  border-radius: 4px;
}
.pt-var-row { display: flex; gap: 8px; align-items: center; padding: 6px 4px 2px; }
.pt-var-row :deep(.el-input) { flex: 1; }
.pt-var-actions { display: flex; align-items: center; gap: 10px; padding: 6px 4px 2px; }
.pt-var-note { font-size: 12px; color: #a8abb2; }
.pt-var-bar {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 6px;
  padding: 4px 8px;
  background: #fdf6ec;
  border: 1px solid #faecd8;
  border-radius: 6px;
}
</style>
