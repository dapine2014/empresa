import type { OrchestratorStatus } from './api/types'

// Mismas etiquetas en Productos y en el Dashboard (modo automático).
export const ORCHESTRATOR_LABELS: Record<OrchestratorStatus, string> = {
  CHOOSING: 'Eligiendo qué construir',
  DISCOVERING: 'Buscando ideas (discovery)',
  PROPOSING: 'Completando la ficha',
  BUILDING: 'Construyendo',
  READY: 'Listo para vender',
  FAILED: 'Falló',
  STOPPED: 'Detenido',
}
