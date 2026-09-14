import type {
  ActivityItem,
  AgentStatusResponse,
  ChatResponse,
  DecisionCommand,
  DecisionResponse,
  MissionResponse,
  MissionStatusResponse,
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
}
