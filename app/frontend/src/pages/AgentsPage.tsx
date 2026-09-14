import { useQuery } from '@tanstack/react-query'
import { api } from '../api/client'
import { statusDot } from '../statusColor'

export default function AgentsPage() {
  const { data, isLoading, error } = useQuery({
    queryKey: ['agentsStatus'],
    queryFn: api.agentsStatus,
    refetchInterval: 5_000,
  })

  if (isLoading) return <p>Cargando agentes...</p>
  if (error) return <p className="error">No se pudo cargar el estado de los agentes.</p>

  return (
    <div>
      <h1>Agents</h1>
      <table className="data-table">
        <thead>
          <tr>
            <th>Agente</th>
            <th>Estado</th>
            <th>Misión</th>
            <th>Acción</th>
            <th>Actualizado</th>
          </tr>
        </thead>
        <tbody>
          {data?.map((agent) => (
            <tr key={agent.agentId}>
              <td>{agent.agentId.toUpperCase()}</td>
              <td>
                {statusDot(agent.status)} {agent.status}
              </td>
              <td>{agent.missionId ?? '—'}</td>
              <td>{agent.action ?? '—'}</td>
              <td>{agent.updatedAt ? new Date(agent.updatedAt).toLocaleString() : '—'}</td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  )
}
