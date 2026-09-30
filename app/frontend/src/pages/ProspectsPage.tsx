import { useState } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { api } from '../api/client'

// Búsqueda de prospectos (spec 2026-09-30 §3): prospectos por producto, estrategias con rendimiento y corridas.
// Contactar es decisión del fundador (subproyecto 6); acá solo se ven.
const OUTREACH_LABELS: Record<string, string> = {
  DRAFTED: 'borrador',
  CONTACT_IN_PROGRESS: 'enviando',
  CONTACTED: 'contactado',
  INTERESTED: 'interesado',
  NOT_INTERESTED: 'no interesado',
  OPTED_OUT: 'baja',
  CONVERTED: 'cliente',
}

function DraftsSection() {
  const queryClient = useQueryClient()
  const [feedback, setFeedback] = useState<string | null>(null)
  const [editing, setEditing] = useState<Record<string, { subject: string; body: string }>>({})
  const drafts = useQuery({ queryKey: ['drafts'], queryFn: () => api.outreachDrafts('PENDING_APPROVAL'), refetchInterval: 30_000 })
  const done = (message: string) => {
    setFeedback(message)
    for (const key of ['drafts', 'prospects', 'autonomy']) void queryClient.invalidateQueries({ queryKey: [key] })
  }
  const act = useMutation({
    mutationFn: async ({ id, action }: { id: string; action: 'approve' | 'discard' | 'save' }) => {
      if (action === 'approve') return api.approveDraft(id)
      if (action === 'discard') return api.discardDraft(id)
      const e = editing[id]
      return api.editDraft(id, e.subject, e.body)
    },
    onSuccess: (d) =>
      done(d.status === 'SENT' ? `Enviado a ${d.to}.` : d.status === 'APPROVED' ? `Aprobado; pendiente: ${d.error}` : 'Listo.'),
    onError: (e: Error) => setFeedback(e.message),
  })
  const all = useMutation({
    mutationFn: api.approveAllDrafts,
    onSuccess: (list) => done(`${list.filter((d) => d.status === 'SENT').length} enviados de ${list.length}.`),
    onError: (e: Error) => setFeedback(e.message),
  })
  const list = drafts.data ?? []
  return (
    <>
      <h2>Correos por aprobar ({list.length})</h2>
      <p className="hint">Nada se envía sin tu aprobación. Las respuestas llegan a tu correo.</p>
      {feedback && <p className={act.isError || all.isError ? 'error' : 'feedback'}>{feedback}</p>}
      {list.length > 0 && (
        <button disabled={all.isPending} onClick={() => all.mutate()}>
          Aprobar todos
        </button>
      )}
      {list.map((d) => {
        const e = editing[d.id]
        return (
          <div key={d.id} className="card">
            <strong>{d.prospectName}</strong> &lt;{d.to}&gt;
            {e ? (
              <>
                <input value={e.subject} onChange={(x) => setEditing({ ...editing, [d.id]: { ...e, subject: x.target.value } })} />
                <textarea rows={8} value={e.body} onChange={(x) => setEditing({ ...editing, [d.id]: { ...e, body: x.target.value } })} />
                <button disabled={act.isPending} onClick={() => act.mutate({ id: d.id, action: 'save' })}>Guardar</button>
              </>
            ) : (
              <>
                <p><strong>{d.subject}</strong></p>
                <pre className="draft-body">{d.body}</pre>
                <button onClick={() => setEditing({ ...editing, [d.id]: { subject: d.subject, body: d.body } })}>Editar</button>
              </>
            )}{' '}
            <button disabled={act.isPending} onClick={() => act.mutate({ id: d.id, action: 'approve' })}>Aprobar y enviar</button>{' '}
            <button className="danger" disabled={act.isPending} onClick={() => act.mutate({ id: d.id, action: 'discard' })}>
              Descartar
            </button>
          </div>
        )
      })}
    </>
  )
}

export default function ProspectsPage() {
  const queryClient = useQueryClient()
  const [feedback, setFeedback] = useState<string | null>(null)
  const prospects = useQuery({ queryKey: ['prospects'], queryFn: api.prospects, refetchInterval: 30_000 })
  const runs = useQuery({ queryKey: ['prospectingRuns'], queryFn: api.prospectingRuns, refetchInterval: 30_000 })
  const strategies = useQuery({ queryKey: ['prospectingStrategies'], queryFn: api.prospectingStrategies, refetchInterval: 30_000 })
  const refresh = () => {
    for (const key of ['prospects', 'prospectingRuns', 'prospectingStrategies', 'autonomy', 'drafts']) {
      void queryClient.invalidateQueries({ queryKey: [key] })
    }
  }
  const respond = useMutation({
    mutationFn: ({ id, r }: { id: string; r: 'INTERESTED' | 'NOT_INTERESTED' | 'OPTED_OUT' }) => api.prospectResponse(id, r),
    onSuccess: () => { setFeedback('Respuesta anotada.'); refresh() },
    onError: (e: Error) => setFeedback(e.message),
  })
  const convert = useMutation({
    mutationFn: (id: string) => api.convertProspect(id),
    onSuccess: (r) => { setFeedback(`Ahora es cliente (${r.customerId}): regístrale ventas en Finanzas.`); refresh() },
    onError: (e: Error) => setFeedback(e.message),
  })
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
      <DraftsSection />
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
                {' · '}
                <span className="hint">{OUTREACH_LABELS[p.outreachStatus ?? ''] ?? 'sin contactar'}</span>
                {p.outreachStatus === 'CONTACTED' && (
                  <>
                    {' '}
                    <button onClick={() => respond.mutate({ id: p.id, r: 'INTERESTED' })}>Interesado</button>{' '}
                    <button onClick={() => respond.mutate({ id: p.id, r: 'NOT_INTERESTED' })}>No interesado</button>{' '}
                    <button onClick={() => respond.mutate({ id: p.id, r: 'OPTED_OUT' })}>Pidió baja</button>
                  </>
                )}
                {(p.outreachStatus === 'CONTACTED' || p.outreachStatus === 'INTERESTED') && (
                  <>
                    {' '}
                    <button onClick={() => convert.mutate(p.id)}>Convertir en cliente</button>
                  </>
                )}
                {!p.contactEmail && p.contactFormUrl && <span className="hint"> · contactar a mano</span>}
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
