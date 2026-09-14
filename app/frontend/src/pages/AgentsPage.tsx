import { useQuery } from '@tanstack/react-query'
import { api } from '../api/client'
import { statusDot } from '../statusColor'
import { humanizeAction } from '../humanize'

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
      <div className="dashboard-cards">
        {data?.map((agent) => (
          <div className="card" key={agent.agentId}>
            <div className="card-value">
              {statusDot(agent.status)} {agent.name}
            </div>
            <div className="card-title">{agent.role}</div>
            <p className="hint">{agent.personality}</p>
            <p className="hint">
              {agent.status === 'IDLE'
                ? 'Inactivo'
                : `Trabajando en: ${humanizeAction(agent.action)}`}
              {agent.missionId ? ` (${agent.missionId})` : ''}
            </p>
          </div>
        ))}
      </div>
    </div>
  )
}
