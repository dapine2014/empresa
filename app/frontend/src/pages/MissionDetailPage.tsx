import { useState } from 'react'
import { useParams } from 'react-router-dom'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { api } from '../api/client'
import { statusDot } from '../statusColor'
import type { InvestorDecision } from '../api/types'

const DECIDABLE = new Set(['AWAITING_INVESTOR', 'FAILED'])

export default function MissionDetailPage() {
  const { missionId } = useParams<{ missionId: string }>()
  const queryClient = useQueryClient()
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
      </p>
      <p className="mission-message">{mission.message}</p>

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
            <th>Acción</th>
            <th>Estado</th>
            <th>Actualizada</th>
          </tr>
        </thead>
        <tbody>
          {tasks.map((task) => (
            <tr key={task.taskId}>
              <td>{task.agentId.toUpperCase()}</td>
              <td>{task.action}</td>
              <td>
                {statusDot(task.status)} {task.status}
              </td>
              <td>{new Date(task.updatedAt).toLocaleString()}</td>
            </tr>
          ))}
        </tbody>
      </table>

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
