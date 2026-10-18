import { useEffect, useState } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { api } from '../api/client'
import type { ApiKeyStatus, PolicyKey, PolicySnapshot } from '../api/types'
import { DatabasesSettings } from '../components/DatabasesSettings'

// Record<PolicyKey, …>: una policy nueva sin etiqueta no compila.
const POLICY_LABELS: Record<PolicyKey, string> = {
  SEED_CAPITAL_USD: 'Capital semilla (US$)',
  CHALLENGE_DAYS: 'Ventana de tiempo (días)',
  CONTRADICTION_SEED_CAPITAL_MULTIPLE: 'Multiplicador de alerta financiera (x capital semilla)',
  SUCCESS_THRESHOLD_GOOD: 'Umbral de éxito: Bueno (US$, >)',
  SUCCESS_THRESHOLD_VERY_GOOD: 'Umbral de éxito: Muy bueno (US$, >)',
  SUCCESS_THRESHOLD_EXCELLENT: 'Umbral de éxito: Excelente (US$, ≥)',
  SUCCESS_THRESHOLD_EXTRAORDINARY: 'Umbral de éxito: Extraordinario (US$, ≥)',
  MAX_EVIDENCE_ROUNDS: 'Vueltas máximas de "más evidencia" por misión',
  ORCHESTRATOR_ENABLED: 'Orquestador de productos encendido (1 = sí, 0 = no)',
  MAX_AUTONOMOUS_PRODUCTS: 'Productos que el orquestador crea a la vez',
  PROSPECTING_ENABLED: 'Búsqueda de clientes encendida (1 = sí, 0 = no)',
  MAX_PROSPECTS_PER_DAY: 'Prospectos nuevos por día (máximo)',
  MAX_OUTREACH_PER_DAY: 'Correos a prospectos por día (máximo)',
  SPEC_APPROVAL_REQUIRED: 'Aprobar los requisitos de Aria antes de construir (1 = sí, 0 = no)',
  MAX_AUTO_CORRECTIONS: 'Correcciones automáticas por incremento (máximo)',
  MAX_MISSION_COST_USD: 'Costo máximo de modelos por misión (US$)',
  JOINT_REVIEW_ENABLED: 'Revisión conjunta de Neo y Aria de HU y diseño antes de tu aprobación (1 = sí, 0 = no)',
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

const SOURCE_LABELS: Record<ApiKeyStatus['source'], string> = {
  FOUNDER: 'cambiada desde aquí',
  ENV: 'del .env',
  NONE: 'sin key',
}

// Keys de modelos (2026-09-29): se prueban contra NVIDIA antes de guardarse, se guardan cifradas y se aplican al
// instante. La pantalla nunca recibe la key: solo sus últimos 4 caracteres.
function ApiKeyRow({ status }: { status: ApiKeyStatus }) {
  const queryClient = useQueryClient()
  const [apiKey, setApiKey] = useState('')
  const [feedback, setFeedback] = useState<string | null>(null)
  const mutation = useMutation({
    mutationFn: () => api.updateApiKey(status.provider, apiKey),
    onSuccess: () => {
      setApiKey('')
      setFeedback('Key comprobada y guardada: ya se usa en la próxima llamada.')
      void queryClient.invalidateQueries({ queryKey: ['api-keys'] })
    },
    onError: (error: Error) => setFeedback(error.message),
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
      <strong>{status.provider}</strong>
      <span className="hint">
        {status.agents.length ? `Usada por: ${status.agents.join(', ')}` : 'Ningún agente la usa hoy'} ·{' '}
        {status.hint || '—'} ({SOURCE_LABELS[status.source]}
        {status.updatedAt ? `, ${new Date(status.updatedAt).toLocaleString()}` : ''})
      </span>
      <input
        type="password"
        value={apiKey}
        onChange={(e) => setApiKey(e.target.value)}
        placeholder="Pegar la key nueva (nvapi-…)"
        autoComplete="new-password"
      />
      <button type="submit" disabled={mutation.isPending || !apiKey.trim()}>
        {mutation.isPending ? 'Comprobando...' : 'Comprobar y guardar'}
      </button>
      {feedback && <p className={mutation.isError ? 'error' : 'feedback'}>{feedback}</p>}
    </form>
  )
}

function ApiKeysSection() {
  const { data, isLoading, error } = useQuery({ queryKey: ['api-keys'], queryFn: api.apiKeys })
  return (
    <>
      <h2>Keys de modelos</h2>
      <p className="hint">
        Una key por proveedor (NVIDIA y Amazon Bedrock, el respaldo pago cuando NVIDIA cae). Antes de guardarla se prueba con una llamada
        mínima; si el proveedor la rechaza, se conserva la anterior. Se guarda cifrada y nunca se vuelve a mostrar
        completa.
      </p>
      {isLoading && <p>Cargando keys...</p>}
      {error && <p className="error">No se pudieron cargar las keys.</p>}
      {data?.map((status) => <ApiKeyRow key={status.provider} status={status} />)}
    </>
  )
}

// Cadena de modelos gratis (2026-10-15): si el gratis de un agente cae, se prueban estos en orden (saltando los caídos)
// antes del respaldo pago en Bedrock y del suplente local.
function FreeModelChainSection() {
  const queryClient = useQueryClient()
  const { data, isLoading, error } = useQuery({
    queryKey: ['free-model-chain'],
    queryFn: api.freeModelChain,
    refetchInterval: 30_000,
  })
  const [draft, setDraft] = useState<string[] | null>(null)
  const [newModel, setNewModel] = useState('')
  const [feedback, setFeedback] = useState<string | null>(null)
  const mutation = useMutation({
    mutationFn: (models: string[]) => api.updateFreeModelChain(models),
    onSuccess: () => {
      setDraft(null)
      setFeedback('Cadena guardada: la próxima llamada ya la usa.')
      void queryClient.invalidateQueries({ queryKey: ['free-model-chain'] })
    },
    onError: (e: Error) => setFeedback(e.message),
  })
  const saved = data?.models.map((m) => m.model) ?? []
  // Sin edición en curso, la lista es la guardada (se refresca con el estado cada 30 s).
  const models = draft ?? saved
  const statusOf = (model: string) => data?.models.find((m) => m.model === model)
  const move = (index: number, delta: number) => {
    const next = [...models]
    const [item] = next.splice(index, 1)
    next.splice(index + delta, 0, item)
    setDraft(next)
  }
  const dirty = JSON.stringify(saved) !== JSON.stringify(models)
  return (
    <>
      <h2>Modelos gratis</h2>
      <p className="hint">
        Si el modelo gratis de un agente cae o no responde, se prueban los demás de esta lista en orden, saltando los
        caídos (cada minuto se validan con una llamada corta). Si fallan todos, pasa al respaldo pago en Bedrock y luego al
        suplente local.
        {data?.updatedAt ? ` Última edición: ${new Date(data.updatedAt).toLocaleString()}.` : ' Hoy rige la lista inicial.'}
      </p>
      {isLoading && <p>Cargando la cadena...</p>}
      {error && <p className="error">No se pudo cargar la cadena de modelos gratis.</p>}
      <ol>
        {models.map((model, i) => {
          const health = statusOf(model)
          return (
            <li key={model}>
              {health?.status === 'DOWN' ? '⚠ ' : health ? '✅ ' : '• '}
              <code>{model}</code>
              {health?.status === 'DOWN' && (
                <span className="hint">
                  {' '}
                  caído desde {health.since ? new Date(health.since).toLocaleString() : '—'} ({health.lastError})
                </span>
              )}
              {health && health.affectedAgents.length > 0 && (
                <span className="hint"> · principal de {health.affectedAgents.join(', ')}</span>
              )}{' '}
              <button type="button" disabled={i === 0} onClick={() => move(i, -1)} aria-label="Subir">
                ↑
              </button>
              <button type="button" disabled={i === models.length - 1} onClick={() => move(i, 1)} aria-label="Bajar">
                ↓
              </button>
              <button type="button" onClick={() => setDraft(models.filter((m) => m !== model))} aria-label="Quitar">
                ✕
              </button>
            </li>
          )
        })}
      </ol>
      <form
        className="decision-form"
        onSubmit={(e) => {
          e.preventDefault()
          const model = newModel.trim()
          if (model && !models.includes(model)) setDraft([...models, model])
          setNewModel('')
        }}
      >
        <input value={newModel} onChange={(e) => setNewModel(e.target.value)} placeholder="nvidia:z-ai/glm-5.3" />
        <button type="submit" disabled={!newModel.trim()}>
          Agregar al final
        </button>
        <button
          type="button"
          disabled={!dirty || mutation.isPending}
          onClick={() => {
            setFeedback(null)
            mutation.mutate(models)
          }}
        >
          {mutation.isPending ? 'Guardando...' : 'Guardar cadena'}
        </button>
      </form>
      {feedback && <p className={mutation.isError ? 'error' : 'feedback'}>{feedback}</p>}
    </>
  )
}

// Contacto con prospectos (spec 2026-09-30): firma que Java agrega al final de cada correo.
function OutreachSignature() {
  const queryClient = useQueryClient()
  const settings = useQuery({ queryKey: ['outreachSettings'], queryFn: api.outreachSettings })
  const [draft, setDraft] = useState<string | null>(null)
  const [message, setMessage] = useState<string | null>(null)
  const save = useMutation({
    mutationFn: (signature: string) => api.updateOutreachSettings(signature),
    onSuccess: () => {
      setMessage('Firma guardada.')
      setDraft(null)
      void queryClient.invalidateQueries({ queryKey: ['outreachSettings'] })
    },
    onError: (e: Error) => setMessage(e.message),
  })
  const value = draft ?? settings.data?.signature ?? ''
  return (
    <>
      <h2>Firma de los correos a prospectos</h2>
      <p className="hint">Se agrega al final de cada correo que apruebes.</p>
      <input value={value} onChange={(e) => setDraft(e.target.value)} />{' '}
      <button disabled={save.isPending || draft === null} onClick={() => save.mutate(value)}>
        Guardar
      </button>
      {message && <p className={save.isError ? 'error' : 'feedback'}>{message}</p>}
    </>
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

      <OutreachSignature />

      <DatabasesSettings />

      <h2>Financial Policies</h2>
      <p className="hint">
        Reglas de negocio versionadas de Forjai — capital semilla, umbrales de éxito, ventana de tiempo. La
        fórmula de ganancia y la validación matemática de los agentes NO son editables acá: son reglas de
        dominio deterministas.
      </p>
      {policiesQuery.isLoading && <p>Cargando políticas...</p>}
      {policiesQuery.error && <p className="error">No se pudieron cargar las políticas.</p>}
      {policiesQuery.data?.map((policy) => <PolicyRow key={policy.key} policy={policy} />)}

      <FreeModelChainSection />
      <ApiKeysSection />
    </div>
  )
}
