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
  return (
    <div className="card orgcard" onClick={onClick} role="button" tabIndex={0}>
      {teamName && <div className="team-label">{teamName}</div>}
      <div className="card-value">
        {statusDot(agent.status)} {agent.name}
        {isLeader && <span className="leader-tag">líder</span>}
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

function PromptEditor({ agent, onClose }: { agent: AgentStatusResponse; onClose: () => void }) {
  const queryClient = useQueryClient()
  const [content, setContent] = useState('')
  const [changeReason, setChangeReason] = useState('')

  const promptQuery = useQuery({
    queryKey: ['agentPrompt', agent.agentId],
    queryFn: () => api.agentPrompt(agent.agentId),
  })

  const saveMutation = useMutation({
    mutationFn: () => api.updateAgentPrompt(agent.agentId, { content, changeReason }),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['agentPrompt', agent.agentId] })
      setChangeReason('')
    },
  })

  const activateMutation = useMutation({
    mutationFn: (version: number) => api.activateAgentPromptVersion(agent.agentId, version),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: ['agentPrompt', agent.agentId] }),
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
  const textareaValue = content || (content === '' && !saveMutation.isSuccess ? snapshot.activeContent : content)

  return (
    <div className="modal-backdrop" onClick={onClose}>
      <div className="modal-panel" onClick={(e) => e.stopPropagation()}>
        <h2>Prompt de {agent.name}</h2>
        <p className="hint">
          Versión activa: v{snapshot.activeVersion} — {snapshot.activeChangeReason}
        </p>

        <label>
          Instrucciones adicionales (no reemplazan las reglas de seguridad, siempre fijas en código)
          <textarea
            rows={8}
            value={textareaValue}
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
