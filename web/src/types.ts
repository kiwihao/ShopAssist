export interface Product {
  product_id: string
  name: string
  category: string
  price_cny: number
  laptop_height_cm: number
  laptop_width_cm: number
  laptop_depth_cm: number
  machine_washable?: boolean | null
}

export interface RecommendationCriteria {
  minPriceCny: number | null
  maxPriceCny: number | null
  minPriceInclusive: boolean
  maxPriceInclusive: boolean
  needsLaptop: boolean
  laptop: { lengthCm: number; widthCm: number; thicknessCm: number } | null
  machineWashRequired: boolean
  unsupportedRequirements: string[]
}

export interface Candidate {
  product: Product
  reasons: string[]
  citations: Citation[]
  evidenceStatus: 'AVAILABLE' | 'MISSING'
}

export interface RecommendationResult {
  requestId: string
  status: 'CANDIDATES' | 'NO_MATCH' | 'NEED_INFO' | 'INSUFFICIENT'
  message: string
  notice: string
  criteria: RecommendationCriteria
  candidates: Candidate[]
}

export interface Citation {
  sourceId: string
  chunk_id: string
  product_id: string
  filename: string
  version: number
  heading: string
  line_start: number
  line_end: number
  content: string
}

export interface Answer {
  productId: string
  requestId: string
  status: 'ANSWER' | 'NEED_INFO' | 'INSUFFICIENT'
  answer: string
  citations: Citation[]
}

export interface Turn {
  id: string
  question: string
  state: 'pending' | 'complete' | 'error'
  answer?: Answer
  agent?: AgentResult
  error?: { message: string; code?: string; requestId?: string }
}

export interface AgentResult {
  requestId: string
  status: string
  message: string
  errorCode?: string
  qa?: Answer
  criteria?: RecommendationCriteria
  toolCalls: number
  maxToolCalls: number
  trace: { tool: string; outcome: string; elapsedMs: number; callId: string }[]
}
