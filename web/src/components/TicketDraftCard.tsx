import { useRef, useState } from 'react'
import { Check, ClipboardList, LoaderCircle } from 'lucide-react'
import { ApiError, cancelTicketDraft, confirmTicketDraft, editTicketDraft } from '../api'
import type { SupportTicket, TicketDraft } from '../types'

interface Props {
  initial: TicketDraft
  savedTicket?: SupportTicket
  savedCancelled?: boolean
  onDraft: (draft: TicketDraft) => void
  onCreated: (ticket: SupportTicket) => void
  onCancelled: () => void
}
export default function TicketDraftCard({ initial, savedTicket, savedCancelled, onDraft, onCreated, onCancelled }: Props) {
  const [draft,setDraft]=useState(initial)
  const [summary,setSummary]=useState(initial.summary)
  const [confirmed,setConfirmed]=useState(false)
  const [busy,setBusy]=useState(false)
  const [error,setError]=useState('')
  const [cancelled,setCancelled]=useState(false)
  const [ticket,setTicket]=useState<SupportTicket | null>(null)
  const [uncertain,setUncertain]=useState(false)
  const key=useRef(`${initial.draft_id}_${initial.version}`)
  const lock=useRef(false)
  const dirty=summary.trim()!==draft.summary
  async function act(action: 'save'|'confirm'|'cancel') {
    if(lock.current) return
    if(action==='confirm' && (!confirmed || dirty)) return
    lock.current=true; setBusy(true); setError('')
    try {
      if(action==='save') { const next=await editTicketDraft(draft,summary.trim()); setDraft(next);setSummary(next.summary);setConfirmed(false);key.current=`${next.draft_id}_${next.version}`;onDraft(next) }
      if(action==='cancel') { await cancelTicketDraft(draft);setCancelled(true);onCancelled() }
      if(action==='confirm') { const created=await confirmTicketDraft(draft,key.current);setTicket(created);onCreated(created) }
    } catch(e) {
      setError(e instanceof Error ? e.message : '操作失败，请重试。')
      if(action==='confirm' && (!(e instanceof ApiError) || !['DRAFT_EXPIRED','DRAFT_VERSION_CONFLICT','INVALID_CONFIRMATION_TOKEN','CONFIRMATION_REQUIRED','INVALID_IDEMPOTENCY_KEY','IDEMPOTENCY_CONFLICT','DRAFT_NOT_EDITABLE'].includes(e.code??''))) setUncertain(true)
    }
    finally { lock.current=false;setBusy(false) }
  }
  const created=savedTicket??ticket
  if(created) return <section className="ticket-card ticket-created" aria-live="polite"><h3><Check size={18}/>咨询工单已创建</h3><strong className="ticket-number">{created.ticket_number}</strong><p>商品 {created.product_id} · 待处理</p><p className="ticket-summary">{created.summary}</p><p>仅登记问题，不代表退款或赔付已获批准。{created.replayed?'重复请求已返回原工单。':''}</p></section>
  if(cancelled || savedCancelled) return <section className="ticket-card"><h3>草稿已取消</h3><p>未创建正式工单。</p></section>
  return <section className="ticket-card" aria-label="咨询工单草稿">
    <h3><ClipboardList size={18}/>咨询工单草稿<span>{uncertain?'结果待确认':'未提交'}</span></h3>
    <p>商品 {draft.product_id} · 版本 {draft.version} · 有效至 {new Date(draft.expires_at).toLocaleTimeString('zh-CN',{hour:'2-digit',minute:'2-digit'})}</p>
    <label>咨询问题<textarea aria-label="工单咨询问题" rows={4} value={summary} disabled={busy || uncertain} onChange={e=>{setSummary(e.target.value);setConfirmed(false)}} /></label>
    {dirty && <button className="outline-button" disabled={busy || !summary.trim() || Array.from(summary.trim()).length>1000} onClick={()=>void act('save')}>保存修改，重新核对</button>}
    <p>{draft.notice}</p>
    <label className="ticket-confirm"><input type="checkbox" disabled={busy || dirty} checked={confirmed} onChange={e=>setConfirmed(e.target.checked)}/>我已核对当前草稿，确认创建咨询工单</label>
    {error && <p className="ticket-error" role="alert">{error}{uncertain?' 创建结果暂未确认，请用同一按钮重试；期间不修改或取消草稿。':''}</p>}
    <div className="ticket-buttons"><button className="primary-button" disabled={busy || dirty || !confirmed} onClick={()=>void act('confirm')}>{busy?<LoaderCircle size={15} className="spin"/>:<Check size={15}/>}确认创建工单</button><button className="text-action" disabled={busy || uncertain} onClick={()=>void act('cancel')}>取消草稿</button></div>
  </section>
}
