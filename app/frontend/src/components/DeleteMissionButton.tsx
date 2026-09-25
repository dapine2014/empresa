import { useState } from 'react'
import { useMutation, useQueryClient } from '@tanstack/react-query'
import { api } from '../api/client'
import type { MissionStatus } from '../api/types'

// Mismo criterio que MissionService.DELETABLE_STATUSES: una misión en curso
// no se borra porque sus threads siguen escribiendo en Neo4j.
const DELETABLE = new Set<MissionStatus>(['AWAITING_INVESTOR', 'FAILED', 'COMPLETED', 'CANCELLED'])
const FOUNDATIONAL_MISSION_ID = 'MISSION-001'

interface Props {
  missionId: string
  status: MissionStatus
  onDeleted?: () => void
}

export default function DeleteMissionButton({ missionId, status, onDeleted }: Props) {
  const queryClient = useQueryClient()
  const [confirming, setConfirming] = useState(false)
  const [error, setError] = useState<string | null>(null)

  const mutation = useMutation({
    mutationFn: () => api.deleteMission(missionId),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['missions'] })
      queryClient.removeQueries({ queryKey: ['missionDetails', missionId] })
      onDeleted?.()
    },
    onError: () => {
      setConfirming(false)
      setError('No se pudo borrar (¿tiene clientes o ventas reales registrados?).')
    },
  })

  if (missionId === FOUNDATIONAL_MISSION_ID) return null

  const deletable = DELETABLE.has(status)

  if (confirming) {
    return (
      <span className="delete-mission">
        ¿Borrar {missionId}?{' '}
        <button type="button" className="danger" disabled={mutation.isPending} onClick={() => mutation.mutate()}>
          {mutation.isPending ? 'Borrando...' : 'Confirmar'}
        </button>{' '}
        <button type="button" disabled={mutation.isPending} onClick={() => setConfirming(false)}>
          Cancelar
        </button>
      </span>
    )
  }

  return (
    <span className="delete-mission">
      <button
        type="button"
        className="danger"
        disabled={!deletable}
        title={deletable ? 'Borrar la misión y todo lo que generó' : 'No se puede borrar una misión en curso'}
        onClick={() => {
          setError(null)
          setConfirming(true)
        }}
      >
        Borrar
      </button>
      {error && <span className="error"> {error}</span>}
    </span>
  )
}
