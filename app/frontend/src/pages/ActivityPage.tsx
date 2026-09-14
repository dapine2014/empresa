import { useQuery } from '@tanstack/react-query'
import { api } from '../api/client'

const TYPE_LABEL: Record<string, string> = {
  TASK: '⚙️ Tarea',
  MISSION: '🧭 Misión',
  EVIDENCE: '📄 Evidencia',
  DECISION: '✅ Decisión',
}

export default function ActivityPage() {
  const { data, isLoading, error } = useQuery({
    queryKey: ['activity'],
    queryFn: () => api.activity(50),
    refetchInterval: 5_000,
  })

  if (isLoading) return <p>Cargando actividad...</p>
  if (error) return <p className="error">No se pudo cargar la actividad reciente.</p>

  return (
    <div>
      <h1>Activity</h1>
      <p className="hint">
        Línea de tiempo derivada de los mismos datos que ya se escriben en Neo4j (misiones, tareas, evidencia,
        decisiones) — no un stream literal de Kafka.
      </p>
      <ul className="activity-list">
        {data?.map((item, index) => (
          <li key={index}>
            <span className="activity-type">{TYPE_LABEL[item.type] ?? item.type}</span>
            <span className="activity-desc">{item.description}</span>
            {item.missionId && <span className="activity-mission">{item.missionId}</span>}
            <span className="activity-time">{new Date(item.timestamp).toLocaleString()}</span>
          </li>
        ))}
      </ul>
    </div>
  )
}
