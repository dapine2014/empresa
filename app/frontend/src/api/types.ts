// Refleja los records Java del backend (com.aicompany.core.model.*).
// Mantener sincronizado a mano -- no hay generación automática de tipos.

export type MissionStatus =
  | 'CREATED'
  | 'PLANNING'
  | 'DELEGATING'
  | 'RESEARCHING'
  | 'EVALUATING'
  | 'WAITING_AGENT_RESULTS'
  | 'CONSOLIDATING'
  | 'AWAITING_INVESTOR'
  | 'EXECUTING'
  | 'COMPLETED'
  | 'FAILED'
  | 'CANCELLED'

export interface MissionResponse {
  missionId: string
  status: MissionStatus
  progress: number
  currentStep: string
  message: string
  updatedAt: string
}

export interface AgentTask {
  taskId: string
  missionId: string
  agentId: string
  action: string
  status: string
  result: string
  updatedAt: string
}

export interface MissionStatusResponse {
  mission: MissionResponse
  tasks: AgentTask[]
}

// status: estado propio del agente (WORKING/IDLE), propiedad real del
// nodo Agent en Neo4j -- distinto de taskStatus, el status de su última
// AgentTask (p. ej. COMPLETED/FAILED). No confundir ambos: un agente
// puede estar IDLE con su última tarea en COMPLETED.
export interface AgentStatusResponse {
  agentId: string
  name: string
  role: string
  personality: string
  status: string
  missionId: string | null
  action: string | null
  taskStatus: string | null
  updatedAt: string | null
}

export interface ActivityItem {
  type: 'TASK' | 'MISSION' | 'EVIDENCE' | 'DECISION'
  missionId: string | null
  agentId: string | null
  description: string
  timestamp: string
}

export type InvestorDecision = 'APPROVE' | 'REJECT' | 'REQUEST_MORE_EVIDENCE'

export interface DecisionCommand {
  decision: InvestorDecision
  reasoning: string
}

export interface DecisionResponse {
  decisionId: string
  missionId: string
  decision: InvestorDecision
  recordedAt: string
}

export interface ChatResponse {
  agent: string
  response: string
}

export interface SettingsResponse {
  alertEmail: string
  systemEmail: string
}

export interface SettingsCommand {
  alertEmail: string
  systemEmail: string
  mailPassword: string
}
