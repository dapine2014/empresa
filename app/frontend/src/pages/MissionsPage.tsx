import { Link } from 'react-router-dom'
import { useQuery } from '@tanstack/react-query'
import { api } from '../api/client'
import { statusDot } from '../statusColor'

export default function MissionsPage() {
  const { data, isLoading, error } = useQuery({
    queryKey: ['missions'],
    queryFn: api.missions,
    refetchInterval: 10_000,
  })

  if (isLoading) return <p>Cargando misiones...</p>
  if (error) return <p className="error">No se pudieron cargar las misiones.</p>

  return (
    <div>
      <h1>Missions</h1>
      <table className="data-table">
        <thead>
          <tr>
            <th>Misión</th>
            <th>Estado</th>
            <th>Entorno</th>
            <th>Progreso</th>
            <th>Paso actual</th>
            <th>Actualizada</th>
          </tr>
        </thead>
        <tbody>
          {data?.map((mission) => (
            <tr key={mission.missionId}>
              <td>
                <Link to={`/missions/${mission.missionId}`}>{mission.missionId}</Link>
              </td>
              <td>
                {statusDot(mission.status)} {mission.status}
              </td>
              <td>{mission.environment === 'PRODUCTION' ? '🏢 PRODUCTION' : '🧪 TEST'}</td>
              <td>{mission.progress}%</td>
              <td>{mission.currentStep}</td>
              <td>{new Date(mission.updatedAt).toLocaleString()}</td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  )
}
