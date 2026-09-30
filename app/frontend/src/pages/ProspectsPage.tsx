import { useState } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { api } from '../api/client'

// Búsqueda de prospectos (spec 2026-09-30 §3): prospectos por producto, estrategias con rendimiento y corridas.
// Contactar es decisión del fundador (subproyecto 6); acá solo se ven.
export default function ProspectsPage() {
  const queryClient = useQueryClient()
  const [feedback, setFeedback] = useState<string | null>(null)
  const prospects = useQuery({ queryKey: ['prospects'], queryFn: api.prospects, refetchInterval: 30_000 })
  const runs = useQuery({ queryKey: ['prospectingRuns'], queryFn: api.prospectingRuns, refetchInterval: 30_000 })
  const strategies = useQuery({ queryKey: ['prospectingStrategies'], queryFn: api.prospectingStrategies, refetchInterval: 30_000 })
  const refresh = () => {
    for (const key of ['prospects', 'prospectingRuns', 'prospectingStrategies', 'autonomy']) {
      void queryClient.invalidateQueries({ queryKey: [key] })
    }
  }
  const runNow = useMutation({
    mutationFn: api.runProspectingNow,
    onSuccess: (run) => {
      setFeedback(run.status === 'COMPLETED' ? `Corrida lista: ${run.valid} válidos de ${run.found}.` : `Falló: ${run.error}`)
      refresh()
    },
    onError: (e: Error) => setFeedback(e.message),
  })
  const decide = useMutation({
    mutationFn: ({ id, decision }: { id: string; decision: 'approve' | 'reject' }) => api.decideStrategy(id, decision),
    onSuccess: () => {
      setFeedback(null)
      refresh()
    },
    onError: (e: Error) => setFeedback(e.message),
  })

  const byProduct = new Map<string, NonNullable<typeof prospects.data>>()
  for (const p of prospects.data ?? []) {
    const key = p.productName ?? p.productId
    byProduct.set(key, [...(byProduct.get(key) ?? []), p])
  }

  return (
    <div>
      <h1>Prospectos</h1>
      <p className="hint">Contactar prospectos o venderles sigue siendo decisión tuya.</p>
      <button disabled={runNow.isPending} onClick={() => runNow.mutate()}>
        {runNow.isPending ? 'Buscando… (puede tardar varios minutos)' : 'Buscar ahora'}
      </button>
      {feedback && <p className={runNow.isError || decide.isError ? 'error' : 'feedback'}>{feedback}</p>}

      <h2>Por producto</h2>
      {byProduct.size === 0 && <p className="hint">Todavía no hay prospectos.</p>}
      {[...byProduct.entries()].map(([product, list]) => (
        <div key={product} className="card">
          <h3>
            {product} ({list.length})
          </h3>
          <ul>
            {list.map((p) => (
              <li key={p.id}>
                <strong>{p.url ? <a href={p.url} target="_blank" rel="noreferrer">{p.name}</a> : p.name}</strong> —{' '}
                {p.contactEmail ? (
                  <>
                    {p.contactEmail} (<a href={p.contactEmailSource ?? '#'} target="_blank" rel="noreferrer">fuente</a>)
                  </>
                ) : (
                  <a href={p.contactFormUrl ?? '#'} target="_blank" rel="noreferrer">formulario</a>
                )}{' '}
                · {p.fitReason} · <span className="hint">{p.strategyId} · {new Date(p.foundAt).toLocaleString()}</span>
              </li>
            ))}
          </ul>
        </div>
      ))}

      <h2>Estrategias</h2>
      <table>
        <thead>
          <tr>
            <th>Estrategia</th>
            <th>Estado</th>
            <th>Corridas</th>
            <th>Válidos/corrida</th>
            <th />
          </tr>
        </thead>
        <tbody>
          {(strategies.data ?? []).map((s) => (
            <tr key={s.id}>
              <td title={s.description ?? ''}>{s.name}</td>
              <td>{s.status}</td>
              <td>{s.runs}</td>
              <td>{s.validPerRun.toFixed(1)}</td>
              <td>
                {s.status === 'PENDING_APPROVAL' && (
                  <>
                    <button disabled={decide.isPending} onClick={() => decide.mutate({ id: s.id, decision: 'approve' })}>
                      Aprobar
                    </button>{' '}
                    <button
                      className="danger"
                      disabled={decide.isPending}
                      onClick={() => decide.mutate({ id: s.id, decision: 'reject' })}
                    >
                      Rechazar
                    </button>
                  </>
                )}
              </td>
            </tr>
          ))}
        </tbody>
      </table>

      <h2>Últimas corridas</h2>
      <ul>
        {(runs.data ?? []).map((r) => (
          <li key={r.id}>
            {new Date(r.startedAt).toLocaleString()} · {r.productId} · {r.strategyId} ·{' '}
            {r.status === 'COMPLETED' ? `${r.valid} válidos de ${r.found}` : <span className="error">falló: {r.error}</span>}
            {r.rejections.length > 0 && (
              <details>
                <summary>Descartados ({r.rejections.length})</summary>
                <ul>
                  {r.rejections.map((x, i) => (
                    <li key={i}>{x}</li>
                  ))}
                </ul>
              </details>
            )}
          </li>
        ))}
      </ul>
    </div>
  )
}
