// Mapeo de estados reales (AgentTask/MissionStatus, ambos strings desde
// el backend) a un color semántico simple para toda la UI.

const GREEN = new Set(['RUNNING', 'WORKING', 'ACTIVE', 'COMPLETED'])
const YELLOW = new Set(['PENDING', 'WAITING', 'AWAITING_INVESTOR', 'CONSOLIDATING', 'EVALUATING', 'WAITING_AGENT_RESULTS', 'PLANNING', 'DELEGATING', 'CREATED'])
const RED = new Set(['FAILED', 'CANCELLED'])

export function statusDot(status: string): string {
  const s = status.toUpperCase()
  if (GREEN.has(s)) return '🟢'
  if (RED.has(s)) return '🔴'
  if (YELLOW.has(s)) return '🟡'
  return '⚪'
}
