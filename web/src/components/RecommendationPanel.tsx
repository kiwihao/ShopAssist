import { useEffect, useRef, useState } from 'react'
import { ArrowRight, BookOpen, Check, CheckCheck, CircleAlert, LoaderCircle, Search, SlidersHorizontal, Sparkles } from 'lucide-react'
import { ApiError, recommendProducts, understandRequirement } from '../api'
import type { Candidate, RecommendationCriteria, RecommendationResult } from '../types'
import Backpack from './Backpack'

interface Props {
  active: boolean
  onConsult: (productId: string) => void
  onSources: (candidate: Candidate, requestId: string) => void
  onResetSources: () => void
  seed?: { message: string; criteria: RecommendationCriteria; id: string }
}

const initialCriteria: RecommendationCriteria = {
  minPriceCny: null, maxPriceCny: null, minPriceInclusive: true, maxPriceInclusive: true,
  needsLaptop: false, laptop: null, machineWashRequired: false, unsupportedRequirements: [],
}

export default function RecommendationPanel({ active, onConsult, onSources, onResetSources, seed }: Props) {
  const [message, setMessage] = useState('')
  const [form, setForm] = useState({ min: '', max: '', minInclusive: true, maxInclusive: true, laptop: false, length: '', width: '', thickness: '', machineWash: false, unsupported: '' })
  const [busy, setBusy] = useState<'understand' | 'filter' | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [recognized, setRecognized] = useState(false)
  const [result, setResult] = useState<RecommendationResult | null>(null)
  const controller = useRef<AbortController | null>(null)
  const resultElement = useRef<HTMLDivElement>(null)
  const locked = useRef(false)
  const [customBudget, setCustomBudget] = useState(false)
  const advancedBudget = customBudget || (form.min !== '' && form.min !== '0') || !form.minInclusive || !form.maxInclusive || (form.max !== '' && !['150','200'].includes(form.max))
  const dimensionNames = ['长', '宽', '厚']
  const dimensions = [form.length,form.width,form.thickness]
  const missingDimensions = form.laptop ? dimensionNames.filter((_,i)=>!dimensions[i]) : []
  const badDimension = form.laptop && dimensions.some(value=>value!=='' && (!Number.isFinite(Number(value)) || Number(value)<=0 || Number(value)>200 || Math.abs(Number(value)*100-Math.round(Number(value)*100))>0.000001))
  const budgetConflict = form.min!=='' && form.max!=='' && (Number(form.min)>Number(form.max) || (Number(form.min)===Number(form.max) && (!form.minInclusive || !form.maxInclusive)))
  const hasCondition = form.min!=='' || form.max!=='' || form.laptop || form.machineWash || !!form.unsupported.trim()
  const missingHint = message.trim() && !recognized ? '请先识别已填写的文字需求，或清空文字后直接点选条件' : missingDimensions.length ? `还需填写电脑的${missingDimensions.join('、')}（cm）` : badDimension ? '尺寸应为 0～200 cm 之间的正数，最多两位小数' : budgetConflict ? '预算上下限有冲突，请修改' : !hasCondition ? '先点选预算，或选择一项需求' : ''
  const budgetSummary = form.min==='0' && form.max==='' ? '预算不限' : [form.min!=='' ? `${form.minInclusive?'≥':'>'} ¥${form.min}` : '',form.max!=='' ? `${form.maxInclusive?'≤':'<'} ¥${form.max}` : ''].filter(Boolean).join('，') || '未限制预算'
  useEffect(() => () => controller.current?.abort(), [])
  useEffect(() => {
    if (!seed) return
    controller.current?.abort(); controller.current=null; locked.current=false; setBusy(null)
    const c=seed.criteria
    setCustomBudget(false)
    setMessage(seed.message); setRecognized(true); setResult(null); setError(null)
    setForm({ min:c.minPriceCny?.toString()??'',max:c.maxPriceCny?.toString()??'',minInclusive:c.minPriceInclusive,maxInclusive:c.maxPriceInclusive,laptop:c.needsLaptop,length:c.laptop?.lengthCm.toString()??'',width:c.laptop?.widthCm.toString()??'',thickness:c.laptop?.thicknessCm.toString()??'',machineWash:c.machineWashRequired,unsupported:c.unsupportedRequirements.join('\n') })
  }, [seed])

  function changeDescription(value: string) {
    setMessage(value); setRecognized(false); setResult(null); setError(null); onResetSources()
  }

  function update(patch: Partial<typeof form>) {
    setForm(current => ({ ...current, ...patch }))
    setResult(null); setError(null); onResetSources()
  }

  async function run(kind: 'understand' | 'filter') {
    if (locked.current) return
    if (kind === 'understand' && (!message.trim() || Array.from(message).length > 1000)) { setError('请填写 1～1000 字的需求描述。'); return }
    locked.current = true
    const request = new AbortController(); controller.current = request
    setBusy(kind); setError(null); setResult(null); onResetSources()
    try {
      if (kind === 'understand') {
        const criteria = await understandRequirement(message.trim(), request.signal)
        if(request.signal.aborted) return
        setCustomBudget(false)
        setForm({
          min: criteria.minPriceCny?.toString() ?? '', max: criteria.maxPriceCny?.toString() ?? '',
          minInclusive: criteria.minPriceInclusive, maxInclusive: criteria.maxPriceInclusive,
          laptop: criteria.needsLaptop, length: criteria.laptop?.lengthCm.toString() ?? '',
          width: criteria.laptop?.widthCm.toString() ?? '', thickness: criteria.laptop?.thicknessCm.toString() ?? '',
          machineWash: criteria.machineWashRequired, unsupported: criteria.unsupportedRequirements.join('\n'),
        })
        setRecognized(true)
      } else {
        if(missingHint) throw new ApiError(missingHint)
        const dimensions = [form.length, form.width, form.thickness]
        if (form.laptop && dimensions.some(Boolean) && !dimensions.every(Boolean)) throw new ApiError('请完整填写电脑的长、宽、厚，或清空尺寸后查看补充信息提示。')
        const criteria: RecommendationCriteria = {
          ...initialCriteria, minPriceCny: form.min === '' ? null : Number(form.min), maxPriceCny: form.max === '' ? null : Number(form.max),
          minPriceInclusive: form.minInclusive, maxPriceInclusive: form.maxInclusive, needsLaptop: form.laptop,
          laptop: form.laptop && dimensions.every(Boolean) ? { lengthCm: Number(form.length), widthCm: Number(form.width), thicknessCm: Number(form.thickness) } : null,
          machineWashRequired: form.machineWash, unsupportedRequirements: form.unsupported.split('\n').map(s => s.trim()).filter(Boolean),
        }
        const response = await recommendProducts(criteria, request.signal)
        if(request.signal.aborted) return
        setResult(response)
        requestAnimationFrame(() => resultElement.current?.scrollIntoView({ behavior: window.matchMedia('(prefers-reduced-motion: reduce)').matches ? 'instant' : 'smooth', block: 'start' }))
      }
    } catch (cause) {
      if (request.signal.aborted) return
      const detail = cause instanceof Error ? cause.message : '请求失败，请重试。'
      setError(detail + (cause instanceof ApiError && cause.code ? `（${cause.code}）` : ''))
    } finally { if(controller.current===request) { locked.current = false; setBusy(null) } }
  }

  return <main className="chat-panel recommendation-panel" hidden={!active} aria-label="条件推荐">
    <div className="chat-heading"><div><h1>帮我选商品<span className="heading-tag">条件先行</span></h1><p>把要求说清楚，先筛选，再核对商品资料。</p></div><SlidersHorizontal size={20} className="recommend-heading-icon" /></div>
    <div className="recommend-scroll">
      <div className="recommend-intro compact"><h2>点选需求，帮你挑包。</h2><p>不用先组织一段话，选好条件就能查找。</p></div>
      {recognized && <p className="recognized-note"><Check size={15}/>已带入识别出的条件，请在下方核对；可直接修改。</p>}
      <section className="requirements-section choice-section">
        <form id="recommend-form" tabIndex={-1} onSubmit={e => { e.preventDefault(); void run('filter') }}>
          <fieldset disabled={!!busy}>
            <legend className="sr-only">已确认的筛选条件</legend>
            <div className="choice-group"><h3>预算多少？</h3><div className="budget-options" role="group" aria-label="预算快捷选择">{['150','200'].map(value=><button type="button" key={value} className="choice-button" aria-pressed={!advancedBudget && form.min==='' && form.max===value && form.maxInclusive} onClick={()=>{setCustomBudget(false);update({min:'',max:value,minInclusive:true,maxInclusive:true})}}>¥{value}<small>以内</small></button>)}<button type="button" className="choice-button" aria-pressed={!advancedBudget && form.min==='0' && form.max===''} onClick={()=>{setCustomBudget(false);update({min:'0',max:'',minInclusive:true,maxInclusive:true})}}>不限预算</button><button type="button" className="choice-button" aria-pressed={advancedBudget} onClick={()=>setCustomBudget(true)}>自定义</button></div></div>
            {advancedBudget && <div className="budget-fields"><label>预算下限（元）<div><select aria-label="预算下限是否包含边界" value={String(form.minInclusive)} onChange={e => update({ minInclusive: e.target.value === 'true' })}><option value="true">不低于</option><option value="false">高于</option></select><input aria-label="预算下限" type="number" min="0" max="1000000" step="0.01" placeholder="不限" value={form.min} onChange={e => update({ min: e.target.value })} /></div></label><label>预算上限（元）<div><select aria-label="预算上限是否包含边界" value={String(form.maxInclusive)} onChange={e => update({ maxInclusive: e.target.value === 'true' })}><option value="true">不超过</option><option value="false">低于</option></select><input aria-label="预算上限" type="number" min="0" max="1000000" step="0.01" placeholder="不限" value={form.max} onChange={e => update({ max: e.target.value })} /></div></label></div>}
            <div className="choice-group"><h3>需要放电脑吗？</h3><div className="need-options" role="group" aria-label="电脑适配要求"><button type="button" className="choice-button" aria-pressed={!form.laptop} onClick={()=>update({laptop:false})}>不作要求</button><button type="button" className="choice-button" aria-pressed={form.laptop} onClick={()=>update({laptop:true})}>需要放电脑</button></div></div>
            {form.laptop && <div className="laptop-fields">{(['length', 'width', 'thickness'] as const).map((key, index) => <label key={key}>{['外形长（cm）', '外形宽（cm）', '厚度（cm）'][index]}<input type="number" min="0.01" max="200" step="0.01" placeholder="待补充" value={form[key]} onChange={e => update({ [key]: e.target.value })} /></label>)}<p>屏幕英寸不能代替外形尺寸。通过尺寸筛选也不保证一定装得下。</p></div>}
            <div className="choice-group"><h3>清洗有什么要求？</h3><div className="need-options" role="group" aria-label="清洗要求"><button type="button" className="choice-button" aria-pressed={!form.machineWash} onClick={()=>update({machineWash:false})}>不作要求</button><button type="button" className="choice-button" aria-pressed={form.machineWash} onClick={()=>update({machineWash:true})}>必须支持机洗</button></div></div>
            <details className="extra-conditions" open={!!form.unsupported.trim()}><summary>其他硬性要求{form.unsupported.trim()?'（待核实）':'（可选）'}</summary><label className="unsupported-label" htmlFor="unsupported-requirements">每行一项。当前资料未覆盖的要求会明确提示，不会静默忽略。</label><textarea id="unsupported-requirements" value={form.unsupported} onChange={e => update({ unsupported: e.target.value })} placeholder="例如：必须达到指定防水等级" rows={2} /></details>
          </fieldset>
          {error && <div className="recommend-error" role="alert"><CircleAlert size={17} /><span>{error}</span></div>}
        </form>
      </section>
      <details className="description-option"><summary><Sparkles size={15}/>想用一句话描述？展开识别条件</summary><form onSubmit={e => { e.preventDefault(); void run('understand') }}><label htmlFor="requirement-description" className="sr-only">选购需求描述</label><textarea id="requirement-description" value={message} disabled={!!busy} onChange={e => changeDescription(e.target.value)} placeholder="例如：预算不到150元，想放14寸电脑。" rows={2}/><div className="requirement-action"><span>识别后仍需核对，电脑尺寸不会自动猜测</span><button type="submit" className="outline-button" disabled={!!busy || !message.trim() || Array.from(message).length>1000}>{busy==='understand'?<LoaderCircle className="spin" size={15}/>:<Sparkles size={15}/>}识别条件</button></div></form></details>

      {result && <section className="recommend-results" ref={resultElement} aria-live="polite">
        <div className="recommend-section-title"><span><Search size={13}/></span><h3>{result.status === 'CANDIDATES' ? '符合参数的候选商品' : result.status === 'NO_MATCH' ? '暂时没有符合的商品' : result.status === 'NEED_INFO' ? '还需要补充一点信息' : '这些要求还无法核实'}</h3></div>
        <p className="recommend-result-message">{result.message}</p>
        <div className="candidate-list">{result.candidates.map(candidate => <article className="candidate-card" key={candidate.product.product_id}>
          <div className="candidate-summary"><div className={`candidate-art ${candidate.product.product_id === 'BP-B' ? 'sand' : ''}`}><Backpack variant={candidate.product.product_id} /></div><div><span className="candidate-status"><CheckCheck size={13} />参数符合</span><h4>{candidate.product.name}</h4><span className="candidate-id">{candidate.product.product_id} / {candidate.product.category}</span></div><strong className="candidate-price">¥{candidate.product.price_cny.toFixed(0)}</strong></div>
          <ul>{candidate.reasons.map(reason => <li key={reason}><Check size={14} /><span>{reason}</span></li>)}</ul>
          <div className="candidate-actions"><button className="outline-button" onClick={() => onSources(candidate, result.requestId)}><BookOpen size={14} />{candidate.citations.length ? `核对原文（${candidate.citations.length}）` : '查看资料状态'}</button><button className="text-action" onClick={() => onConsult(candidate.product.product_id)}>咨询这款<ArrowRight size={14} /></button></div>
          {candidate.evidenceStatus === 'MISSING' && <p className="candidate-warning">商品属性符合，但尚无已生效的说明原文，请先补充资料。</p>}
        </article>)}</div>
        <p className="recommend-notice"><CircleAlert size={14} /><span>{result.notice}</span></p>
      </section>}
    </div>
    <div className="recommend-action-bar"><div className="condition-review" aria-live="polite"><strong>按这些条件查找</strong><div><span>{budgetSummary}</span><span>{form.laptop ? missingDimensions.length ? '电脑尺寸待补全' : `${form.length}×${form.width}×${form.thickness} cm` : '电脑适配不限'}</span><span>{form.machineWash?'必须机洗':'清洗方式不限'}</span>{form.unsupported.trim() && <span>其他要求待核实</span>}</div></div><p className={`condition-hint ${missingHint?'incomplete':''}`} role="status">{missingHint || '只筛选符合条件的商品，资料不足会如实说明。'}</p><button form="recommend-form" type="submit" className="primary-button recommend-submit" disabled={!!busy || !!missingHint}>{busy==='filter'?<LoaderCircle className="spin" size={17}/>:<Search size={17}/>}确认条件并查找</button></div>
  </main>
}
