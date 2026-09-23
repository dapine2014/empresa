import { useEffect, useState } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { api } from '../api/client'
import type { PolicySnapshot } from '../api/types'

const POLICY_LABELS: Record<string, string> = {
  SEED_CAPITAL_USD: 'Capital semilla (US$)',
  CHALLENGE_DAYS: 'Ventana de tiempo (días)',
  CONTRADICTION_SEED_CAPITAL_MULTIPLE: 'Multiplicador de alerta financiera (x capital semilla)',
  SUCCESS_THRESHOLD_GOOD: 'Umbral de éxito: Bueno (US$, >)',
  SUCCESS_THRESHOLD_VERY_GOOD: 'Umbral de éxito: Muy bueno (US$, >)',
  SUCCESS_THRESHOLD_EXCELLENT: 'Umbral de éxito: Excelente (US$, ≥)',
  SUCCESS_THRESHOLD_EXTRAORDINARY: 'Umbral de éxito: Extraordinario (US$, ≥)',
}

function PolicyRow({ policy }: { policy: PolicySnapshot }) {
  const queryClient = useQueryClient()
  const [value, setValue] = useState<number | null>(null)
  const [changeReason, setChangeReason] = useState('')
  const [expanded, setExpanded] = useState(false)

  const displayedValue = value ?? policy.activeValue

  const saveMutation = useMutation({
    mutationFn: () => api.updatePolicy(policy.key, { value: displayedValue, changeReason }),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['policies'] })
      setChangeReason('')
      setValue(null)
    },
  })

  const activateMutation = useMutation({
    mutationFn: (version: number) => api.activatePolicyVersion(policy.key, version),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['policies'] })
      setValue(null)
    },
  })

  return (
    <div className="policy-row">
      <div className="policy-row-main">
        <label>
          {POLICY_LABELS[policy.key] ?? policy.key}
          <input type="number" step="0.01" value={displayedValue} onChange={(e) => setValue(Number(e.target.value))} />
        </label>
        <input
          type="text"
          placeholder="Motivo del cambio"
          value={changeReason}
          onChange={(e) => setChangeReason(e.target.value)}
        />
        <button disabled={!changeReason.trim() || saveMutation.isPending} onClick={() => saveMutation.mutate()}>
          Guardar
        </button>
        <button type="button" onClick={() => setExpanded((v) => !v)}>
          {expanded ? 'Ocultar historial' : `Historial (v${policy.activeVersion})`}
        </button>
      </div>
      {saveMutation.isError && <p className="error">No se pudo guardar la política.</p>}
      {expanded && (
        <ul className="prompt-version-list">
          {policy.history.map((v) => (
            <li key={v.version}>
              <span>
                v{v.version} = {v.value} — {v.changeReason} ({new Date(v.createdAt).toLocaleString()})
              </span>
              {v.version !== policy.activeVersion && (
                <button disabled={activateMutation.isPending} onClick={() => activateMutation.mutate(v.version)}>
                  Activar
                </button>
              )}
              {v.version === policy.activeVersion && <span className="leader-tag">activa</span>}
            </li>
          ))}
        </ul>
      )}
    </div>
  )
}

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

  const policiesQuery = useQuery({ queryKey: ['policies'], queryFn: api.policies })

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

      <h2>Financial Policies</h2>
      <p className="hint">
        Reglas de negocio versionadas de Forjai — capital semilla, umbrales de éxito, ventana de tiempo. La
        fórmula de ganancia y la validación matemática de los agentes NO son editables acá: son reglas de
        dominio deterministas.
      </p>
      {policiesQuery.isLoading && <p>Cargando políticas...</p>}
      {policiesQuery.error && <p className="error">No se pudieron cargar las políticas.</p>}
      {policiesQuery.data?.map((policy) => <PolicyRow key={policy.key} policy={policy} />)}
    </div>
  )
}
