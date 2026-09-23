import { useState } from 'react'
import { Link } from 'react-router-dom'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { api } from '../api/client'
import { statusDot } from '../statusColor'

function StartMissionForm() {
  const queryClient = useQueryClient()
  const [instruction, setInstruction] = useState('')
  const [environment, setEnvironment] = useState<'PRODUCTION' | 'TEST'>('PRODUCTION')
  const [hasFinancialCriteria, setHasFinancialCriteria] = useState(false)
  const [targetAmount, setTargetAmount] = useState('')
  const [currency, setCurrency] = useState('USD')
  const [deadline, setDeadline] = useState('')
  const [feedback, setFeedback] = useState<string | null>(null)

  const mutation = useMutation({
    mutationFn: () =>
      api.startMission({
        missionId: `MISSION-${Date.now()}`,
        instruction,
        environment,
        financialCriteria: hasFinancialCriteria
          ? { metric: 'NET_PROFIT', targetAmount: Number(targetAmount), currency, deadline: deadline || null }
          : null,
      }),
    onSuccess: (response) => {
      setFeedback(`Misión ${response.missionId} creada.`)
      setInstruction('')
      setHasFinancialCriteria(false)
      setTargetAmount('')
      setDeadline('')
      queryClient.invalidateQueries({ queryKey: ['missions'] })
    },
    onError: () => setFeedback('No se pudo iniciar la misión.'),
  })

  return (
    <form
      className="decision-form"
      onSubmit={(e) => {
        e.preventDefault()
        setFeedback(null)
        mutation.mutate()
      }}
    >
      <h2>Iniciar misión</h2>
      <label>
        Instrucción
        <textarea required rows={3} value={instruction} onChange={(e) => setInstruction(e.target.value)} />
      </label>
      <label>
        Entorno
        <select value={environment} onChange={(e) => setEnvironment(e.target.value as 'PRODUCTION' | 'TEST')}>
          <option value="PRODUCTION">PRODUCTION</option>
          <option value="TEST">TEST</option>
        </select>
      </label>
      <label>
        <input type="checkbox" checked={hasFinancialCriteria} onChange={(e) => setHasFinancialCriteria(e.target.checked)} />
        Definir objetivo financiero
      </label>
      {hasFinancialCriteria && (
        <>
          <label>
            Utilidad neta objetivo
            <input type="number" required min="0.01" step="0.01" value={targetAmount} onChange={(e) => setTargetAmount(e.target.value)} />
          </label>
          <label>
            Moneda
            <input type="text" value={currency} onChange={(e) => setCurrency(e.target.value)} />
          </label>
          <label>
            Plazo (opcional)
            <input type="date" value={deadline} onChange={(e) => setDeadline(e.target.value)} />
          </label>
        </>
      )}
      <button type="submit" disabled={mutation.isPending || !instruction.trim()}>
        {mutation.isPending ? 'Iniciando...' : 'Iniciar misión'}
      </button>
      {feedback && <p className="feedback">{feedback}</p>}
    </form>
  )
}

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
      <StartMissionForm />
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
