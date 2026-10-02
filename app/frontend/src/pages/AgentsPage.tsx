import { useState } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { api } from '../api/client'
import type { AgentStatusResponse } from '../api/types'
import { statusDot } from '../statusColor'
import { humanizeAction } from '../humanize'

function AgentCard({
  agent,
  teamName,
  isLeader,
  onClick,
}: {
  agent: AgentStatusResponse
  teamName?: string
  isLeader?: boolean
  onClick: () => void
}) {
  const agentsQuery = useQuery({ queryKey: ['agents'], queryFn: api.agents })
  const off = agentsQuery.data?.find((a) => a.id === agent.agentId)?.enabled === false
  return (
    <div className="card orgcard" onClick={onClick} role="button" tabIndex={0} style={off ? { opacity: 0.55 } : undefined}>
      {teamName && <div className="team-label">{teamName}</div>}
      <div className="card-value">
        {off ? '⚫' : statusDot(agent.status)} {agent.name}
        {isLeader && <span className="leader-tag">líder</span>}
        {off && <span className="leader-tag">apagado</span>}
      </div>
      <div className="card-title">{agent.role}</div>
      <p className="hint">{agent.personality}</p>
      <p className="hint">
        {agent.status === 'WORKING' && agent.missionId
          ? `Trabajando en: ${humanizeAction(agent.action)} (${agent.missionId})`
          : agent.missionId
            ? `Inactivo — última tarea: ${humanizeAction(agent.action)} (${agent.missionId}), resultado: ${agent.taskStatus}`
            : 'Inactivo'}
      </p>
    </div>
  )
}

/** Subproyecto 2 (2026-09-28): el modelo de cada agente se edita acá; Java rechaza un proveedor desconocido. */
function ModelEditor({ agentId }: { agentId: string }) {
  const queryClient = useQueryClient()
  const agentsQuery = useQuery({ queryKey: ['agents'], queryFn: api.agents })
  const current = agentsQuery.data?.find((a) => a.id === agentId)?.model ?? ''
  const currentFallback = agentsQuery.data?.find((a) => a.id === agentId)?.fallbackModel ?? ''
  const healthQuery = useQuery({ queryKey: ['models-health'], queryFn: api.modelsHealth, refetchInterval: 30_000 })
  const down = healthQuery.data?.find((h) => h.model === current && h.status === 'DOWN')
  const [fallback, setFallback] = useState<string | null>(null)
  const displayedFallback = fallback ?? currentFallback
  const fallbackMutation = useMutation({
    mutationFn: () => api.updateAgentFallbackModel(agentId, displayedFallback),
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: ['agents'] })
      setFallback(null)
    },
  })
  const [model, setModel] = useState<string | null>(null)
  const displayed = model ?? current
  const known = Array.from(new Set((agentsQuery.data ?? []).map((a) => a.model).filter(Boolean)))
  const mutation = useMutation({
    mutationFn: () => api.updateAgentModel(agentId, displayed),
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: ['agents'] })
      void queryClient.invalidateQueries({ queryKey: ['teams'] })
      setModel(null)
    },
  })
  const enabled = agentsQuery.data?.find((a) => a.id === agentId)?.enabled ?? true
  const enabledMutation = useMutation({
    mutationFn: (value: boolean) => api.setAgentEnabled(agentId, value),
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: ['agents'] })
      void queryClient.invalidateQueries({ queryKey: ['agentsStatus'] })
    },
  })
  return (
    <div className="decision-form">
      <h3>Encendido</h3>
      <p className="hint">
        {enabled
          ? 'Trabaja: puede recibir tareas, misiones y menciones en el chat.'
          : 'Apagado: nunca se llama a su modelo; las misiones que lo necesitan se rechazan.'}
      </p>
      <label>
        <input
          type="checkbox"
          checked={enabled}
          disabled={agentId === 'ceo' || enabledMutation.isPending}
          onChange={(e) => enabledMutation.mutate(e.target.checked)}
        />{' '}
        {agentId === 'ceo' ? 'Alex no se apaga: es tu interlocutor y consolida las misiones' : 'Encendido'}
      </label>
      {enabledMutation.isError && <p className="error">{enabledMutation.error.message}</p>}
      <h3>Modelo</h3>
      <p className="hint">
        Actual: <code>{current || 'por defecto'}</code>. Formato: <code>proveedor:modelo</code> para NVIDIA (nvidia,
        nvidia-discovery, nvidia-creative, nvidia-ceo) o el nombre de un modelo local de Ollama (p. ej. qwen3:8b).
      </p>
      <input list={`models-${agentId}`} value={displayed} onChange={(e) => setModel(e.target.value)} />
      <datalist id={`models-${agentId}`}>
        {known.map((m) => (
          <option key={m} value={m} />
        ))}
      </datalist>
      <button disabled={!displayed.trim() || displayed === current || mutation.isPending} onClick={() => mutation.mutate()}>
        Guardar modelo
      </button>
      {mutation.isSuccess && <p className="hint">Modelo actualizado.</p>}
      {mutation.isError && <p className="error">{mutation.error.message}</p>}
      {down && (
        <p className="error">
          ⚠ Su modelo no responde desde {down.since?.slice(0, 16).replace('T', ' ')} UTC: trabaja con su suplente{' '}
          <code>{currentFallback || 'ninguno (sus tareas fallan hasta que vuelva)'}</code>.
        </p>
      )}
      <h3>Suplente local</h3>
      <p className="hint">
        Si su modelo remoto cae, Forjai usa este (por defecto qwen3-coder:30b). Vacío = sin suplente.
      </p>
      <input value={displayedFallback} onChange={(e) => setFallback(e.target.value)} placeholder="p. ej. qwen3-coder:30b" />
      <button disabled={displayedFallback === currentFallback || fallbackMutation.isPending} onClick={() => fallbackMutation.mutate()}>
        Guardar suplente
      </button>
      {fallbackMutation.isError && <p className="error">{fallbackMutation.error.message}</p>}
    </div>
  )
}

function PromptEditor({ agent, onClose }: { agent: AgentStatusResponse; onClose: () => void }) {
  const queryClient = useQueryClient()
  const [content, setContent] = useState<string | null>(null)
  const [changeReason, setChangeReason] = useState('')

  const promptQuery = useQuery({
    queryKey: ['agentPrompt', agent.agentId],
    queryFn: () => api.agentPrompt(agent.agentId),
  })

  const displayedContent = content ?? promptQuery.data?.activeContent ?? ''

  const saveMutation = useMutation({
    mutationFn: () => api.updateAgentPrompt(agent.agentId, { content: displayedContent, changeReason }),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['agentPrompt', agent.agentId] })
      setChangeReason('')
      setContent(null)
    },
  })

  const activateMutation = useMutation({
    mutationFn: (version: number) => api.activateAgentPromptVersion(agent.agentId, version),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['agentPrompt', agent.agentId] })
      setContent(null)
    },
  })

  if (promptQuery.isLoading) {
    return (
      <div className="modal-backdrop" onClick={onClose}>
        <div className="modal-panel" onClick={(e) => e.stopPropagation()}>
          <p>Cargando prompt...</p>
        </div>
      </div>
    )
  }

  if (promptQuery.error || !promptQuery.data) {
    return (
      <div className="modal-backdrop" onClick={onClose}>
        <div className="modal-panel" onClick={(e) => e.stopPropagation()}>
          <p className="error">No se pudo cargar el prompt de {agent.name}.</p>
        </div>
      </div>
    )
  }

  const snapshot = promptQuery.data

  return (
    <div className="modal-backdrop" onClick={onClose}>
      <div className="modal-panel" onClick={(e) => e.stopPropagation()}>
        <h2>Prompt de {agent.name}</h2>
        <ModelEditor agentId={agent.agentId} />
        <p className="hint">
          Versión activa: v{snapshot.activeVersion} — {snapshot.activeChangeReason}
        </p>

        <label>
          Instrucciones adicionales (no reemplazan las reglas de seguridad, siempre fijas en código)
          <textarea
            rows={8}
            value={displayedContent}
            onChange={(e) => setContent(e.target.value)}
            placeholder="Ej: Sé especialmente conservador con las proyecciones financieras."
          />
        </label>

        <label>
          Motivo del cambio
          <input
            type="text"
            value={changeReason}
            onChange={(e) => setChangeReason(e.target.value)}
            placeholder="Ej: Ajustar tono tras retro del fundador"
          />
        </label>

        <button
          disabled={!changeReason.trim() || saveMutation.isPending}
          onClick={() => saveMutation.mutate()}
        >
          Guardar (crea versión nueva)
        </button>
        {saveMutation.isError && <p className="error">No se pudo guardar el prompt.</p>}

        <h3>Historial de versiones</h3>
        <ul className="prompt-version-list">
          {snapshot.versions.map((v) => (
            <li key={v.version}>
              <span>
                v{v.version} — {v.changeReason} ({new Date(v.createdAt).toLocaleString()})
              </span>
              {v.version !== snapshot.activeVersion && (
                <button
                  disabled={activateMutation.isPending}
                  onClick={() => activateMutation.mutate(v.version)}
                >
                  Activar
                </button>
              )}
              {v.version === snapshot.activeVersion && <span className="leader-tag">activa</span>}
            </li>
          ))}
        </ul>

        <button onClick={onClose}>Cerrar</button>
      </div>
    </div>
  )
}

export default function AgentsPage() {
  const [selectedAgent, setSelectedAgent] = useState<AgentStatusResponse | null>(null)

  const agentsQuery = useQuery({
    queryKey: ['agentsStatus'],
    queryFn: api.agentsStatus,
    refetchInterval: 5_000,
  })

  const teamsQuery = useQuery({
    queryKey: ['teams'],
    queryFn: api.teams,
    refetchInterval: 5_000,
  })

  if (agentsQuery.isLoading || teamsQuery.isLoading) return <p>Cargando agentes...</p>
  if (agentsQuery.error || teamsQuery.error) {
    return <p className="error">No se pudo cargar el estado de los agentes.</p>
  }

  const agents = agentsQuery.data ?? []
  const teams = teamsQuery.data ?? []
  const byId = new Map(agents.map((agent) => [agent.agentId, agent]))
  const teamMemberIds = new Set(teams.flatMap((team) => team.members.map((member) => member.agentId)))

  const ceo = byId.get('ceo')
  const soloReports = agents.filter((agent) => agent.agentId !== 'ceo' && !teamMemberIds.has(agent.agentId))

  return (
    <div>
      <h1>Agents</h1>
      <ul className="orgtree">
        <li>
          {ceo && <AgentCard agent={ceo} onClick={() => setSelectedAgent(ceo)} />}
          <ul>
            {soloReports.map((agent) => (
              <li key={agent.agentId}>
                <AgentCard agent={agent} onClick={() => setSelectedAgent(agent)} />
              </li>
            ))}
            {teams.map((team) => {
              const leaderAgent = team.leaderAgentId ? byId.get(team.leaderAgentId) : undefined
              const otherMembers = team.members.filter((member) => member.agentId !== team.leaderAgentId)

              return (
                <li key={team.teamId}>
                  {leaderAgent && (
                    <AgentCard
                      agent={leaderAgent}
                      teamName={team.teamName ?? undefined}
                      isLeader
                      onClick={() => setSelectedAgent(leaderAgent)}
                    />
                  )}
                  {otherMembers.length > 0 && (
                    <ul>
                      {otherMembers.map((member) => {
                        const memberAgent = byId.get(member.agentId)
                        return memberAgent ? (
                          <li key={member.agentId}>
                            <AgentCard agent={memberAgent} onClick={() => setSelectedAgent(memberAgent)} />
                          </li>
                        ) : null
                      })}
                    </ul>
                  )}
                </li>
              )
            })}
          </ul>
        </li>
      </ul>

      {selectedAgent && <PromptEditor agent={selectedAgent} onClose={() => setSelectedAgent(null)} />}
    </div>
  )
}
