import { useQuery } from '@tanstack/react-query'
import { api } from '../api/client'
import type { AgentStatusResponse } from '../api/types'
import { statusDot } from '../statusColor'
import { humanizeAction } from '../humanize'

function AgentCard({
  agent,
  teamName,
  isLeader,
}: {
  agent: AgentStatusResponse
  teamName?: string
  isLeader?: boolean
}) {
  return (
    <div className="card orgcard">
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

export default function AgentsPage() {
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
  // Reportes directos sin equipo (Sofia/Max/Luna hoy) -- cualquier
  // agente que no sea el CEO y no pertenezca a ninguno de los 3 equipos
  // reales (Team/MEMBER_OF en Neo4j, ver GET /api/company/teams).
  const soloReports = agents.filter((agent) => agent.agentId !== 'ceo' && !teamMemberIds.has(agent.agentId))

  return (
    <div>
      <h1>Agents</h1>
      <ul className="orgtree">
        <li>
          {ceo && <AgentCard agent={ceo} />}
          <ul>
            {soloReports.map((agent) => (
              <li key={agent.agentId}>
                <AgentCard agent={agent} />
              </li>
            ))}
            {teams.map((team) => {
              const leaderAgent = team.leaderAgentId ? byId.get(team.leaderAgentId) : undefined
              const otherMembers = team.members.filter((member) => member.agentId !== team.leaderAgentId)

              return (
                <li key={team.teamId}>
                  {leaderAgent && <AgentCard agent={leaderAgent} teamName={team.teamName ?? undefined} isLeader />}
                  {otherMembers.length > 0 && (
                    <ul>
                      {otherMembers.map((member) => {
                        const memberAgent = byId.get(member.agentId)
                        return memberAgent ? (
                          <li key={member.agentId}>
                            <AgentCard agent={memberAgent} />
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
    </div>
  )
}
