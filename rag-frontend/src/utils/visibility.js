/**
 * 资源可见性（与后端 ResourceVisibility 保持一致）。
 * PRIVATE：仅创建者/管理员可读写；TEAM / PUBLIC：登录用户可读，仅创建者/管理员可写。
 * 后端返回空值时按 PRIVATE 处理。
 */
export const VISIBILITY_OPTIONS = [
  { value: 'PRIVATE', label: '私有', tagType: 'info', hint: '仅自己和管理员可见' },
  { value: 'TEAM', label: '团队', tagType: 'warning', hint: '登录用户可读，仅自己和管理员可写' },
  { value: 'PUBLIC', label: '公开', tagType: 'success', hint: '登录用户可读，仅自己和管理员可写' }
]

/** 取可见性展示元信息，空值/未知值统一按 PRIVATE */
export const visibilityMeta = (visibility) =>
  VISIBILITY_OPTIONS.find(o => o.value === visibility) || VISIBILITY_OPTIONS[0]

/**
 * 是否对当前用户可写（仅创建者与管理员；后端 DataScopeGuard 同口径）。
 * user 直接传 useUserStore() 实例即可（含 username / isAdmin）。
 */
export const canWriteResource = (resource, user) => {
  if (!resource) return true
  if (user?.isAdmin) return true
  return !!user?.username && resource.createBy === user.username
}
