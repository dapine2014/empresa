import { useState } from 'react'
import { Link } from 'react-router-dom'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { api } from '../api/client'
import type { AutonomyCommand } from '../api/types'
import { ORCHESTRATOR_LABELS } from '../orchestratorLabels'

// Modo automático (spec 2026-09-29): interruptores + "qué están haciendo". Sin estado optimista: el interruptor
// muestra lo que devuelve el backend; si el cambio falla, queda como estaba y se ve el motivo.
export default function AutonomyPanel() {
  const queryClient = useQueryClient()
  const [error, setError] = useState<string | null>(null)
  const autonomy = useQuery({ queryKey: ['autonomy'], queryFn: api.autonomy, refetchInterval: 15_000 })
  const orchestrator = useQuery({ queryKey: ['orchestrator'], queryFn: api.orchestrator, refetchInterval: 15_000 })
  const agents = useQuery({ queryKey: ['agentsStatus'], queryFn: api.agentsStatus, refetchInterval: 5_000 })
  const mutation = useMutation({
    mutationFn: (command: AutonomyCommand) => api.setAutonomy(command),
    onSuccess: (view) => {
      setError(null)
      queryClient.setQueryData(['autonomy'], view)
      void queryClient.invalidateQueries({ queryKey: ['orchestrator'] })
    },
    onError: (e: Error) => setError(e.message),
  })

  const view = autonomy.data
  if (!view) {
    return <div className="card autonomy">{autonomy.isError ? 'Modo automático no disponible.' : 'Cargando…'}</div>
  }
  const available = [view.products, view.clients].filter((f) => f.available)
  const allOn = available.every((f) => f.enabled)
  const someOn = available.some((f) => f.enabled)
  const run = orchestrator.data?.run
  const active = run && ['CHOOSING', 'DISCOVERING', 'PROPOSING', 'BUILDING'].includes(run.status)
  const missionId = run?.buildMissionId ?? run?.discoveryMissionId
  const working = (agents.data ?? []).filter((a) => a.status === 'WORKING')

  return (
    <div className="card autonomy">
      <h2>Modo automático</h2>
      <div className="autonomy-switches">
        <label>
          <input
            type="checkbox"
            checked={allOn}
            ref={(el) => {
              if (el) el.indeterminate = someOn && !allOn
            }}
            disabled={mutation.isPending}
            onChange={() => mutation.mutate({ products: !allOn })}
          />{' '}
          <strong>Todo en automático</strong> {someOn && !allOn && <span className="hint">(parcial)</span>}
        </label>
        <label>
          <input
            type="checkbox"
            checked={view.products.enabled}
            disabled={mutation.isPending}
            onChange={() => mutation.mutate({ products: !view.products.enabled })}
          />{' '}
          Crear productos y servicios
        </label>
        <label className="hint">
          <input type="checkbox" checked={false} disabled /> Buscar clientes (próximamente)
        </label>
      </div>
      {error && <p className="error">{error}</p>}
      {!view.products.enabled && view.products.pauseReason && <p className="error">{view.products.pauseReason}</p>}
      {view.products.enabled && !active && (
        <p className="hint">Retoma en el próximo chequeo (cada 15 minutos o al terminar una misión).</p>
      )}
      <p className="hint">Contactar clientes o vender sigue siendo decisión tuya.</p>

      <h3>Qué están haciendo</h3>
      <div className="autonomy-front">
        <strong>Productos y servicios:</strong>{' '}
        {orchestrator.isError ? (
          'no disponible'
        ) : active && run ? (
          <>
            {ORCHESTRATOR_LABELS[run.status]}
            {missionId && (
              <>
                {' · '}
                <Link to={`/missions/${missionId}`}>{missionId}</Link>
              </>
            )}
            <ul className="autonomy-steps">
              {(orchestrator.data?.steps ?? []).slice(-5).map((s, i) => (
                <li key={i}>
                  {new Date(s.at).toLocaleTimeString()} — {s.detail}
                </li>
              ))}
            </ul>
          </>
        ) : !view.products.enabled ? (
          'pausado'
        ) : (
          'sin ciclo en curso (arranca solo cuando no hay productos listos para vender ni en construcción)'
        )}
      </div>
      <div className="autonomy-front">
        <strong>Clientes:</strong> próximamente
      </div>
      <div className="autonomy-front">
        <strong>Agentes trabajando ahora:</strong>{' '}
        {working.length === 0
          ? 'ninguno'
          : working.map((a) => `${a.name} (${a.action ?? '—'}${a.missionId ? ` · ${a.missionId}` : ''})`).join(', ')}
      </div>
      <div className="autonomy-front">
        <strong>Esperando tu decisión:</strong>{' '}
        <Link to="/missions">{view.waiting.orchestratorMissions} misiones del orquestador</Link> ·{' '}
        <Link to="/dependencias">{view.waiting.pendingDependencies} dependencias pendientes</Link>
      </div>
    </div>
  )
}
