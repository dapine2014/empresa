import { useState } from 'react'
import { useNavigate, useParams } from 'react-router-dom'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { api } from '../api/client'
import { statusDot } from '../statusColor'
import DeleteMissionButton from '../components/DeleteMissionButton'
import type { InvestorDecision, StaticCheck } from '../api/types'

function parseChecks(raw: string | null): StaticCheck[] {
  if (!raw) return []
  try {
    return JSON.parse(raw) as StaticCheck[]
  } catch {
    return []
  }
}

const DECIDABLE = new Set(['AWAITING_INVESTOR', 'FAILED'])

export default function MissionDetailPage() {
  const { missionId } = useParams<{ missionId: string }>()
  const queryClient = useQueryClient()
  const navigate = useNavigate()
  const [reasoning, setReasoning] = useState('')
  const [decision, setDecision] = useState<InvestorDecision>('APPROVE')
  const [feedback, setFeedback] = useState<string | null>(null)

  const { data, isLoading, error } = useQuery({
    queryKey: ['missionDetails', missionId],
    queryFn: () => api.missionDetails(missionId!),
    enabled: !!missionId,
    refetchInterval: 5_000,
  })

  const netProfitQuery = useQuery({
    queryKey: ['netProfit', missionId],
    queryFn: () => api.netProfit(missionId!),
    enabled: !!missionId,
  })

  const mutation = useMutation({
    mutationFn: () => api.recordDecision(missionId!, { decision, reasoning }),
    onSuccess: (response) => {
      setFeedback(`Decisión registrada: ${response.decision} sobre ${response.missionId}.`)
      setReasoning('')
      queryClient.invalidateQueries({ queryKey: ['missionDetails', missionId] })
      queryClient.invalidateQueries({ queryKey: ['missions'] })
    },
    onError: () => setFeedback('No se pudo registrar la decisión (¿la misión sigue en curso?).'),
  })

  if (isLoading) return <p>Cargando misión...</p>
  if (error || !data) return <p className="error">No se encontró la misión {missionId}.</p>

  const { mission, tasks } = data
  const canDecide = DECIDABLE.has(mission.status)

  return (
    <div>
      <h1>{mission.missionId}</h1>
      <p>
        {statusDot(mission.status)} <strong>{mission.status}</strong> — {mission.currentStep} ({mission.progress}%)
        {' — '}
        {mission.environment === 'PRODUCTION' ? '🏢 PRODUCTION' : '🧪 TEST'}
        {mission.teamId && <>{' — '}👥 {mission.teamId}</>}
      </p>
      <p className="mission-message">{mission.message}</p>
      <DeleteMissionButton
        missionId={mission.missionId}
        status={mission.status}
        onDeleted={() => navigate('/missions')}
      />

      {mission.financialCriteria && (
        <div className="financial-criteria">
          <h2>Objetivo financiero</h2>
          <p>
            {mission.financialCriteria.metric} ≥ {mission.financialCriteria.targetAmount} {mission.financialCriteria.currency}
            {mission.financialCriteria.deadline ? ` para ${mission.financialCriteria.deadline}` : ' (sin plazo definido)'}
          </p>
          {netProfitQuery.data?.financialCriteriaEvaluation && (
            <p>
              Resultado real: {netProfitQuery.data.netProfitUsd.toFixed(2)} {mission.financialCriteria.currency} (
              {netProfitQuery.data.financialCriteriaEvaluation.progressPct.toFixed(1)}% del objetivo) —{' '}
              {netProfitQuery.data.financialCriteriaEvaluation.criterionMet ? 'cumplido' : 'no cumplido todavía'}
            </p>
          )}
        </div>
      )}

      <h2>Tareas por agente</h2>
      <table className="data-table">
        <thead>
          <tr>
            <th>Agente</th>
            <th>Tipo</th>
            <th>Acción</th>
            <th>Estado</th>
            <th>Commit</th>
            <th>Archivos</th>
            <th>Actualizada</th>
          </tr>
        </thead>
        <tbody>
          {tasks.map((task) => (
            <tr key={task.taskId}>
              <td>{task.agentId.toUpperCase()}</td>
              <td>{task.kind ?? '—'}</td>
              <td>{task.action}</td>
              <td>
                {statusDot(task.status)} {task.status}
              </td>
              <td>{task.commitSha ? <code>{task.commitSha.slice(0, 7)}</code> : '—'}</td>
              <td>{task.files && task.files.length > 0 ? task.files.join(', ') : '—'}</td>
              <td>{new Date(task.updatedAt).toLocaleString()}</td>
            </tr>
          ))}
        </tbody>
      </table>

      {tasks
        .filter((task) => task.validationStatus)
        .map((task) => (
          <section key={`${task.taskId}-validation`}>
            <h2>Validación estática</h2>
            <p>
              {statusDot(task.validationStatus ?? '')} <strong>{task.validationStatus}</strong> — revisada por{' '}
              {task.agentId.toUpperCase()}. Esta fase no ejecuta código.
            </p>
            <ul>
              {parseChecks(task.staticChecks).map((check, index) => (
                <li key={`${check.check}-${index}`}>
                  {check.status === 'PASS' ? '🟢' : '🔴'} {check.check}: {check.detail}
                </li>
              ))}
            </ul>
          </section>
        ))}

      <h2>Decisión del inversionista</h2>
      {!canDecide && (
        <p className="hint">
          Solo se puede decidir sobre una misión en AWAITING_INVESTOR o FAILED (estado actual: {mission.status}).
        </p>
      )}
      {canDecide && (
        <form
          className="decision-form"
          onSubmit={(e) => {
            e.preventDefault()
            mutation.mutate()
          }}
        >
          <label>
            Decisión
            <select value={decision} onChange={(e) => setDecision(e.target.value as InvestorDecision)}>
              <option value="APPROVE">Aprobar</option>
              <option value="REJECT">Rechazar</option>
              <option value="REQUEST_MORE_EVIDENCE">Pedir más evidencia</option>
            </select>
          </label>
          <label>
            Razón (obligatoria)
            <textarea
              required
              value={reasoning}
              onChange={(e) => setReasoning(e.target.value)}
              rows={3}
            />
          </label>
          <button type="submit" disabled={mutation.isPending || !reasoning.trim()}>
            {mutation.isPending ? 'Registrando...' : 'Registrar decisión'}
          </button>
        </form>
      )}
      {feedback && <p className="feedback">{feedback}</p>}
    </div>
  )
}
