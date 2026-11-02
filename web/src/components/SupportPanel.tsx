import { useRef, useState } from 'react'
import { ClipboardList, LoaderCircle, ShieldCheck } from 'lucide-react'
import { prepareTicketDraft } from '../api'
import type { Product, SupportTicket, TicketDraft } from '../types'
import TicketDraftCard from './TicketDraftCard'

interface Props { active: boolean; products: Product[]; selectedId: string }

export default function SupportPanel({ active, products, selectedId }: Props) {
  const [productId, setProductId] = useState('')
  const [summary, setSummary] = useState('')
  const [draft, setDraft] = useState<TicketDraft>()
  const [ticket, setTicket] = useState<SupportTicket>()
  const [cancelled, setCancelled] = useState(false)
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState('')
  const lock = useRef(false)
  const selectedProduct = productId || selectedId
  const length = Array.from(summary.trim()).length

  async function prepare() {
    if (lock.current || !selectedProduct || length < 1 || length > 1000) return
    lock.current = true; setBusy(true); setError('')
    try { setDraft(await prepareTicketDraft(selectedProduct, summary.trim())) }
    catch (e) { setError(e instanceof Error ? e.message : '草稿生成失败，请重试。') }
    finally { lock.current = false; setBusy(false) }
  }
  function reset() { setDraft(undefined); setTicket(undefined); setCancelled(false); setSummary(''); setError('') }

  return <main className="support-panel" hidden={!active} aria-label="售后服务">
    <div className="support-scroll">
      <div className="support-title"><span className="support-title-icon"><ClipboardList size={22}/></span><div><h1>售后服务</h1><p>遇到商品问题？填写咨询内容，核对后创建工单。</p></div></div>
      <div className="support-steps" aria-label="工单创建步骤"><span className={!draft ? 'current' : ''}>1 填写问题</span><i/><span className={draft && !ticket && !cancelled ? 'current' : ''}>2 核对草稿</span><i/><span className={ticket ? 'current' : ''}>3 确认创建</span></div>
      {!draft ? <form className="support-form" id="support-form" onSubmit={e => { e.preventDefault(); void prepare() }}>
        <h2>登记咨询问题</h2><p>选择相关商品，简单描述你遇到的情况。</p>
        <label htmlFor="support-product">咨询商品</label>
        <select id="support-product" value={selectedProduct} disabled={busy || !products.length} onChange={e => setProductId(e.target.value)}>
          {!products.length && <option value="">暂无可选商品</option>}
          {products.map(product => <option key={product.product_id} value={product.product_id}>{product.name}（{product.product_id}）</option>)}
        </select>
        <label htmlFor="support-summary">问题描述</label>
        <textarea id="support-summary" rows={5} value={summary} disabled={busy} onChange={e => setSummary(e.target.value)} placeholder="例如：背包的拉链使用时不顺畅，想咨询处理方式。" />
        <div className="support-count">{length} / 1000</div>
        {error && <p className="support-error" role="alert">{error}</p>}
        <button className="primary-button support-submit" type="submit" disabled={busy || !selectedProduct || length < 1 || length > 1000}>{busy ? <LoaderCircle size={16} className="spin"/> : <ClipboardList size={16}/>}生成工单草稿</button>
      </form> : <div className="support-form"><h2>{ticket ? '提交完成' : cancelled ? '草稿已取消' : '请核对工单内容'}</h2>
        <TicketDraftCard key={draft.draft_id} initial={draft} savedTicket={ticket} savedCancelled={cancelled} onDraft={setDraft} onCreated={setTicket} onCancelled={() => setCancelled(true)} />
        {(ticket || cancelled) && <button className="outline-button" onClick={reset}>登记新的咨询</button>}
      </div>}
      <div className="support-note"><ShieldCheck size={18}/><p><strong>先核对，再提交</strong><br/>这里只登记售后咨询，不代表退款、退货或赔付已获批准。草稿和结果仅在当前页面显示，请提交后保存工单编号；刷新后无法从页面找回。</p></div>
    </div>
  </main>
}
