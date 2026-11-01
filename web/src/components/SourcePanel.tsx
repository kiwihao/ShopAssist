import { useEffect, useRef, useState } from 'react'
import { BookOpen, ChevronRight, FileText, Quote, X } from 'lucide-react'
import type { Answer, Product } from '../types'

interface Props {
  product?: Product
  answer?: Answer
  activeSource: string | null
  onSource: (id: string) => void
  mobileOpen: boolean
  onClose: () => void
}

export default function SourcePanel({ product, answer, activeSource, onSource, mobileOpen, onClose }: Props) {
  const sources = answer?.citations ?? []
  const panel = useRef<HTMLElement>(null)
  const [narrow, setNarrow] = useState(() => window.matchMedia('(max-width: 1080px)').matches)
  useEffect(() => {
    const query = window.matchMedia('(max-width: 1080px)')
    const update = () => setNarrow(query.matches)
    query.addEventListener('change', update)
    return () => query.removeEventListener('change', update)
  }, [])
  useEffect(() => {
    if (!narrow || !mobileOpen) return
    const previous = document.activeElement as HTMLElement | null
    const background = document.querySelectorAll<HTMLElement>('.skip-link, .topbar, .catalog-sidebar, .chat-panel')
    background.forEach(element => { element.inert = true })
    // Wait until the drawer's visible style has been applied before focusing it.
    const frame = requestAnimationFrame(() => panel.current?.querySelector<HTMLButtonElement>('button')?.focus())
    return () => {
      cancelAnimationFrame(frame)
      background.forEach(element => { element.inert = false })
      previous?.focus()
    }
  }, [narrow, mobileOpen])
  return <>
    {mobileOpen && <button className="panel-backdrop" aria-label="关闭资料面板" tabIndex={-1} onClick={onClose} />}
    <aside ref={panel} className={`sources-panel ${mobileOpen ? 'is-open' : ''}`} aria-label="回答依据" role={narrow && mobileOpen ? 'dialog' : undefined} aria-modal={narrow && mobileOpen ? true : undefined} onTransitionEnd={event => {
      // During the entering transition the button can still be off-screen.
      if (event.target === event.currentTarget && narrow && mobileOpen && !panel.current?.contains(document.activeElement)) {
        panel.current?.querySelector<HTMLButtonElement>('button')?.focus()
      }
    }} onKeyDown={event => {
      if (!narrow || !mobileOpen || event.key !== 'Tab') return
      const buttons = panel.current?.querySelectorAll<HTMLButtonElement>('button')
      if (!buttons?.length) return
      const first = buttons[0], last = buttons[buttons.length - 1]
      if (event.shiftKey && document.activeElement === first) { event.preventDefault(); last.focus() }
      else if (!event.shiftKey && document.activeElement === last) { event.preventDefault(); first.focus() }
    }}>
      <div className="sources-heading"><div><BookOpen size={18} /><h2>回答依据</h2>{sources.length > 0 && <span className="count-badge">{sources.length}</span>}</div><button className="icon-button mobile-close" aria-label="关闭资料面板" onClick={onClose}><X size={19} /></button></div>
      <div className="sources-scroll">
        {sources.length > 0 ? <>
          <p className="panel-description">本次回答引用的商品原文。点击标题展开核对。</p>
          <div className="source-list">
            {sources.map(source => {
              const opened = activeSource === source.sourceId
              return <section className={`source-document ${opened ? 'expanded' : ''}`} key={source.sourceId}>
                <button className="source-toggle" aria-expanded={opened} aria-controls={`source-${source.sourceId}`} onClick={() => onSource(source.sourceId)}>
                  <span className="source-index">{source.sourceId}</span><span className="source-title">{source.heading.split(' / ').at(-1)}<small>{source.filename}</small></span><ChevronRight size={16} />
                </button>
                {opened && <div id={`source-${source.sourceId}`} className="source-body">
                  <div className="source-meta"><span>版本 v{source.version}</span><span>第 {source.line_start}–{source.line_end} 行</span></div>
                  <blockquote>{source.content}</blockquote>
                  <span className="source-product">适用商品 {source.product_id}</span>
                </div>}
              </section>
            })}
          </div>
          <p className="source-footnote"><Quote size={14} />引用方便核查，具体结论仍需结合原文理解。</p>
        </> : <div className="sources-empty">
          <div className="document-sketch"><FileText size={27} strokeWidth={1.35} /><span /><span /><span /></div>
          <h3>{answer ? '本次没有引用资料' : '答案从哪里来？'}</h3>
          <p>{answer ? '当前回答未返回引用。资料不足时，我们会明确说明。' : '提问后，这里会显示引用的文档、版本与原文，方便你逐条核对。'}</p>
          {!answer && <div className="source-preview"><FileText size={15} /><span>商品说明</span><span>原文片段</span></div>}
        </div>}
        {product && <div className="product-facts"><span className="section-caption">当前商品</span><h3>{product.name}</h3><dl><div><dt>商品编号</dt><dd>{product.product_id}</dd></div><div><dt>商品分类</dt><dd>{product.category}</dd></div><div><dt>演示价格</dt><dd>¥{product.price_cny.toFixed(2)}</dd></div></dl></div>}
      </div>
      <div className="sources-bottom"><span className="tiny-dot" />仅查阅当前商品的资料</div>
    </aside>
  </>
}
