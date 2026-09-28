import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { Link } from 'react-router-dom'
import { api } from '../api/client'
import type { DependencyInfo } from '../api/types'

// Subproyecto 2 (2026-09-28): dependencias que pidió Engineering. Aprobar o rechazar es decisión del fundador (🔴);
// aprobar promueve el paquete (ya descargado y analizado) a la caché que usa el sandbox.

const STATUS_LABELS: Record<DependencyInfo['status'], string> = {
  PENDING_APPROVAL: '⏳ Esperando tu decisión',
  APPROVED: '✅ Aprobada',
  REJECTED: '⛔ Rechazada',
}

function DependencyRow({ dependency }: { dependency: DependencyInfo }) {
  const queryClient = useQueryClient()
  const refresh = () => void queryClient.invalidateQueries({ queryKey: ['dependencies'] })
  const approve = useMutation({ mutationFn: () => api.approveDependency(dependency.id), onSuccess: refresh })
  const reject = useMutation({ mutationFn: () => api.rejectDependency(dependency.id), onSuccess: refresh })
  const pending = dependency.status === 'PENDING_APPROVAL'
  const error = approve.error ?? reject.error

  return (
    <tr>
      <td>
        <code>{dependency.id}</code>
      </td>
      <td>{dependency.license ?? '—'}</td>
      <td>
        {dependency.requestedByAgent ?? '—'}
        {dependency.missionId && (
          <>
            {' '}
            en <Link to={`/missions/${dependency.missionId}`}>{dependency.missionId}</Link>
          </>
        )}
      </td>
      <td>{(dependency.reasons ?? []).join('; ') || '—'}</td>
      <td>
        {STATUS_LABELS[dependency.status]}
        {!pending && dependency.approvedBy && <span className="hint"> ({dependency.approvedBy})</span>}
      </td>
      <td>
        {pending && (
          <>
            <button disabled={approve.isPending || reject.isPending} onClick={() => approve.mutate()}>
              Aprobar
            </button>{' '}
            <button disabled={approve.isPending || reject.isPending} onClick={() => reject.mutate()}>
              Rechazar
            </button>
          </>
        )}
        {error && <p className="error">{error.message}</p>}
      </td>
    </tr>
  )
}

export default function DependenciesPage() {
  const { data, isLoading, error } = useQuery({ queryKey: ['dependencies'], queryFn: api.dependencies })

  if (isLoading) return <p>Cargando dependencias…</p>
  if (error || !data) return <p className="error">No se pudieron cargar las dependencias.</p>

  const pending = data.filter((d) => d.status === 'PENDING_APPROVAL')
  const decided = data.filter((d) => d.status !== 'PENDING_APPROVAL')

  const table = (rows: DependencyInfo[]) => (
    <table className="data-table">
      <thead>
        <tr>
          <th>Paquete</th>
          <th>Licencia</th>
          <th>Pedida por</th>
          <th>Motivo</th>
          <th>Estado</th>
          <th></th>
        </tr>
      </thead>
      <tbody>
        {rows.map((d) => (
          <DependencyRow key={d.id} dependency={d} />
        ))}
      </tbody>
    </table>
  )

  return (
    <section>
      <h2>Dependencias de Engineering</h2>
      <p className="hint">
        Paquetes que pidieron los agentes y no pasó la política automática (licencia, vulnerabilidades). Mientras haya
        alguno pendiente, el sandbox de esa misión no corre.
      </p>
      <h3>Esperando tu decisión ({pending.length})</h3>
      {pending.length === 0 ? <p className="hint">No hay dependencias pendientes.</p> : table(pending)}
      <h3>Historial</h3>
      {decided.length === 0 ? <p className="hint">Todavía no hay decisiones.</p> : table(decided)}
    </section>
  )
}
