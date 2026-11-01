import type { Answer, Product, RecommendationCriteria, RecommendationResult } from './types'

export class ApiError extends Error {
  code?: string
  requestId?: string
  constructor(message: string, code?: string, requestId?: string) {
    super(message)
    this.code = code
    this.requestId = requestId
  }
}

async function request(path: string, init: RequestInit = {}, timeoutMs = 15_000): Promise<unknown> {
  try {
    const response = await fetch(path, {
      ...init,
      signal: init.signal ? AbortSignal.any([init.signal, AbortSignal.timeout(timeoutMs)]) : AbortSignal.timeout(timeoutMs),
    })
    const data = await response.json().catch(() => null)
    if (!response.ok) {
      throw new ApiError(data?.message || '暂时无法连接问答服务，请检查后端是否已启动后重试。', data?.code || `HTTP_${response.status}`, data?.requestId)
    }
    if (!data) throw new ApiError('服务返回的内容不完整，请重试。', 'INVALID_RESPONSE')
    return data
  } catch (error) {
    if (error instanceof ApiError) throw error
    if (error instanceof DOMException && error.name === 'AbortError') throw error
    if (error instanceof DOMException && error.name === 'TimeoutError') throw new ApiError('等待回答超时，请稍后重试。', 'REQUEST_TIMEOUT')
    throw new ApiError('暂时无法连接服务，请检查网络和后端运行状态。', 'NETWORK_ERROR')
  }
}

export async function getProducts(signal: AbortSignal): Promise<Product[]> {
  const data = await request('/api/products', { signal })
  if (!Array.isArray(data) || !data.every(p => typeof p.product_id === 'string' && typeof p.name === 'string' && typeof p.price_cny === 'number')) {
    throw new ApiError('商品列表格式异常，请检查服务后重试。', 'INVALID_PRODUCTS')
  }
  return data
}

export async function askProduct(productId: string, question: string, signal: AbortSignal): Promise<Answer> {
  const data = await request('/api/qa', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ productId, question }),
    signal,
  }, 210_000) as Answer
  if (data.productId !== productId || !['ANSWER', 'NEED_INFO', 'INSUFFICIENT'].includes(data.status) || typeof data.answer !== 'string'
      || !Array.isArray(data.citations) || !data.citations.every(c => c.product_id === productId && typeof c.content === 'string' && typeof c.heading === 'string' && typeof c.sourceId === 'string')) {
    throw new ApiError('回答格式或商品范围异常，请重试。', 'INVALID_ANSWER')
  }
  return data
}

export async function understandRequirement(message: string, signal: AbortSignal): Promise<RecommendationCriteria> {
  const data = await request('/api/recommendations/understand', {
    method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ message }), signal,
  }, 75_000) as { criteria: RecommendationCriteria }
  if (!data.criteria || !Array.isArray(data.criteria.unsupportedRequirements)) throw new ApiError('需求识别格式异常，请直接填写条件。', 'INVALID_REQUIREMENT_RESPONSE')
  return data.criteria
}

export async function recommendProducts(criteria: RecommendationCriteria, signal: AbortSignal): Promise<RecommendationResult> {
  const data = await request('/api/recommendations', {
    method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(criteria), signal,
  }) as RecommendationResult
  if (!['CANDIDATES', 'NO_MATCH', 'NEED_INFO', 'INSUFFICIENT'].includes(data.status) || typeof data.message !== 'string'
    || !Array.isArray(data.candidates) || !data.candidates.every(c => typeof c.product?.product_id === 'string' && typeof c.product?.price_cny === 'number'
      && Array.isArray(c.reasons) && Array.isArray(c.citations) && c.citations.every(s => s.product_id === c.product.product_id && typeof s.content === 'string'))) {
    throw new ApiError('推荐结果格式异常，请重试。', 'INVALID_RECOMMENDATION')
  }
  return data
}
