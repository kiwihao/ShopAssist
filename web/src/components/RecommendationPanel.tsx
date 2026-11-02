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
  useEffect(() => () => controller.current?.abort(), [])
  useEffect(() => {
    if (!seed) return
    controller.current?.abort(); controller.current=null; locked.current=false; setBusy(null)
    const c=seed.criteria
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
        setForm({
          min: criteria.minPriceCny?.toString() ?? '', max: criteria.maxPriceCny?.toString() ?? '',
          minInclusive: criteria.minPriceInclusive, maxInclusive: criteria.maxPriceInclusive,
          laptop: criteria.needsLaptop, length: criteria.laptop?.lengthCm.toString() ?? '',
          width: criteria.laptop?.widthCm.toString() ?? '', thickness: criteria.laptop?.thicknessCm.toString() ?? '',
          machineWash: criteria.machineWashRequired, unsupported: criteria.unsupportedRequirements.join('\n'),
        })
        setRecognized(true)
      } else {
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
      <div className="recommend-intro"><span><Sparkles size={15} />找到合适的那一只</span><h2>先看需求，再选背包。</h2><p>支持预算、电脑外形尺寸和机洗要求。未覆盖的条件会明确提示。</p></div>
      <section className="requirements-section">
        <div className="recommend-section-title"><span>1</span><h3>说说你的需求</h3><small>也可以跳过，直接填写下方条件</small></div>
        <form onSubmit={e => { e.preventDefault(); void run('understand') }}>
          <label htmlFor="requirement-description" className="sr-only">选购需求描述</label>
          <textarea id="requirement-description" value={message} disabled={!!busy} onChange={e => changeDescription(e.target.value)} placeholder="例如：预算 200 元以内，电脑含保护壳 35×24×2 cm，想选一个背包。" rows={3} />
          <div className="requirement-examples"><button type="button" disabled={!!busy} onClick={() => changeDescription('预算150元以内，推荐一个背包')}>预算 150 元以内</button><button type="button" disabled={!!busy} onClick={() => changeDescription('预算200元以内，电脑外形35×24×2 cm，需要放电脑')}>需要放电脑</button><button type="button" disabled={!!busy} onClick={() => changeDescription('预算200元以内，必须支持机洗')}>必须能机洗</button></div>
          <div className="requirement-action"><span>{recognized ? <><Check size={14} />已识别，请核对下方条件</> : '识别结果需要你确认，不会直接推荐'}</span><button type="submit" className="outline-button" disabled={!!busy || !message.trim() || Array.from(message).length > 1000}>{busy === 'understand' ? <LoaderCircle className="spin" size={15} /> : <Sparkles size={15} />}识别条件</button></div>
        </form>
      </section>

      <section className="requirements-section">
        <div className="recommend-section-title"><span>2</span><h3>核对筛选条件</h3></div>
        <form onSubmit={e => { e.preventDefault(); void run('filter') }}>
          <fieldset disabled={!!busy}>
            <legend className="sr-only">已确认的筛选条件</legend>
            <div className="budget-fields"><label>预算下限（元）<div><select aria-label="预算下限是否包含边界" value={String(form.minInclusive)} onChange={e => update({ minInclusive: e.target.value === 'true' })}><option value="true">不低于</option><option value="false">高于</option></select><input aria-label="预算下限" type="number" min="0" max="1000000" step="0.01" placeholder="不限" value={form.min} onChange={e => update({ min: e.target.value })} /></div></label><label>预算上限（元）<div><select aria-label="预算上限是否包含边界" value={String(form.maxInclusive)} onChange={e => update({ maxInclusive: e.target.value === 'true' })}><option value="true">不超过</option><option value="false">低于</option></select><input aria-label="预算上限" type="number" min="0" max="1000000" step="0.01" placeholder="不限" value={form.max} onChange={e => update({ max: e.target.value })} /></div></label></div>
            <label className="condition-checkbox"><input type="checkbox" checked={form.laptop} onChange={e => update({ laptop: e.target.checked })} /><span>需要放电脑</span><small>使用含保护壳的机身尺寸</small></label>
            {form.laptop && <div className="laptop-fields">{(['length', 'width', 'thickness'] as const).map((key, index) => <label key={key}>{['外形长（cm）', '外形宽（cm）', '厚度（cm）'][index]}<input type="number" min="0.01" max="200" step="0.01" placeholder="待补充" value={form[key]} onChange={e => update({ [key]: e.target.value })} /></label>)}<p>屏幕英寸不能代替外形尺寸。通过尺寸筛选也不保证一定装得下。</p></div>}
            <label className="condition-checkbox"><input type="checkbox" checked={form.machineWash} onChange={e => update({ machineWash: e.target.checked })} /><span>必须支持机洗</span></label>
            <label className="unsupported-label" htmlFor="unsupported-requirements">其他硬性要求<span>当前资料待核实，每行一项；保留这些要求时不会强行推荐</span></label>
            <textarea id="unsupported-requirements" value={form.unsupported} onChange={e => update({ unsupported: e.target.value })} placeholder="例如：必须达到指定防水等级" rows={2} />
          </fieldset>
          {error && <div className="recommend-error" role="alert"><CircleAlert size={17} /><span>{error}</span></div>}
          <button type="submit" className="primary-button recommend-submit" disabled={!!busy}>{busy === 'filter' ? <LoaderCircle className="spin" size={17} /> : <Search size={17} />}确认以上条件并查找</button>
        </form>
      </section>

      {result && <section className="recommend-results" ref={resultElement} aria-live="polite">
        <div className="recommend-section-title"><span>3</span><h3>{result.status === 'CANDIDATES' ? '符合参数的候选商品' : result.status === 'NO_MATCH' ? '暂时没有符合的商品' : result.status === 'NEED_INFO' ? '还需要补充一点信息' : '这些要求还无法核实'}</h3></div>
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
  </main>
}
