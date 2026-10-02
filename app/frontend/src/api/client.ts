import type {
  ActivityItem,
  AgentInfo,
  AgentStatusResponse,
  AutonomyCommand,
  AutonomyView,
  ChatResponse,
  DecisionCommand,
  DecisionResponse,
  DependencyInfo,
  FinanceCorrectionCommand,
  FinanceCustomer,
  FinanceCustomerCommand,
  FinanceExpenseCommand,
  FinanceSaleCommand,
  FinanceSummary,
  ModelHealth,
  MissionCommand,
  MissionProfitResponse,
  MissionResponse,
  MissionStatusResponse,
  PolicyCommand,
  ApiKeyStatus,
  OrchestratorView,
  ProductCommand,
  ProductView,
  PolicySnapshot,
  PromptCommand,
  PromptSnapshot,
  PromptVersionContent,
  SettingsCommand,
  SettingsResponse,
  TeamSnapshot,
  ContactDraft,
  Prospect,
  ProspectingRun,
  StrategyView,
} from './types'

async function request<T>(path: string, init?: RequestInit): Promise<T> {
  const response = await fetch(path, {
    headers: { 'Content-Type': 'application/json' },
    ...init,
  })

  if (!response.ok) {
    // El backend devuelve el motivo real (server.error.include-message): se muestra tal cual en la pantalla.
    const body = await response.text()
    let message = `${init?.method ?? 'GET'} ${path} -> HTTP ${response.status}`
    try {
      const parsed = JSON.parse(body) as { message?: string }
      if (parsed.message) message = parsed.message
    } catch {
      // sin cuerpo JSON: queda el mensaje genérico
    }
    throw new Error(message)
  }

  // 404 en los endpoints que devuelven Optional puede venir sin body.
  const text = await response.text()
  return (text ? JSON.parse(text) : undefined) as T
}

export const api = {
  agents: () => request<AgentInfo[]>('/api/company/agents'),
  updateAgentModel: (agentId: string, model: string) =>
    request<{ agentId: string; model: string }>(`/api/company/agents/${agentId}/model`, {
      method: 'PUT',
      body: JSON.stringify({ model }),
    }),
  setAgentEnabled: (agentId: string, enabled: boolean) =>
    request<{ agentId: string; enabled: boolean }>(`/api/company/agents/${agentId}/enabled`, {
      method: 'PUT',
      body: JSON.stringify({ enabled }),
    }),
  modelsHealth: () => request<ModelHealth[]>('/api/company/models/health'),
  updateAgentFallbackModel: (agentId: string, model: string) =>
    request<{ agentId: string; model: string }>(`/api/company/agents/${agentId}/fallback-model`, {
      method: 'PUT',
      body: JSON.stringify({ model }),
    }),
  dependencies: () => request<DependencyInfo[]>('/api/company/dependencies'),
  approveDependency: (id: string) =>
    request<unknown>(`/api/company/dependencies/${encodeURIComponent(id)}/approve`, { method: 'PUT' }),
  rejectDependency: (id: string) =>
    request<unknown>(`/api/company/dependencies/${encodeURIComponent(id)}/reject`, { method: 'PUT' }),

  finance: (missionId?: string, productId?: string) =>
    request<FinanceSummary>(
      `/api/company/finance${productId ? `?productId=${encodeURIComponent(productId)}` : missionId ? `?missionId=${encodeURIComponent(missionId)}` : ''}`,
    ),
  products: () => request<ProductView[]>('/api/company/products'),
  orchestrator: () => request<OrchestratorView>('/api/company/orchestrator'),
  autonomy: () => request<AutonomyView>('/api/company/autonomy'),
  outreachDrafts: (status?: string) =>
    request<ContactDraft[]>(`/api/company/outreach/drafts${status ? `?status=${status}` : ''}`),
  editDraft: (id: string, subject: string, body: string) =>
    request<ContactDraft>(`/api/company/outreach/drafts/${encodeURIComponent(id)}`, {
      method: 'PUT',
      body: JSON.stringify({ subject, body }),
    }),
  approveDraft: (id: string) => request<ContactDraft>(`/api/company/outreach/drafts/${encodeURIComponent(id)}/approve`, { method: 'POST' }),
  discardDraft: (id: string) => request<ContactDraft>(`/api/company/outreach/drafts/${encodeURIComponent(id)}/discard`, { method: 'POST' }),
  approveAllDrafts: () => request<ContactDraft[]>('/api/company/outreach/drafts/approve-all', { method: 'POST' }),
  prospectResponse: (id: string, response: 'INTERESTED' | 'NOT_INTERESTED' | 'OPTED_OUT') =>
    request<unknown>(`/api/company/outreach/prospects/${encodeURIComponent(id)}/response`, {
      method: 'POST',
      body: JSON.stringify({ response }),
    }),
  convertProspect: (id: string) =>
    request<{ customerId: string }>(`/api/company/outreach/prospects/${encodeURIComponent(id)}/convert`, { method: 'POST' }),
  outreachSettings: () => request<{ signature: string }>('/api/company/outreach/settings'),
  updateOutreachSettings: (signature: string) =>
    request<{ signature: string }>('/api/company/outreach/settings', { method: 'PUT', body: JSON.stringify({ signature }) }),
  prospects: () => request<Prospect[]>('/api/company/prospecting/prospects'),
  prospectingRuns: () => request<ProspectingRun[]>('/api/company/prospecting/runs'),
  runProspectingNow: () => request<ProspectingRun>('/api/company/prospecting/runs', { method: 'POST' }),
  prospectingStrategies: () => request<StrategyView[]>('/api/company/prospecting/strategies'),
  decideStrategy: (id: string, decision: 'approve' | 'reject') =>
    request<unknown>(`/api/company/prospecting/strategies/${encodeURIComponent(id)}/${decision}`, { method: 'PUT' }),
  setAutonomy: (command: AutonomyCommand) =>
    request<AutonomyView>('/api/company/autonomy', { method: 'PUT', body: JSON.stringify(command) }),
  apiKeys: () => request<ApiKeyStatus[]>('/api/company/api-keys'),
  updateApiKey: (provider: string, apiKey: string) =>
    request<ApiKeyStatus>(`/api/company/api-keys/${provider}`, { method: 'PUT', body: JSON.stringify({ apiKey }) }),
  createProduct: (command: ProductCommand) =>
    request<ProductView>('/api/company/products', { method: 'POST', body: JSON.stringify(command) }),
  updateProduct: (id: string, command: ProductCommand) =>
    request<ProductView>(`/api/company/products/${id}`, { method: 'PUT', body: JSON.stringify(command) }),
  changeProductStatus: (id: string, status: string, reason: string) =>
    request<ProductView>(`/api/company/products/${id}/status`, { method: 'PUT', body: JSON.stringify({ status, reason }) }),
  linkProductMissions: (id: string, validatedBy: string[], builtBy: string[]) =>
    request<ProductView>(`/api/company/products/${id}/missions`, {
      method: 'PUT',
      body: JSON.stringify({ validatedBy, builtBy }),
    }),
  financeCustomers: () => request<FinanceCustomer[]>('/api/company/finance/customers'),
  createFinanceCustomer: (command: FinanceCustomerCommand) =>
    request<FinanceCustomer>('/api/company/finance/customers', { method: 'POST', body: JSON.stringify(command) }),
  createSale: (command: FinanceSaleCommand) =>
    request<{ id: string }>('/api/company/finance/sales', { method: 'POST', body: JSON.stringify(command) }),
  createExpense: (command: FinanceExpenseCommand) =>
    request<{ id: string }>('/api/company/finance/expenses', { method: 'POST', body: JSON.stringify(command) }),
  createCorrection: (command: FinanceCorrectionCommand) =>
    request<{ id: string }>('/api/company/finance/corrections', { method: 'POST', body: JSON.stringify(command) }),

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

  deleteMission: (missionId: string) =>
    request<void>(`/api/company/missions/${missionId}`, { method: 'DELETE' }),

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
