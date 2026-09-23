import type {
  ActivityItem,
  AgentStatusResponse,
  ChatResponse,
  DecisionCommand,
  DecisionResponse,
  MissionCommand,
  MissionProfitResponse,
  MissionResponse,
  MissionStatusResponse,
  PolicyCommand,
  PolicySnapshot,
  PromptCommand,
  PromptSnapshot,
  PromptVersionContent,
  SettingsCommand,
  SettingsResponse,
  TeamSnapshot,
} from './types'

async function request<T>(path: string, init?: RequestInit): Promise<T> {
  const response = await fetch(path, {
    headers: { 'Content-Type': 'application/json' },
    ...init,
  })

  if (!response.ok) {
    throw new Error(`${init?.method ?? 'GET'} ${path} -> HTTP ${response.status}`)
  }

  // 404 en los endpoints que devuelven Optional puede venir sin body.
  const text = await response.text()
  return (text ? JSON.parse(text) : undefined) as T
}

export const api = {
  health: () => request<{ status: string }>('/actuator/health'),

  agentsStatus: () => request<AgentStatusResponse[]>('/api/company/agents/status'),

  teams: () => request<TeamSnapshot[]>('/api/company/teams'),

  agentPrompt: (agentId: string) => request<PromptSnapshot>(`/api/company/agents/${agentId}/prompt`),

  agentPromptVersion: (agentId: string, version: number) =>
    request<PromptVersionContent>(`/api/company/agents/${agentId}/prompt/versions/${version}`),

  updateAgentPrompt: (agentId: string, command: PromptCommand) =>
    request<PromptSnapshot>(`/api/company/agents/${agentId}/prompt`, {
      method: 'PUT',
      body: JSON.stringify(command),
    }),

  activateAgentPromptVersion: (agentId: string, version: number) =>
    request<PromptSnapshot>(`/api/company/agents/${agentId}/prompt/versions/${version}/activate`, {
      method: 'PUT',
    }),

  activity: (limit = 50) => request<ActivityItem[]>(`/api/company/activity?limit=${limit}`),

  missions: () => request<MissionResponse[]>('/api/company/missions'),

  missionDetails: (missionId: string) =>
    request<MissionStatusResponse>(`/api/company/missions/${missionId}/details`),

  recordDecision: (missionId: string, command: DecisionCommand) =>
    request<DecisionResponse>(`/api/company/missions/${missionId}/decision`, {
      method: 'POST',
      body: JSON.stringify(command),
    }),

  chat: (message: string) =>
    request<ChatResponse>('/api/company/chat', {
      method: 'POST',
      body: JSON.stringify({ message }),
    }),

  settings: () => request<SettingsResponse>('/api/company/settings'),

  updateSettings: (command: SettingsCommand) =>
    request<SettingsResponse>('/api/company/settings', {
      method: 'PUT',
      body: JSON.stringify(command),
    }),

  policies: () => request<PolicySnapshot[]>('/api/company/policies'),

  updatePolicy: (key: string, command: PolicyCommand) =>
    request<PolicySnapshot>(`/api/company/policies/${key}`, {
      method: 'PUT',
      body: JSON.stringify(command),
    }),

  activatePolicyVersion: (key: string, version: number) =>
    request<PolicySnapshot>(`/api/company/policies/${key}/versions/${version}/activate`, {
      method: 'PUT',
    }),

  netProfit: (missionId: string) =>
    request<MissionProfitResponse>(`/api/company/missions/${missionId}/net-profit`),

  startMission: (command: MissionCommand) =>
    request<MissionResponse>('/api/company/missions', {
      method: 'POST',
      body: JSON.stringify(command),
    }),
}
