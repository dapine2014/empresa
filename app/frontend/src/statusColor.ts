// Mapeo de estados reales (AgentTask/MissionStatus, ambos strings desde
// el backend) a un color semántico simple para toda la UI.

const GREEN = new Set(['RUNNING', 'WORKING', 'ACTIVE', 'COMPLETED', 'STATICALLY_VALIDATED', 'VERIFIED', 'READY', 'PASS'])
const YELLOW = new Set(['PENDING', 'WAITING', 'AWAITING_INVESTOR', 'AWAITING_SPEC_APPROVAL', 'CONSOLIDATING', 'EVALUATING', 'WAITING_AGENT_RESULTS', 'PLANNING', 'DELEGATING', 'CREATED', 'GENERATED', 'UNVALIDATED', 'INTERRUPTED', 'PARTIAL', 'PACKAGING'])
const RED = new Set(['FAILED', 'CANCELLED', 'BLOCKED', 'FAIL', 'TIMEOUT'])

export function statusDot(status: string): string {
  const s = status.toUpperCase()
  if (GREEN.has(s)) return '🟢'
  if (RED.has(s)) return '🔴'
  if (YELLOW.has(s)) return '🟡'
  return '⚪'
}
