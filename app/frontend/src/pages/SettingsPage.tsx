import { useEffect, useState } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { api } from '../api/client'

export default function SettingsPage() {
  const queryClient = useQueryClient()
  const [alertEmail, setAlertEmail] = useState('')
  const [feedback, setFeedback] = useState<string | null>(null)

  const { data, isLoading, error } = useQuery({
    queryKey: ['settings'],
    queryFn: api.settings,
  })

  useEffect(() => {
    if (data) setAlertEmail(data.alertEmail)
  }, [data])

  const mutation = useMutation({
    mutationFn: () => api.updateSettings({ alertEmail }),
    onSuccess: (response) => {
      setFeedback(`Correo de alertas actualizado a ${response.alertEmail}.`)
      queryClient.setQueryData(['settings'], response)
    },
    onError: () => setFeedback('No se pudo actualizar el correo (¿formato válido?).'),
  })

  if (isLoading) return <p>Cargando configuración...</p>
  if (error) return <p className="error">No se pudo cargar la configuración.</p>

  return (
    <div>
      <h1>Settings</h1>
      <p className="hint">
        Correo al que se envían las alertas inmediatas (misión que necesita tu decisión, misión que falló).
      </p>

      <form
        className="decision-form"
        onSubmit={(e) => {
          e.preventDefault()
          setFeedback(null)
          mutation.mutate()
        }}
      >
        <label>
          Correo de alertas
          <input
            type="email"
            required
            value={alertEmail}
            onChange={(e) => setAlertEmail(e.target.value)}
          />
        </label>
        <button type="submit" disabled={mutation.isPending || !alertEmail.trim()}>
          {mutation.isPending ? 'Guardando...' : 'Guardar'}
        </button>
      </form>
      {feedback && <p className="feedback">{feedback}</p>}
    </div>
  )
}
