import { useEffect, useState } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { api } from '../api/client'

export default function SettingsPage() {
  const queryClient = useQueryClient()
  const [alertEmail, setAlertEmail] = useState('')
  const [systemEmail, setSystemEmail] = useState('')
  const [mailPassword, setMailPassword] = useState('')
  const [feedback, setFeedback] = useState<string | null>(null)

  const { data, isLoading, error } = useQuery({
    queryKey: ['settings'],
    queryFn: api.settings,
  })

  useEffect(() => {
    if (data) {
      setAlertEmail(data.alertEmail)
      setSystemEmail(data.systemEmail)
    }
  }, [data])

  const mutation = useMutation({
    mutationFn: () => api.updateSettings({ alertEmail, systemEmail, mailPassword }),
    onSuccess: (response) => {
      setFeedback('Configuración guardada.')
      setMailPassword('')
      queryClient.setQueryData(['settings'], response)
    },
    onError: () => setFeedback('No se pudo guardar (¿formato de correo válido?).'),
  })

  if (isLoading) return <p>Cargando configuración...</p>
  if (error) return <p className="error">No se pudo cargar la configuración.</p>

  return (
    <div>
      <h1>Settings</h1>

      <form
        className="decision-form"
        onSubmit={(e) => {
          e.preventDefault()
          setFeedback(null)
          mutation.mutate()
        }}
      >
        <label>
          Correo de alertas (a quién le llegan)
          <input
            type="email"
            required
            value={alertEmail}
            onChange={(e) => setAlertEmail(e.target.value)}
          />
        </label>

        <p className="hint">
          Correo propio del sistema: la cuenta de Gmail que la empresa usa para enviar las alertas.
        </p>
        <label>
          Correo del sistema (remitente)
          <input
            type="email"
            value={systemEmail}
            onChange={(e) => setSystemEmail(e.target.value)}
            placeholder="ai-company@gmail.com"
          />
        </label>
        <label>
          Clave de aplicación de Gmail
          <input
            type="password"
            value={mailPassword}
            onChange={(e) => setMailPassword(e.target.value)}
            placeholder={systemEmail ? '•••••• (dejar vacío para no cambiarla)' : 'App Password de Gmail'}
            autoComplete="new-password"
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
