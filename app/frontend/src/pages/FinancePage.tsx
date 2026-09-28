import { useState } from 'react'
import { Link } from 'react-router-dom'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { api } from '../api/client'
import type { FinanceEntry } from '../api/types'

// Finanzas (spec 2026-09-27): costos frente a ganancias. Solo el fundador registra; nada se edita ni se borra,
// un error se corrige con un asiento de corrección.

const TYPE_LABELS: Record<FinanceEntry['type'], string> = {
  SALE_REVENUE: 'Venta',
  SALE_COST: 'Costo de venta',
  EXPENSE: 'Gasto',
  CORRECTION: 'Corrección',
}

const usd = (n: number) => `US$${n.toFixed(2)}`

function useFinanceInvalidation() {
  const queryClient = useQueryClient()
  return () => {
    void queryClient.invalidateQueries({ queryKey: ['finance'] })
    void queryClient.invalidateQueries({ queryKey: ['finance-customers'] })
  }
}

function MissionSelect({ value, onChange }: { value: string; onChange: (v: string) => void }) {
  const missions = useQuery({ queryKey: ['missions'], queryFn: api.missions })
  return (
    <select value={value} onChange={(e) => onChange(e.target.value)}>
      <option value="">Sin misión (movimiento general)</option>
      {(missions.data ?? []).map((m) => (
        <option key={m.missionId} value={m.missionId}>
          {m.missionId}
        </option>
      ))}
    </select>
  )
}

function EvidenceFields(props: {
  description: string
  link: string
  onDescription: (v: string) => void
  onLink: (v: string) => void
}) {
  return (
    <>
      <input
        placeholder="Evidencia (obligatoria): p. ej. factura #123, pago por Nequi"
        value={props.description}
        onChange={(e) => props.onDescription(e.target.value)}
      />
      <input placeholder="Link al comprobante (opcional)" value={props.link} onChange={(e) => props.onLink(e.target.value)} />
    </>
  )
}

function CustomerForm() {
  const refresh = useFinanceInvalidation()
  const [name, setName] = useState('')
  const [contact, setContact] = useState('')
  const [missionId, setMissionId] = useState('')
  const [evidence, setEvidence] = useState('')
  const [link, setLink] = useState('')
  const mutation = useMutation({
    mutationFn: () =>
      api.createFinanceCustomer({
        name,
        contact: contact || undefined,
        missionId: missionId || undefined,
        evidenceDescription: evidence,
        evidenceLink: link || undefined,
      }),
    onSuccess: () => {
      setName('')
      setContact('')
      setEvidence('')
      setLink('')
      refresh()
    },
  })
  return (
    <form className="decision-form" onSubmit={(e) => { e.preventDefault(); mutation.mutate() }}>
      <h3>Registrar cliente</h3>
      <p className="hint">Cliente = empresa o persona que compra. Un prospecto de los agentes no es cliente.</p>
      <input placeholder="Nombre" value={name} onChange={(e) => setName(e.target.value)} />
      <input placeholder="Contacto (opcional)" value={contact} onChange={(e) => setContact(e.target.value)} />
      <MissionSelect value={missionId} onChange={setMissionId} />
      <EvidenceFields description={evidence} link={link} onDescription={setEvidence} onLink={setLink} />
      <button disabled={!name.trim() || !evidence.trim() || mutation.isPending}>Registrar cliente</button>
      {mutation.isError && <p className="error">{mutation.error.message}</p>}
    </form>
  )
}

function SaleForm() {
  const refresh = useFinanceInvalidation()
  const customers = useQuery({ queryKey: ['finance-customers'], queryFn: api.financeCustomers })
  const [customerId, setCustomerId] = useState('')
  const [description, setDescription] = useState('')
  const [revenue, setRevenue] = useState(0)
  const [cost, setCost] = useState(0)
  const [missionId, setMissionId] = useState('')
  const [environment, setEnvironment] = useState('PRODUCTION')
  const [evidence, setEvidence] = useState('')
  const [link, setLink] = useState('')
  const mutation = useMutation({
    mutationFn: () =>
      api.createSale({
        customerId,
        description,
        revenueUsd: revenue,
        costUsd: cost,
        missionId: missionId || undefined,
        environment,
        evidenceDescription: evidence,
        evidenceLink: link || undefined,
      }),
    onSuccess: () => {
      setDescription('')
      setRevenue(0)
      setCost(0)
      setEvidence('')
      setLink('')
      refresh()
    },
  })
  return (
    <form className="decision-form" onSubmit={(e) => { e.preventDefault(); mutation.mutate() }}>
      <h3>Registrar venta</h3>
      <select value={customerId} onChange={(e) => setCustomerId(e.target.value)}>
        <option value="">Elige un cliente</option>
        {(customers.data ?? []).map((c) => (
          <option key={c.id} value={c.id}>
            {c.name}
          </option>
        ))}
      </select>
      <input placeholder="Qué se vendió" value={description} onChange={(e) => setDescription(e.target.value)} />
      <label>
        Ingreso (US$) <input type="number" step="0.01" min="0" value={revenue} onChange={(e) => setRevenue(Number(e.target.value))} />
      </label>
      <label>
        Costo de la venta (US$) <input type="number" step="0.01" min="0" value={cost} onChange={(e) => setCost(Number(e.target.value))} />
      </label>
      <MissionSelect value={missionId} onChange={setMissionId} />
      {!missionId && (
        <select value={environment} onChange={(e) => setEnvironment(e.target.value)}>
          <option value="PRODUCTION">Real (suma)</option>
          <option value="TEST">Prueba (no suma)</option>
        </select>
      )}
      <EvidenceFields description={evidence} link={link} onDescription={setEvidence} onLink={setLink} />
      <button disabled={!customerId || !description.trim() || !evidence.trim() || mutation.isPending}>Registrar venta</button>
      {mutation.isError && <p className="error">{mutation.error.message}</p>}
    </form>
  )
}

function ExpenseForm() {
  const refresh = useFinanceInvalidation()
  const [description, setDescription] = useState('')
  const [amount, setAmount] = useState(0)
  const [missionId, setMissionId] = useState('')
  const [environment, setEnvironment] = useState('PRODUCTION')
  const [evidence, setEvidence] = useState('')
  const [link, setLink] = useState('')
  const mutation = useMutation({
    mutationFn: () =>
      api.createExpense({
        description,
        amountUsd: amount,
        missionId: missionId || undefined,
        environment,
        evidenceDescription: evidence,
        evidenceLink: link || undefined,
      }),
    onSuccess: () => {
      setDescription('')
      setAmount(0)
      setEvidence('')
      setLink('')
      refresh()
    },
  })
  return (
    <form className="decision-form" onSubmit={(e) => { e.preventDefault(); mutation.mutate() }}>
      <h3>Registrar gasto</h3>
      <input placeholder="Qué se pagó (p. ej. dominio forjai.com)" value={description} onChange={(e) => setDescription(e.target.value)} />
      <label>
        Monto (US$) <input type="number" step="0.01" min="0" value={amount} onChange={(e) => setAmount(Number(e.target.value))} />
      </label>
      <MissionSelect value={missionId} onChange={setMissionId} />
      {!missionId && (
        <select value={environment} onChange={(e) => setEnvironment(e.target.value)}>
          <option value="PRODUCTION">Real (suma)</option>
          <option value="TEST">Prueba (no suma)</option>
        </select>
      )}
      <EvidenceFields description={evidence} link={link} onDescription={setEvidence} onLink={setLink} />
      <button disabled={!description.trim() || amount <= 0 || !evidence.trim() || mutation.isPending}>Registrar gasto</button>
      {mutation.isError && <p className="error">{mutation.error.message}</p>}
    </form>
  )
}

/** Asiento de corrección: el movimiento original nunca se toca. "Anular" precarga el monto inverso. */
function CorrectionForm({ entry, entries, onDone }: { entry: FinanceEntry; entries: FinanceEntry[]; onDone: () => void }) {
  const refresh = useFinanceInvalidation()
  const isExpense = entry.type === 'EXPENSE'
  const revenueLine = entries.find((e) => e.movementId === entry.movementId && e.type === 'SALE_REVENUE')
  const costLine = entries.find((e) => e.movementId === entry.movementId && (e.type === 'SALE_COST' || e.type === 'EXPENSE'))
  const [revenueAdj, setRevenueAdj] = useState(0)
  const [costAdj, setCostAdj] = useState(0)
  const [reason, setReason] = useState('')
  const [evidence, setEvidence] = useState('')
  const mutation = useMutation({
    mutationFn: () =>
      api.createCorrection({
        targetId: entry.movementId,
        revenueAdjustmentUsd: isExpense ? 0 : revenueAdj,
        costAdjustmentUsd: costAdj,
        reason,
        evidenceDescription: evidence,
      }),
    onSuccess: () => {
      refresh()
      onDone()
    },
  })
  const cancelAll = () => {
    setRevenueAdj(revenueLine ? -revenueLine.amountUsd : 0)
    setCostAdj(costLine ? Math.abs(costLine.amountUsd) * -1 : 0)
    if (!reason) setReason('Anulado: registro equivocado o duplicado')
  }
  return (
    <form className="decision-form" onSubmit={(e) => { e.preventDefault(); mutation.mutate() }}>
      <p className="hint">Corrige {entry.movementId}: el original queda intacto y este ajuste se suma al libro.</p>
      <button type="button" onClick={cancelAll}>Anular (monto inverso)</button>
      {!isExpense && (
        <label>
          Ajuste de ingreso (US$, con signo)
          <input type="number" step="0.01" value={revenueAdj} onChange={(e) => setRevenueAdj(Number(e.target.value))} />
        </label>
      )}
      <label>
        Ajuste de costo (US$, con signo)
        <input type="number" step="0.01" value={costAdj} onChange={(e) => setCostAdj(Number(e.target.value))} />
      </label>
      <input placeholder="Motivo (obligatorio)" value={reason} onChange={(e) => setReason(e.target.value)} />
      <input placeholder="Evidencia (obligatoria)" value={evidence} onChange={(e) => setEvidence(e.target.value)} />
      <button disabled={!reason.trim() || !evidence.trim() || mutation.isPending}>Registrar corrección</button>
      <button type="button" onClick={onDone}>Cancelar</button>
      {mutation.isError && <p className="error">{mutation.error.message}</p>}
    </form>
  )
}

export default function FinancePage() {
  const [missionFilter, setMissionFilter] = useState('')
  const [correcting, setCorrecting] = useState<FinanceEntry | null>(null)
  const { data, isLoading, error } = useQuery({
    queryKey: ['finance', missionFilter],
    queryFn: () => api.finance(missionFilter || undefined),
  })

  if (isLoading) return <p>Cargando finanzas…</p>
  if (error || !data) return <p className="error">No se pudieron cargar las finanzas.</p>

  return (
    <section>
      <h2>Finanzas: costos frente a ganancias</h2>
      <MissionSelect value={missionFilter} onChange={setMissionFilter} />
      <div className="dashboard-cards">
        <div className="card">
          <div className="card-title">Costos</div>
          <div className="card-value">{usd(data.costsUsd)}</div>
        </div>
        <div className="card">
          <div className="card-title">Ganancias</div>
          <div className="card-value" style={{ color: data.profitUsd >= 0 ? 'var(--ok, #1a7f37)' : 'var(--danger, #cf222e)' }}>
            {usd(data.profitUsd)}
          </div>
        </div>
        <div className="card">
          <div className="card-title">Ingresos</div>
          <div className="card-value">{usd(data.revenueUsd)}</div>
        </div>
        <div className="card">
          <div className="card-title">Balance</div>
          <div className="card-value">{usd(data.balanceUsd)}</div>
          <p className="hint">capital semilla {usd(data.seedCapitalUsd)}</p>
        </div>
      </div>

      <h3>Libro de movimientos</h3>
      {data.entries.length === 0 ? (
        <p className="hint">Todavía no hay movimientos registrados.</p>
      ) : (
        <table className="data-table">
          <thead>
            <tr>
              <th>Fecha</th>
              <th>Tipo</th>
              <th>Descripción</th>
              <th>Misión</th>
              <th>Monto</th>
              <th>Balance</th>
              <th></th>
            </tr>
          </thead>
          <tbody>
            {data.entries.map((e, i) => (
              <tr key={`${e.movementId}-${e.type}-${i}`}>
                <td>{e.occurredAt.slice(0, 10)}</td>
                <td>
                  {TYPE_LABELS[e.type]} {e.environment === 'TEST' && '🧪'}
                </td>
                <td>
                  {e.description}
                  {e.targetId && <span className="hint"> (corrige {e.targetId})</span>}
                </td>
                <td>{e.missionId ? <Link to={`/missions/${e.missionId}`}>{e.missionId}</Link> : '—'}</td>
                <td>{usd(e.amountUsd)}</td>
                <td>{e.runningBalanceUsd === null ? '—' : usd(e.runningBalanceUsd)}</td>
                <td>
                  {(e.type === 'SALE_REVENUE' || e.type === 'EXPENSE') && (
                    <button type="button" onClick={() => setCorrecting(e)}>
                      Corregir
                    </button>
                  )}
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      )}
      {correcting && <CorrectionForm entry={correcting} entries={data.entries} onDone={() => setCorrecting(null)} />}

      <CustomerForm />
      <SaleForm />
      <ExpenseForm />
    </section>
  )
}
