import { useCallback, useEffect, useRef, useState } from 'react'
import { ArrowDown, ArrowRight, ArrowUp, BookOpen, Check, ChevronDown, ChevronRight, CircleAlert, Copy, FileText, HelpCircle, Leaf, LoaderCircle, MessageSquareText, Package, PanelRight, Plus, RefreshCw, ShieldCheck, ShoppingBag, Sparkles, X } from 'lucide-react'
import { ApiError, askAgent, askProduct, getProducts } from './api'
import Backpack from './components/Backpack'
import SourcePanel from './components/SourcePanel'
import RecommendationPanel from './components/RecommendationPanel'
import TicketDraftCard from './components/TicketDraftCard'
import type { AgentResult, Answer, Product, RecommendationCriteria, Turn } from './types'

const suggestions = [
  { icon: Package, title: '电脑能放下吗？', detail: '先了解电脑仓尺寸', question: '这款背包的电脑仓尺寸是多少？' },
  { icon: Leaf, title: '平时怎么清洗？', detail: '看看清洁与保养要求', question: '这款背包能机洗吗？应该如何清洁？' },
  { icon: ShoppingBag, title: '适合日常通勤吗？', detail: '了解隔层和收纳设计', question: '这款背包有哪些隔层，适合什么用途？' },
]

function BrandMark({ small = false }: { small?: boolean }) {
  return <span className={`brand-mark ${small ? 'small' : ''}`}><ShoppingBag size={small ? 18 : 23} strokeWidth={1.7} /><span /></span>
}

function HelpDialog({ onClose }: { onClose: () => void }) {
  const dialog = useRef<HTMLDialogElement>(null)
  useEffect(() => { dialog.current?.showModal() }, [])
  return <dialog ref={dialog} className="help-dialog" onClose={onClose} onClick={e => { if (e.target === e.currentTarget) onClose() }}>
    <div className="dialog-heading"><BrandMark /><button className="icon-button" aria-label="关闭使用说明" onClick={onClose}><X size={20} /></button></div>
    <h2>把商品问清楚。</h2><p>可以选商品问详情，也可以用“帮我选商品”核对需求、筛选候选商品。</p>
    <ul><li><Check size={17} />先选择商品，再问尺寸、清洁或用途。</li><li><Check size={17} />点击回答下方的引用，查看文档原文。</li><li><Check size={17} />资料不全时会追问，资料未写时会说明。</li></ul>
    <div className="dialog-note">这是使用虚拟商品资料的演示。每次提问独立处理，页面中的历史不会作为下一次问题的上下文；请在问题中补全条件。记录仅保留在当前页面，刷新后清除。</div>
    <button className="primary-button" onClick={onClose}>开始提问<ArrowRight size={16} /></button>
  </dialog>
}

function CopyButton({ text }: { text: string }) {
  const [state, setState] = useState<'idle' | 'copied' | 'failed'>('idle')
  const timeout = useRef<ReturnType<typeof setTimeout> | undefined>(undefined)
  useEffect(() => () => clearTimeout(timeout.current), [])
  return <button className="text-action" onClick={async () => {
    try { await navigator.clipboard.writeText(text); setState('copied') } catch { setState('failed') }
    clearTimeout(timeout.current)
    timeout.current = setTimeout(() => setState('idle'), 2200)
  }}>{state === 'copied' ? <Check size={14} /> : <Copy size={14} />}{state === 'copied' ? '已复制' : state === 'failed' ? '复制失败，请手动选择' : '复制回答'}</button>
}

export default function App() {
  const [autoAgent, setAutoAgent] = useState(false)
  const [recommendationSeed, setRecommendationSeed] = useState<{message:string;criteria:RecommendationCriteria;id:string} | undefined>(undefined)
  const [mode, setMode] = useState<'qa' | 'recommend'>('qa')
  const [recommendationAnswer, setRecommendationAnswer] = useState<Answer | undefined>(undefined)
  const [recommendationSource, setRecommendationSource] = useState<string | null>(null)
  const [products, setProducts] = useState<Product[]>([])
  const [productsError, setProductsError] = useState<string | null>(null)
  const [loadingProducts, setLoadingProducts] = useState(true)
  const [reload, setReload] = useState(0)
  const [selectedId, setSelectedId] = useState('')
  const [histories, setHistories] = useState<Record<string, Turn[]>>({})
  const [drafts, setDrafts] = useState<Record<string, string>>({})
  const [sourceSelections, setSourceSelections] = useState<Record<string, { turnId: string; sourceId: string | null }>>({})
  const [mobileSources, setMobileSources] = useState(false)
  const [showHelp, setShowHelp] = useState(false)
  const [showJump, setShowJump] = useState(false)
  const scroll = useRef<HTMLDivElement>(null)
  const textarea = useRef<HTMLTextAreaElement>(null)
  const controllers = useRef(new Map<string, AbortController>())
  const pendingProducts = useRef(new Set<string>())

  useEffect(() => {
    const controller = new AbortController()
    setLoadingProducts(true); setProductsError(null)
    getProducts(controller.signal).then(items => {
      setProducts(items)
      setSelectedId(current => items.some(p => p.product_id === current) ? current : items[0]?.product_id ?? '')
    }).catch(error => { if (!controller.signal.aborted) setProductsError(error.message) })
      .finally(() => { if (!controller.signal.aborted) setLoadingProducts(false) })
    return () => controller.abort()
  }, [reload])

  useEffect(() => () => { controllers.current.forEach(controller => controller.abort()) }, [])
  useEffect(() => {
    if (!mobileSources) return
    const close = (event: KeyboardEvent) => { if (event.key === 'Escape') setMobileSources(false) }
    window.addEventListener('keydown', close)
    return () => window.removeEventListener('keydown', close)
  }, [mobileSources])

  const product = products.find(p => p.product_id === selectedId)
  const turns = histories[selectedId] ?? []
  const busy = turns.some(turn => turn.state === 'pending')
  const draft = drafts[selectedId] ?? ''
  const length = Array.from(draft).length
  const selectedSource = sourceSelections[selectedId]
  const activeAnswer = (turns.find(turn => turn.id === selectedSource?.turnId) ?? turns.findLast(turn => turn.state === 'complete'))?.answer
  const activeSource = selectedSource?.sourceId ?? activeAnswer?.citations[0]?.sourceId ?? null

  const jumpToBottom = useCallback((behavior: ScrollBehavior = 'smooth') => {
    scroll.current?.scrollTo({ top: scroll.current.scrollHeight, behavior })
  }, [])
  useEffect(() => { jumpToBottom('instant') }, [selectedId, jumpToBottom])
  useEffect(() => {
    const el = scroll.current
    if (el && el.scrollHeight - el.scrollTop - el.clientHeight < 360) jumpToBottom()
  }, [histories, jumpToBottom])

  const send = async (question: string, retryId?: string) => {
    const text = question.trim()
    if (!product || !text || Array.from(text).length > 1000 || pendingProducts.current.has(selectedId)) return
    const productId = selectedId
    const id = retryId ?? crypto.randomUUID()
    const controller = new AbortController()
    pendingProducts.current.add(productId); controllers.current.set(id, controller)
    const pending: Turn = { id, question: text, state: 'pending' }
    setHistories(previous => ({ ...previous, [productId]: retryId ? previous[productId].map(turn => turn.id === id ? pending : turn) : [...(previous[productId] ?? []), pending] }))
    setDrafts(previous => ({ ...previous, [productId]: '' }))
    requestAnimationFrame(() => jumpToBottom())
    try {
      const agent = autoAgent ? await askAgent(productId,text,controller.signal) : undefined
      const answer: Answer = agent ? agent.qa ?? { productId, requestId:agent.requestId, status:agent.status==='INSUFFICIENT'?'INSUFFICIENT':'NEED_INFO', answer:agent.message, citations:[] } : await askProduct(productId, text, controller.signal)
      setHistories(previous => ({ ...previous, [productId]: previous[productId].map(turn => turn.id === id ? { ...turn, state: 'complete', answer, agent } : turn) }))
      setSourceSelections(previous => ({ ...previous, [productId]: { turnId: id, sourceId: answer.citations[0]?.sourceId ?? null } }))
    } catch (error) {
      if (controller.signal.aborted) return
      const detail = error instanceof ApiError ? error : new ApiError('暂时无法获取回答，请重试。')
      setHistories(previous => ({ ...previous, [productId]: previous[productId].map(turn => turn.id === id ? { ...turn, state: 'error', error: { message: detail.message, code: detail.code, requestId: detail.requestId } } : turn) }))
    } finally {
      pendingProducts.current.delete(productId); controllers.current.delete(id)
    }
  }

  const clearHistory = () => {
    if (busy) return
    setHistories(previous => ({ ...previous, [selectedId]: [] }))
    setSourceSelections(previous => { const next = { ...previous }; delete next[selectedId]; return next })
    setMobileSources(false); textarea.current?.focus()
  }

  const selectCitation = (turnId: string, sourceId: string) => {
    setSourceSelections(previous => ({ ...previous, [selectedId]: { turnId, sourceId } }))
    setMobileSources(true)
  }
  const updateTicket = (productId: string, turnId: string, patch: Partial<AgentResult>) => {
    setHistories(previous=>({...previous,[productId]:(previous[productId]??[]).map(turn=>turn.id===turnId && turn.agent ? {...turn,agent:{...turn.agent,...patch},answer:turn.answer && patch.message ? {...turn.answer,answer:patch.message}:turn.answer}:turn)}))
  }

  return <div className="app-shell">
    <a className="skip-link" href={mode === 'qa' ? '#question-input' : '#requirement-description'}>跳到提问框</a>
    <header className="topbar">
      <a className="brand" href="/" aria-label="ShopAssist 首页"><BrandMark /><span>ShopAssist<span className="brand-period">.</span></span></a>
      <div className="topbar-center"><span className="workspace-label">电商智能助手</span><ChevronRight size={14} /><span>{mode === 'qa' ? '商品问答' : '帮我选商品'}</span></div>
      <div className="topbar-actions"><span className={`connection-badge ${productsError ? 'offline' : ''}`}><span />{loadingProducts ? '连接中' : productsError ? '后端未连接' : '后端已连接'}</span><span className="demo-label">演示空间</span><button className="icon-button help-button" aria-label="查看使用说明" onClick={() => setShowHelp(true)}><HelpCircle size={20} /></button></div>
    </header>

    <aside className="catalog-sidebar" aria-label="商品选择">
      <div className="workspace-selector"><span className="store-icon"><ShoppingBag size={18} /></span><div><strong>轻行生活</strong><span>虚拟演示店铺</span></div><span className="store-badge">Demo</span></div>
      <nav className="workspace-nav" aria-label="功能"><button className={`workspace-nav-button ${mode === 'qa' ? 'active-nav' : ''}`} aria-current={mode === 'qa' ? 'page' : undefined} onClick={() => { setMode('qa'); setMobileSources(false) }}><MessageSquareText size={18} /><span>商品问答</span>{mode === 'qa' && <span className="nav-dot" />}</button><button className={`workspace-nav-button ${mode === 'recommend' ? 'active-nav' : ''}`} aria-current={mode === 'recommend' ? 'page' : undefined} onClick={() => { setMode('recommend'); setMobileSources(false) }}><Sparkles size={18} /><span>帮我选商品</span>{mode === 'recommend' && <span className="nav-dot" />}</button></nav>
      <div className="catalog-heading"><h2>选择咨询商品</h2><span>{products.length.toString().padStart(2, '0')}</span></div>
      <div className="product-list">
        {loadingProducts ? <div className="catalog-loading"><LoaderCircle className="spin" size={18} />正在加载商品</div> : productsError ? <div className="catalog-error"><CircleAlert size={20} /><p>商品加载失败</p><button className="text-action" onClick={() => setReload(n => n + 1)}><RefreshCw size={14} />重新加载</button></div> : products.length === 0 ? <p className="empty-catalog">暂无可咨询商品，请先添加商品资料。</p> : products.map(item => <button key={item.product_id} className={`product-card ${item.product_id === selectedId ? 'selected' : ''}`} aria-pressed={item.product_id === selectedId} onClick={() => { setMode('qa'); setSelectedId(item.product_id); setMobileSources(false) }}>
          <span className={`product-art ${item.product_id === 'BP-B' ? 'sand' : ''}`}><Backpack variant={item.product_id} /></span>
          <span className="product-card-content"><strong>{item.name}</strong><span>{item.product_id} / {item.category}</span><span className="product-price">¥{item.price_cny.toFixed(0)}<span className="selection-check">{item.product_id === selectedId ? <Check size={13} /> : <ChevronRight size={14} />}</span></span></span>
        </button>)}
      </div>
      <div className="sidebar-note"><span className="note-icon"><ShieldCheck size={20} /></span><h3>选对商品，问得更准。</h3><p>每次只查阅当前商品资料，尺寸与清洁要求不会混在一起。</p><span>虚拟商品与演示价格</span></div>
      <button className="sidebar-help" onClick={() => setShowHelp(true)}><HelpCircle size={17} /><span>使用说明</span><ChevronRight size={15} /></button>
    </aside>

    <main className="chat-panel" aria-label="商品问答" hidden={mode !== 'qa'}>
      <div className="chat-heading"><div><h1>商品问答<span className="heading-tag">有据可查</span></h1><p>关于商品的每个疑问，从资料里找答案。</p></div><div className="chat-heading-actions"><button className="icon-button source-mobile-trigger" aria-label="查看回答依据" onClick={() => setMobileSources(true)}><PanelRight size={19} /></button><button className="new-question-button" onClick={clearHistory} disabled={busy || !turns.length}><Plus size={16} /><span>清空问答</span></button></div></div>
      <div className="selected-product-bar"><span><span className="tiny-dot" />正在咨询 <strong>{product?.name ?? (loadingProducts ? '加载中…' : '请选择商品')}</strong></span><label className="agent-toggle"><input type="checkbox" checked={autoAgent} disabled={busy} onChange={e=>setAutoAgent(e.target.checked)} />客服自动选工具</label></div>

      <div className="conversation-scroll" ref={scroll} onScroll={() => { const el = scroll.current; if (el) setShowJump(el.scrollHeight - el.scrollTop - el.clientHeight > 240) }}>
        {!turns.length ? <div className="welcome">
          <div className="welcome-hero"><div className="welcome-copy"><span className="welcome-kicker"><Sparkles size={15} />你的商品咨询助手</span><h2>关于这只包，<br />你想了解什么？</h2><p>尺寸、收纳，或是清洁方式。<br />选一个问题，让我们一起翻翻商品说明。</p></div><div className="hero-art"><span className="orbit orbit-one" /><span className="orbit orbit-two" /><Backpack variant={selectedId} /><span className="hero-art-label"><span className="tiny-dot" />{product?.name ?? '商品说明'}</span></div></div>
          <div className="suggestions-heading"><span>从一个小问题开始</span><span>也可以直接输入</span></div>
          <div className="suggestion-list">{suggestions.map(({ icon: Icon, title, detail, question }) => <button key={title} className="suggestion" onClick={() => void send(question)} disabled={!product || busy}><span className="suggestion-icon"><Icon size={19} strokeWidth={1.6} /></span><span><strong>{title}</strong><small>{detail}</small></span><ArrowRight size={17} /></button>)}</div>
          <div className="welcome-promise"><FileText size={15} /><span>回答附原文引用</span><span className="promise-separator" /><CircleAlert size={15} /><span>资料未写，如实说明</span></div>
        </div> : <div className="conversation" aria-live="polite" aria-relevant="additions text">
          <div className="conversation-start"><span />正在咨询 {product?.name}<span /></div>
          {turns.map(turn => <section className="turn" key={turn.id}>
            <div className="user-message"><span className="user-bubble">{turn.question}</span><span className="user-avatar">我</span></div>
            <div className="assistant-message"><BrandMark small /><div className="assistant-content"><div className="assistant-name">ShopAssist<span>商品助手</span></div>
              {turn.state === 'pending' ? <div className="thinking" role="status"><span className="thinking-dots"><i /><i /><i /></span>正在查阅商品资料并整理回答…</div> : turn.state === 'error' ? <div className="answer-error" role="alert"><CircleAlert size={19} /><div><strong>这次没能获取回答</strong><p>{turn.error?.message}</p><div className="error-details">{turn.error?.code}{turn.error?.requestId && <span>请求编号：{turn.error.requestId}</span>}</div><button className="text-action" disabled={busy} onClick={() => void send(turn.question, turn.id)}><RefreshCw size={14} />重新获取回答</button></div></div> : turn.answer && <>
                <span className={`answer-status ${turn.answer.status.toLowerCase()}`}>{turn.answer.status === 'ANSWER' ? <BookOpen size={13} /> : <CircleAlert size={13} />}{turn.agent?.ticket ? '咨询工单已创建' : turn.agent?.draftCancelled ? '草稿已取消' : turn.agent?.ticketDraft ? '待确认工单草稿' : turn.agent?.status === 'NEED_CONFIRMATION' ? '待核对推荐条件' : turn.agent?.status === 'UNSUPPORTED' ? '当前能力范围' : turn.answer.status === 'ANSWER' ? '来自商品资料' : turn.answer.status === 'NEED_INFO' ? '需要补充信息' : '资料暂未说明'}</span>
                <p className="answer-text">{turn.answer.answer}</p>
                {turn.agent?.ticketDraft && <TicketDraftCard initial={turn.agent.ticketDraft} savedTicket={turn.agent.ticket} savedCancelled={turn.agent.draftCancelled} onDraft={ticketDraft=>updateTicket(selectedId,turn.id,{ticketDraft})} onCreated={ticket=>updateTicket(selectedId,turn.id,{ticket,status:'TICKET_CREATED',message:'咨询工单已创建，可查看下方编号与登记内容。'})} onCancelled={()=>updateTicket(selectedId,turn.id,{draftCancelled:true,status:'DRAFT_CANCELLED',message:'草稿已取消，未创建正式工单。'})} />}
                {turn.agent && <div className="agent-record"><span>工具调用 {turn.agent.toolCalls}/{turn.agent.maxToolCalls}</span>{turn.agent.trace.map(step=><span key={step.callId}>{step.tool==='query_product_knowledge'?'商品知识问答':step.tool==='prepare_recommendation'?'准备推荐条件':step.tool==='prepare_support_ticket'?'准备咨询工单草稿':step.tool} · {step.outcome} · {step.elapsedMs} ms</span>)}</div>}
                {turn.agent?.criteria && <button className="outline-button agent-review" disabled={busy} onClick={()=>{ setRecommendationSeed({message:turn.question,criteria:turn.agent!.criteria!,id:crypto.randomUUID()});setMode('recommend');setMobileSources(false) }}>核对推荐条件<ArrowRight size={14} /></button>}
                {turn.answer.citations.length > 0 && <div className="citation-row"><span>查看依据</span>{turn.answer.citations.map(source => <button key={source.sourceId} className={`citation-chip ${selectedSource?.turnId === turn.id && activeSource === source.sourceId ? 'active' : ''}`} onClick={() => selectCitation(turn.id, source.sourceId)}><FileText size={13} /><span>{source.heading.split(' / ').at(-1)}</span><span className="citation-id">{source.sourceId}</span><ChevronRight size={12} /></button>)}</div>}
                <div className="answer-actions"><CopyButton text={turn.answer.answer} /><span className="request-id" title={turn.answer.requestId}>请求 {turn.answer.requestId.slice(0, 8)}</span></div>
              </>}
            </div></div>
          </section>)}
        </div>}
      </div>
      <div className="composer-area">
        {showJump && <button className="jump-button" onClick={() => jumpToBottom()}><ArrowDown size={14} />回到最新回答</button>}
        <form className={`composer ${length > 1000 ? 'invalid' : ''}`} onSubmit={e => { e.preventDefault(); void send(draft) }}>
          <label className="sr-only" htmlFor="question-input">向商品助手提问</label>
          <textarea id="question-input" ref={textarea} rows={2} placeholder={product ? `想了解「${product.name}」的什么？` : '请先选择商品'} value={draft} disabled={!product || busy} onChange={e => setDrafts(previous => ({ ...previous, [selectedId]: e.target.value }))} onKeyDown={e => { if (e.key === 'Enter' && !e.shiftKey && !e.nativeEvent.isComposing && e.keyCode !== 229) { e.preventDefault(); void send(draft) } }} aria-describedby="composer-note" />
          <div className="composer-toolbar"><span className="composer-scope"><ShoppingBag size={13} /><span>{product?.name ?? '等待选择商品'}</span><ChevronDown size={12} /></span><div><span className={`character-count ${length > 1000 ? 'over-limit' : ''}`}>{length > 0 ? `${length} / 1000` : 'Enter 发送'}</span><button type="submit" className="send-button" aria-label="发送问题" disabled={!product || busy || !draft.trim() || length > 1000}>{busy ? <LoaderCircle size={19} className="spin" /> : <ArrowUp size={20} />}</button></div></div>
        </form>
        <div id="composer-note" className="composer-note"><span>{length > 1000 ? '问题最多 1000 字，请精简后发送。' : '每次独立回答，请在问题中补全条件；重要信息请核对原文。'}</span><span>Shift + Enter 换行</span></div>
      </div>
    </main>
    <RecommendationPanel seed={recommendationSeed} active={mode === 'recommend'} onConsult={productId => { setSelectedId(productId); setMode('qa'); setMobileSources(false) }} onResetSources={() => { setRecommendationAnswer(undefined); setRecommendationSource(null) }} onSources={(candidate, requestId) => {
      setRecommendationAnswer({ productId: candidate.product.product_id, requestId, status: candidate.citations.length ? 'ANSWER' : 'INSUFFICIENT', answer: candidate.reasons.join('\n'), citations: candidate.citations })
      setRecommendationSource(candidate.citations[0]?.sourceId ?? null); setMobileSources(true)
    }} />
    <SourcePanel product={mode === 'qa' ? product : products.find(item => item.product_id === recommendationAnswer?.productId)} answer={mode === 'qa' ? activeAnswer : recommendationAnswer} activeSource={mode === 'qa' ? activeSource : recommendationSource} onSource={sourceId => {
      if (mode === 'recommend') { setRecommendationSource(previous => previous === sourceId ? null : sourceId); return }
      const turnId = selectedSource?.turnId ?? turns.findLast(turn => turn.state === 'complete')?.id
      if (turnId) setSourceSelections(previous => ({ ...previous, [selectedId]: { turnId, sourceId: activeSource === sourceId ? '' : sourceId } }))
    }} mobileOpen={mobileSources} onClose={() => setMobileSources(false)} />
    {showHelp && <HelpDialog onClose={() => setShowHelp(false)} />}
  </div>
}
