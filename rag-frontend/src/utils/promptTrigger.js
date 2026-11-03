/**
 * Prompt 模板前缀联想的全局配置（前端本地配置，存放在 localStorage）
 * - 仅影响输入框交互，不改变后端模板数据
 * - 配置项：是否启用、前缀字符（默认 `/`）、最大候选数（1~50）
 */
const STORAGE_KEY = 'wgh_prompt_trigger_config'

export const DEFAULT_PROMPT_TRIGGER_CONFIG = {
  enabled: true,
  prefix: '/',
  maxCandidates: 10
}

const normalize = (raw) => {
  const cfg = { ...DEFAULT_PROMPT_TRIGGER_CONFIG, ...(raw || {}) }
  const prefix = String(cfg.prefix || '/').slice(0, 1) || '/'
  const max = Number(cfg.maxCandidates)
  return {
    enabled: cfg.enabled !== false,
    prefix,
    maxCandidates: Number.isFinite(max) ? Math.min(50, Math.max(1, Math.floor(max))) : 10
  }
}

export const loadPromptTriggerConfig = () => {
  try {
    const raw = localStorage.getItem(STORAGE_KEY)
    return normalize(raw ? JSON.parse(raw) : null)
  } catch {
    return { ...DEFAULT_PROMPT_TRIGGER_CONFIG }
  }
}

export const savePromptTriggerConfig = (cfg) => {
  const next = normalize(cfg)
  try {
    localStorage.setItem(STORAGE_KEY, JSON.stringify(next))
  } catch { /* localStorage 不可用时静默降级为默认配置 */ }
  return next
}

/** 变量占位符语法与后端 TemplateRenderer.VAR_PATTERN 保持一致：{{var}} */
const VAR_RE = /\{\{\s*([\w.\-]+)\s*\}\}/g

export const extractTemplateVariables = (content) => {
  const vars = []
  const seen = new Set()
  let m
  const re = new RegExp(VAR_RE.source, 'g')
  while ((m = re.exec(content || '')) !== null) {
    if (!seen.has(m[1])) {
      seen.add(m[1])
      vars.push(m[1])
    }
  }
  return vars
}

export const promptVarToken = (name) => `{{${name}}}`

export const promptVarRegExp = (name, flags = 'g') =>
  new RegExp(`\\{\\{\\s*${String(name).replace(/[.*+?^${}()|[\]\\]/g, '\\$&')}\\s*\\}\\}`, flags)
