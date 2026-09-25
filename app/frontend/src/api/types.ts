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

// environment: PRODUCTION (actividad empresarial real) o TEST (misiones
// de desarrollo/depuración) -- separado de status a propósito, ver
// CLAUDE.md "MISSION-001 vs. las ~25 misiones de prueba acumuladas".
export interface MissionResponse {
  missionId: string
  status: MissionStatus
  environment: string
  progress: number
  currentStep: string
  message: string
  updatedAt: string
  financialCriteria: FinancialCriteriaResponse | null
  // Equipo responsable (TEAM-...) o null en misiones de discovery
  teamId: string | null
}

export interface AgentTask {
  taskId: string
  missionId: string
  agentId: string
  action: string
  status: string
  result: string
  updatedAt: string
  // Misiones por equipo: PLANNING | WORK | VALIDATION (null en discovery)
  kind: string | null
  workspacePath: string | null
  commitSha: string | null
  files: string[] | null
  // Solo en la tarea VALIDATION: STATICALLY_VALIDATED | UNVALIDATED | FAILED
  validationStatus: string | null
  // JSON de StaticCheck[]
  staticChecks: string | null
}

export interface StaticCheck {
  check: string
  status: 'PASS' | 'FAIL'
  detail: string
  sha: string | null
  paths: string[]
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

// Estructura real de uno de los 3 equipos (Team/MEMBER_OF/LEADS en
// Neo4j) -- separado a propósito de AgentStatusResponse: "quién
// pertenece a qué equipo" y "qué está haciendo ahora" son preguntas
// distintas, igual que en el backend (ChatIntentRouter.formatTeamDetails).
export interface TeamMemberInfo {
  agentId: string
  name: string
  role: string
  roleCode: string | null
  capabilities: string[]
  model: string | null
}

export interface TeamSnapshot {
  teamId: string
  teamName: string | null
  status: string | null
  leaderAgentId: string | null
  members: TeamMemberInfo[]
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

// Prompt versionado de un agente -- ver GET/PUT /api/company/agents/{id}/prompt
export interface PromptVersionSummary {
  version: number
  createdBy: string
  changeReason: string
  createdAt: string
}

export interface PromptSnapshot {
  agentId: string
  activeVersion: number
  activeContent: string
  activeCreatedBy: string
  activeChangeReason: string
  activeCreatedAt: string
  versions: PromptVersionSummary[]
}

export interface PromptVersionContent {
  version: number
  content: string
}

export interface PromptCommand {
  content: string
  changeReason: string
}

export type FinancialMetric = 'NET_PROFIT'

export interface FinancialCriteriaCommand {
  metric: FinancialMetric
  targetAmount: number
  currency: string
  deadline: string | null
}

export interface FinancialCriteriaResponse {
  metric: FinancialMetric
  targetAmount: number
  currency: string
  deadline: string | null
}

export interface FinancialCriteriaEvaluation {
  metric: FinancialMetric
  targetAmount: number
  currency: string
  deadline: string | null
  criterionMet: boolean
  progressPct: number
  deadlinePassed: boolean | null
}

export interface MissionProfitResponse {
  missionId: string
  totalRevenueUsd: number
  totalCostUsd: number
  netProfitUsd: number
  seedCapitalUsd: number
  successCriterionMet: boolean
  successLevel: string
  financialCriteriaEvaluation: FinancialCriteriaEvaluation | null
}

export interface MissionCommand {
  missionId: string
  instruction: string
  environment: string
  financialCriteria: FinancialCriteriaCommand | null
  teamId: string | null
}

// Company Financial Policy versionada -- ver GET/PUT /api/company/policies
export type PolicyKey =
  | 'SEED_CAPITAL_USD'
  | 'CHALLENGE_DAYS'
  | 'CONTRADICTION_SEED_CAPITAL_MULTIPLE'
  | 'SUCCESS_THRESHOLD_GOOD'
  | 'SUCCESS_THRESHOLD_VERY_GOOD'
  | 'SUCCESS_THRESHOLD_EXCELLENT'
  | 'SUCCESS_THRESHOLD_EXTRAORDINARY'

export interface PolicyVersionSummary {
  version: number
  value: number
  createdBy: string
  changeReason: string
  createdAt: string
}

export interface PolicySnapshot {
  key: PolicyKey
  activeVersion: number
  activeValue: number
  createdBy: string
  changeReason: string
  updatedAt: string
  history: PolicyVersionSummary[]
}

export interface PolicyCommand {
  value: number
  changeReason: string
}
