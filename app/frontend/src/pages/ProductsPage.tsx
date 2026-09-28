import { useState } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { api } from '../api/client'
import type { CatalogStatus, ProductView } from '../api/types'

// Catálogo (spec 2026-09-28). Los agentes pueden llevar un producto hasta "listo para vender" si Java verifica los
// requisitos; tú (el Command Center actúa como el fundador) editas, pausas, reanudas, retiras y reactivas.

const STATUS_LABELS: Record<CatalogStatus, string> = {
  READY_TO_SELL: 'Listos para vender',
  IN_CONSTRUCTION: 'En construcción',
  IDEA: 'Ideas',
  PAUSED: 'Pausados',
  RETIRED: 'Retirados',
}
const ORDER: CatalogStatus[] = ['READY_TO_SELL', 'IN_CONSTRUCTION', 'IDEA', 'PAUSED', 'RETIRED']
const usd = (n: number) => `US$${n.toFixed(2)}`
const list = (text: string) => text.split(',').map((s) => s.trim()).filter(Boolean)

function useRefresh() {
  const queryClient = useQueryClient()
  return () => {
    void queryClient.invalidateQueries({ queryKey: ['products'] })
    void queryClient.invalidateQueries({ queryKey: ['finance'] })
  }
}

function StatusButtons({ view }: { view: ProductView }) {
  const refresh = useRefresh()
  const [reason, setReason] = useState('')
  const mutation = useMutation({
    mutationFn: (status: string) => api.changeProductStatus(view.product.id, status, reason),
    onSuccess: () => {
      setReason('')
      refresh()
    },
  })
  const s = view.product.status
  const buttons: Array<[string, string]> =
    s === 'PAUSED'
      ? [['', 'Reanudar'], ['RETIRED', 'Retirar']]
      : s === 'RETIRED'
        ? [['IDEA', 'Reactivar como idea']]
        : [
            ...(s !== 'IDEA' ? ([['IDEA', 'Volver a idea']] as Array<[string, string]>) : []),
            ...(s !== 'IN_CONSTRUCTION' ? ([['IN_CONSTRUCTION', 'En construcción']] as Array<[string, string]>) : []),
            ...(s !== 'READY_TO_SELL' ? ([['READY_TO_SELL', 'Listo para vender']] as Array<[string, string]>) : []),
            ['PAUSED', 'Pausar'],
            ['RETIRED', 'Retirar'],
          ]
  return (
    <div className="decision-form">
      <input placeholder="Motivo (opcional)" value={reason} onChange={(e) => setReason(e.target.value)} />
      {buttons.map(([status, label]) => (
        <button key={label} disabled={mutation.isPending} onClick={() => mutation.mutate(status)}>
          {label}
        </button>
      ))}
      {mutation.isError && <p className="error">{mutation.error.message}</p>}
    </div>
  )
}

function ProductEditor({ view }: { view: ProductView }) {
  const refresh = useRefresh()
  const p = view.product
  const missions = useQuery({ queryKey: ['missions'], queryFn: api.missions })
  const finance = useQuery({ queryKey: ['finance', 'product', p.id], queryFn: () => api.finance(undefined, p.id) })
  const [form, setForm] = useState({
    name: p.name,
    description: p.description ?? '',
    kind: p.kind,
    targetCustomer: p.targetCustomer ?? '',
    priceUsd: p.priceUsd,
    priceOnRequest: p.priceOnRequest,
    estimatedCostUsd: p.estimatedCostUsd,
    delivery: p.delivery ?? '',
    markets: p.markets.join(', '),
    languages: p.languages.join(', '),
    reason: '',
  })
  const [validatedBy, setValidatedBy] = useState<string[]>(p.validatedBy)
  const [builtBy, setBuiltBy] = useState<string[]>(p.builtBy)
  const save = useMutation({
    mutationFn: () =>
      api.updateProduct(p.id, { ...form, markets: list(form.markets), languages: list(form.languages) }),
    onSuccess: refresh,
  })
  const link = useMutation({ mutationFn: () => api.linkProductMissions(p.id, validatedBy, builtBy), onSuccess: refresh })
  const missionOptions = (missions.data ?? []).map((m) => m.missionId)
  const toggle = (values: string[], id: string) => (values.includes(id) ? values.filter((v) => v !== id) : [...values, id])

  return (
    <div className="card">
      <h3>
        {p.name} <span className="hint">({p.kind === 'SERVICE' ? 'servicio' : 'software'})</span>
      </h3>
      <p className="hint">
        {view.missing.length === 0
          ? p.status === 'READY_TO_SELL'
            ? '✅ Cumple todos los requisitos para venderse.'
            : '✅ Cumple los requisitos: puede pasar a listo para vender.'
          : view.missing.map((m) => `❌ ${m}`).join(' ')}
      </p>
      {finance.data && (
        <p className="hint">
          Ventas del producto: ingresos {usd(finance.data.revenueUsd)}, costos {usd(finance.data.costsUsd)}, ganancias{' '}
          {usd(finance.data.profitUsd)}.
        </p>
      )}
      <StatusButtons view={view} />
      <form className="decision-form" onSubmit={(e) => { e.preventDefault(); save.mutate() }}>
        <input value={form.name} onChange={(e) => setForm({ ...form, name: e.target.value })} placeholder="Nombre" />
        <textarea value={form.description} onChange={(e) => setForm({ ...form, description: e.target.value })} placeholder="Descripción" />
        <select value={form.kind} onChange={(e) => setForm({ ...form, kind: e.target.value as 'SOFTWARE' | 'SERVICE' })}>
          <option value="SOFTWARE">Software</option>
          <option value="SERVICE">Servicio</option>
        </select>
        <input value={form.targetCustomer} onChange={(e) => setForm({ ...form, targetCustomer: e.target.value })} placeholder="A quién se le vende" />
        <label>
          Precio (US$) <input type="number" step="0.01" min="0" value={form.priceUsd} onChange={(e) => setForm({ ...form, priceUsd: Number(e.target.value) })} />
        </label>
        <label>
          <input type="checkbox" checked={form.priceOnRequest} onChange={(e) => setForm({ ...form, priceOnRequest: e.target.checked })} /> A cotizar
        </label>
        <label>
          Costo estimado por venta (US$){' '}
          <input type="number" step="0.01" min="0" value={form.estimatedCostUsd} onChange={(e) => setForm({ ...form, estimatedCostUsd: Number(e.target.value) })} />
        </label>
        <input value={form.delivery} onChange={(e) => setForm({ ...form, delivery: e.target.value })} placeholder="Cómo se entrega (obligatorio en servicios)" />
        <input value={form.markets} onChange={(e) => setForm({ ...form, markets: e.target.value })} placeholder="Mercados (WORLDWIDE por defecto)" />
        <input value={form.languages} onChange={(e) => setForm({ ...form, languages: e.target.value })} placeholder="Idiomas (en, es)" />
        <input value={form.reason} onChange={(e) => setForm({ ...form, reason: e.target.value })} placeholder="Motivo del cambio (opcional)" />
        <button disabled={save.isPending}>Guardar cambios</button>
        {save.isError && <p className="error">{save.error.message}</p>}
      </form>
      <details>
        <summary>Misiones asociadas</summary>
        <p className="hint">Demanda: misiones de discovery con evidencia web. Construcción: Engineering, Creative o Marketing.</p>
        <table className="data-table">
          <thead>
            <tr>
              <th>Misión</th>
              <th>Demanda</th>
              <th>Construcción</th>
            </tr>
          </thead>
          <tbody>
            {missionOptions.map((id) => (
              <tr key={id}>
                <td>{id}</td>
                <td>
                  <input type="checkbox" checked={validatedBy.includes(id)} onChange={() => setValidatedBy(toggle(validatedBy, id))} />
                </td>
                <td>
                  <input type="checkbox" checked={builtBy.includes(id)} onChange={() => setBuiltBy(toggle(builtBy, id))} />
                </td>
              </tr>
            ))}
          </tbody>
        </table>
        <button disabled={link.isPending} onClick={() => link.mutate()}>
          Guardar misiones
        </button>
        {link.isError && <p className="error">{link.error.message}</p>}
      </details>
      <details>
        <summary>Historial ({view.history.length})</summary>
        <ul>
          {view.history.map((c, i) => (
            <li key={i}>
              {c.at.slice(0, 16).replace('T', ' ')} — {c.actor}: {c.field} {c.from ?? '∅'} → {c.to ?? '∅'} {c.reason && `(${c.reason})`}
            </li>
          ))}
        </ul>
      </details>
    </div>
  )
}

function NewProductForm() {
  const refresh = useRefresh()
  const [name, setName] = useState('')
  const [description, setDescription] = useState('')
  const [kind, setKind] = useState('SOFTWARE')
  const mutation = useMutation({
    mutationFn: () => api.createProduct({ name, description, kind }),
    onSuccess: () => {
      setName('')
      setDescription('')
      refresh()
    },
  })
  return (
    <form className="decision-form" onSubmit={(e) => { e.preventDefault(); mutation.mutate() }}>
      <h3>Nuevo producto o servicio</h3>
      <input placeholder="Nombre" value={name} onChange={(e) => setName(e.target.value)} />
      <textarea placeholder="Descripción" value={description} onChange={(e) => setDescription(e.target.value)} />
      <select value={kind} onChange={(e) => setKind(e.target.value)}>
        <option value="SOFTWARE">Software</option>
        <option value="SERVICE">Servicio</option>
      </select>
      <button disabled={!name.trim() || mutation.isPending}>Crear como idea</button>
      {mutation.isError && <p className="error">{mutation.error.message}</p>}
    </form>
  )
}

export default function ProductsPage() {
  const { data, isLoading, error } = useQuery({ queryKey: ['products'], queryFn: api.products })

  if (isLoading) return <p>Cargando catálogo…</p>
  if (error || !data) return <p className="error">No se pudo cargar el catálogo.</p>

  return (
    <section>
      <h2>Productos y servicios</h2>
      <p className="hint">
        Se buscan clientes a nivel mundial para lo que está listo para vender. Contactar y vender siguen requiriendo tu
        aprobación.
      </p>
      {data.length === 0 && <p className="hint">El catálogo está vacío.</p>}
      {ORDER.map((status) => {
        const views = data.filter((v) => v.product.status === status)
        return views.length === 0 ? null : (
          <div key={status}>
            <h3>
              {STATUS_LABELS[status]} ({views.length})
            </h3>
            {views.map((v) => (
              <ProductEditor key={`${v.product.id}-${v.product.updatedAt}`} view={v} />
            ))}
          </div>
        )
      })}
      <NewProductForm />
    </section>
  )
}
