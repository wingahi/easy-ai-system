/**
 * 统一触发表单校验。
 * Element Plus 的 validate() 在校验失败时会 reject，若调用方直接 await 而不捕获，
 * 会产生未处理的 Promise 拒绝（控制台报未捕获错误）。此处统一收敛为布尔结果，
 * 调用方只需在校验失败时提前返回。
 *
 * @param {{ value: { validate: () => Promise<unknown> } }} formRef 表单 ref
 * @returns {Promise<boolean>} 校验是否通过
 */
export async function validateForm(formRef) {
  try {
    await formRef.value.validate()
    return true
  } catch {
    return false
  }
}
