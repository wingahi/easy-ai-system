/**
 * 前端通用常量：localStorage key、业务码、HTTP 状态码与鉴权头。
 * 业务码需与后端 ResultCode 保持一致，禁止在前端散落裸数字。
 */
export const STORAGE_KEYS = {
  TOKEN: 'wgh_token',
  USERNAME: 'wgh_username',
  USER_ID: 'wgh_userId',
  ROLES: 'wgh_roles',
  PWD_MUST_CHANGE: 'wgh_pwd_must_change',
  VISITOR_ID: 'wgh_visitor_id'
}

export const BUSINESS_CODE = {
  SUCCESS: 200,
  UNAUTHORIZED: 1001,
  TOKEN_EXPIRED: 1002,
  PASSWORD_CHANGE_REQUIRED: 1006,
  SESSION_INVALID: 1007
}

export const HTTP_STATUS = {
  UNAUTHORIZED: 401,
  FORBIDDEN: 403
}

export const AUTH_HEADER = 'Authorization'
export const BEARER_PREFIX = 'Bearer '
