import { Link } from 'react-router-dom'
import { useQuery } from '@tanstack/react-query'
import { api } from '../api/client'
import { statusDot } from '../statusColor'

const TERMINAL_STATUSES = new Set(['COMPLETED', 'FAILED', 'CANCELLED'])

export default function DashboardPage() {
  const missionsQuery = useQuery({ queryKey: ['missions'], queryFn: api.missions, refetchInterval: 10_000 })
  const agentsQuery = useQuery({ queryKey: ['agentsStatus'], queryFn: api.agentsStatus, refetchInterval: 5_000 })
  const activityQuery = useQuery({ queryKey: ['activity'], queryFn: () => api.activity(5), refetchInterval: 5_000 })

  const missions = missionsQuery.data ?? []
  const agents = agentsQuery.data ?? []

  const activeMissions = missions.filter((m) => !TERMINAL_STATUSES.has(m.status))
  const needingAttention = missions.filter((m) => m.status === 'AWAITING_INVESTOR' || m.status === 'FAILED')

  return (
    <div>
      <h1>Dashboard</h1>
      <div className="dashboard-cards">
        <div className="card">
          <div className="card-title">Misiones activas</div>
          <div className="card-value">{activeMissions.length}</div>
        </div>
        <div className="card">
          <div className="card-title">Necesitan tu atención</div>
          <div className="card-value">{needingAttention.length}</div>
          <Link to="/missions">Ver misiones →</Link>
        </div>
        <div className="card">
          <div className="card-title">Agentes</div>
          <div className="card-value">
            {agents.map((a) => (
              <span key={a.agentId} className="agent-chip">
                {statusDot(a.status)} {a.name}
              </span>
            ))}
          </div>
          <Link to="/agents">Ver agentes →</Link>
        </div>
      </div>

      <h2>Actividad reciente</h2>
      <ul className="activity-list">
        {(activityQuery.data ?? []).map((item, i) => (
          <li key={i}>
            <span className="activity-desc">{item.description}</span>
            {item.missionId && <span className="activity-mission">{item.missionId}</span>}
          </li>
        ))}
      </ul>
      <Link to="/activity">Ver toda la actividad →</Link>
    </div>
  )
}
