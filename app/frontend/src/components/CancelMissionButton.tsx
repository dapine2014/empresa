import { useState } from 'react'
import { useMutation, useQueryClient } from '@tanstack/react-query'
import { api } from '../api/client'
import type { MissionStatus } from '../api/types'

// Mismo criterio que MissionService.NOT_CANCELLABLE: una misión terminada no se cancela (se borra).
const FINISHED = new Set<MissionStatus>(['COMPLETED', 'CANCELLED', 'FAILED'])

interface Props {
  missionId: string
  status: MissionStatus
}

/**
 * Cancelar una misión en curso (decisión del fundador 2026-10-15): queda CANCELLED al instante y el trabajo vivo se
 * detiene en su próximo paso (la llamada al modelo que ya estaba corriendo termina sola). No se retoma.
 */
export default function CancelMissionButton({ missionId, status }: Props) {
  const queryClient = useQueryClient()
  const [confirming, setConfirming] = useState(false)
  const [error, setError] = useState<string | null>(null)

  const mutation = useMutation({
    mutationFn: () => api.cancelMission(missionId),
    onSuccess: () => {
      setConfirming(false)
      void queryClient.invalidateQueries({ queryKey: ['missions'] })
      void queryClient.invalidateQueries({ queryKey: ['missionDetails', missionId] })
    },
    onError: (e: Error) => {
      setConfirming(false)
      setError(e.message)
    },
  })

  if (FINISHED.has(status)) return null

  if (confirming) {
    return (
      <span className="delete-mission">
        ¿Cancelar {missionId}? No se retoma.{' '}
        <button type="button" className="danger" disabled={mutation.isPending} onClick={() => mutation.mutate()}>
          {mutation.isPending ? 'Cancelando...' : 'Sí, cancelar'}
        </button>{' '}
        <button type="button" disabled={mutation.isPending} onClick={() => setConfirming(false)}>
          No
        </button>
      </span>
    )
  }

  return (
    <span className="delete-mission">
      <button
        type="button"
        className="danger"
        title="Detener la misión: queda CANCELLED y no se retoma"
        onClick={() => {
          setError(null)
          setConfirming(true)
        }}
      >
        Cancelar misión
      </button>
      {error && <span className="error"> {error}</span>}
    </span>
  )
}
