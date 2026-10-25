import {
  ID_PATTERN, MONEY_PATTERN, MAX_ID, DEFAULT_PAGE, DEFAULT_PAGE_SIZE, MAX_PAGE_SIZE,
  type ApiId, type ApiMoney, type ErrorCode,
} from './generated'

export type { ApiId, ApiMoney, ErrorCode } from './generated'
export interface FieldErrorDetail { field: string; reason: string }
export interface SuccessResult<T> { code: 'OK'; message: string; data: T; traceId: string }
export interface FailureResult {
  code: ErrorCode; message: string; data: null; traceId: string; details?: FieldErrorDetail[]
}
export type Result<T> = SuccessResult<T> | FailureResult
export interface Page<T> { items: T[]; page: number; pageSize: number; total: number }

export function apiId(value: unknown): ApiId {
  if (typeof value !== 'string' || value.trim() !== value || !new RegExp(ID_PATTERN).test(value) || BigInt(value) > BigInt(MAX_ID)) {
    throw new TypeError('ID 必须为正的无符号 BIGINT 十进制字符串')
  }
  return value as ApiId
}

export function apiMoney(value: unknown): ApiMoney {
  // 禁止用浮点 Number 先计算金额再转字符串；财务计算由后端完成。
  if (typeof value !== 'string' || value.trim() !== value || !new RegExp(MONEY_PATTERN).test(value)) {
    throw new TypeError('金额必须使用两位小数字符串')
  }
  return value as ApiMoney
}

export function pageQuery(page = DEFAULT_PAGE, pageSize = DEFAULT_PAGE_SIZE) {
  if (!Number.isInteger(page) || page < 1 || page > 2147483647 || !Number.isInteger(pageSize) || pageSize < 1 || pageSize > MAX_PAGE_SIZE) {
    throw new TypeError('分页参数超出范围')
  }
  return { page, pageSize }
}
