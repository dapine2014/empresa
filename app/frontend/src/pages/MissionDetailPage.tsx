import { useState } from 'react'
import { useNavigate, useParams } from 'react-router-dom'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { api } from '../api/client'
import { statusDot } from '../statusColor'
import DeleteMissionButton from '../components/DeleteMissionButton'
import type { AdjustTarget, DesignProperty, InvestorDecision, MissionResponse, RequirementsDecision, SandboxResult, SandboxStep, StaticCheck } from '../api/types'

function parseChecks(raw: string | null): StaticCheck[] {
  if (!raw) return []
  try {
    return JSON.parse(raw) as StaticCheck[]
  } catch {
    return []
  }
}

// Revisión final QA (M6): reporte de cobertura de escenarios que Java guarda en la tarea VALIDATION.
const COVERAGE_REPORT = 'Reporte de cobertura de escenarios (Java, QA casos de uso):'

function coverageLines(result: string | null): string[] {
  if (!result || !result.startsWith(COVERAGE_REPORT)) return []
  return result
    .slice(COVERAGE_REPORT.length)
    .split('\n')
    .map((line) => line.trim())
    .filter((line) => line.length > 0)
}

function parseSandbox(raw: string | null): SandboxResult | null {
  if (!raw) return null
  try {
    return JSON.parse(raw) as SandboxResult
  } catch {
    return null
  }
}

function sandboxDot(step: SandboxStep): string {
  if (step.status === 'PASS') return '🟢'
  if (step.status === 'SKIPPED') return '⚪'
  return '🔴'
}

const DECIDABLE = new Set(['AWAITING_INVESTOR', 'FAILED', 'BLOCKED'])

/** Spec misiones durables §4: una BLOCKED/INTERRUPTED muestra su motivo en su propia sección (no dos veces). */
function showsRecovery(mission: MissionResponse) {
  return mission.status === 'INTERRUPTED' || mission.status === 'BLOCKED'
}

/** Spec misiones durables §4: motivo de una BLOCKED/INTERRUPTED y "Reanudar" para las INTERRUPTED. */
function MissionRecovery({ mission }: { mission: MissionResponse }) {
  const queryClient = useQueryClient()
  const resume = useMutation({
    mutationFn: () => api.resumeMission(mission.missionId),
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: ['missionDetails', mission.missionId] })
      void queryClient.invalidateQueries({ queryKey: ['missions'] })
    },
  })
  if (!showsRecovery(mission)) return null
  return (
    <section>
      <h2>{mission.status === 'BLOCKED' ? 'Bloqueada: espera tu indicación' : 'Interrumpida por un reinicio'}</h2>
      <pre className="mission-message">{mission.message}</pre>
      {mission.status === 'INTERRUPTED' && (
        <button type="button" disabled={resume.isPending} onClick={() => resume.mutate()}>
          Reanudar
        </button>
      )}
      {resume.isError && <p className="error">{(resume.error as Error).message}</p>}
    </section>
  )
}

const VERIFIABLE = new Set(['BLOCKED', 'FAILED', 'AWAITING_INVESTOR', 'INTERRUPTED'])

/**
 * Revisión 2026-10-06 §5 (manos humanas): un desarrollador corrigió el código e hizo commit; Forjai verifica el último
 * commit (chequeos, sandbox y escenarios) sin modelos.
 */
function MissionHumanVerify({ mission }: { mission: MissionResponse }) {
  const queryClient = useQueryClient()
  const verify = useMutation({
    mutationFn: () => api.verifyMission(mission.missionId),
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: ['missionDetails', mission.missionId] })
      void queryClient.invalidateQueries({ queryKey: ['missions'] })
    },
  })
  if (mission.teamId !== 'TEAM-DEVELOPMENT' || !VERIFIABLE.has(mission.status)) return null
  return (
    <div className="decision-form">
      <button type="button" disabled={verify.isPending} onClick={() => verify.mutate()}>
        Verificar (manos humanas)
      </button>
      <p className="hint">
        Para cuando un desarrollador corrigió el código en ~/forjai-products/{mission.missionId} e hizo commit. Verifica el
        último commit sin modelos; lo que no tenga commit no se verifica (si arranca la corrección automática, queda guardado
        en git stash).
      </p>
      {verify.isError && <p className="error">{(verify.error as Error).message}</p>}
    </div>
  )
}

/** Spec 2026-10-02 §4: migraciones de Diego y "Aplicar esquema" (404 = la misión no declara base). */
function MissionDatabase({ missionId }: { missionId: string }) {
  const queryClient = useQueryClient()
  const query = useQuery({
    queryKey: ['missionDatabase', missionId],
    queryFn: () => api.missionDatabase(missionId),
    retry: false,
  })
  const apply = useMutation({
    mutationFn: () => api.applyMissionDatabase(missionId),
    onSuccess: () => void queryClient.invalidateQueries({ queryKey: ['missionDatabase', missionId] }),
  })
  if (!query.data) {
    return null
  }
  return (
    <div className="decision-form">
      <h2>Base de datos</h2>
      {query.data.environments.length === 0 ? (
        <p>Sin conexión cargada (cárgala en Settings → Bases de datos)</p>
      ) : (
        <ul>
          {query.data.environments.map((e) => {
            const failed = query.data.environments.find((x) => x.state === 'FAILED')
            const versions = e.applied.length > 0 ? e.applied.map((v) => `V${v}`).join(', ') : 'sin migraciones nuevas'
            const text =
              e.state === 'APPLIED'
                ? `✅ ${versions}`
                : e.state === 'FAILED'
                  ? `❌ ${e.applied.length > 0 ? `aplicadas ${versions}, ` : ''}falló V${e.failedVersion}${e.detail ? `: ${e.detail}` : ''}`
                  : e.state === 'SKIPPED'
                    ? `— sin aplicar${failed ? `: falló ${failed.environment} en V${failed.failedVersion}` : ''}`
                    : '⏸ sin aplicar'
            return (
              <li key={e.connection}>
                {e.environment ?? '?'} ({e.connection}) {text}
              </li>
            )
          })}
        </ul>
      )}
      <ul>
        {query.data.migrations.map((m) => (
          <li key={m.version}>
            V{m.version} {m.description} — {m.state}
          </li>
        ))}
      </ul>
      {query.data.lastResult && <p className="hint">{query.data.lastResult}</p>}
      <button disabled={apply.isPending} onClick={() => apply.mutate()}>
        Aplicar esquema
      </button>
      {apply.isSuccess && <p className="hint">{apply.data.result}</p>}
      {apply.isError && <p className="error">{apply.error.message}</p>}
    </div>
  )
}

/** Costo real por misión (2026-10-04): tokens y US$ de las llamadas remotas (NVIDIA gratis; se paga Bedrock). */
function MissionModelCost({ missionId }: { missionId: string }) {
  const query = useQuery({
    queryKey: ['missionModelCost', missionId],
    queryFn: () => api.missionModelCost(missionId),
    refetchInterval: 15_000,
  })
  if (!query.data || query.data.calls === 0) {
    return null
  }
  return (
    <p className="hint">
      Costo de modelos: <strong>US$ {query.data.totalUsd.toFixed(2)}</strong> en {query.data.calls} llamadas
      {' — '}
      {query.data.rows
        .map((r) => `${r.provider}: ${r.calls} llamadas, ${r.input.toLocaleString()} tokens de entrada y ${r.output.toLocaleString()} de salida (US$ ${r.costUsd.toFixed(2)})`)
        .join('; ')}
    </p>
  )
}

const INCREMENT_READY = new Set(['AWAITING_INVESTOR', 'COMPLETED', 'FAILED'])

/** Spec 2026-10-03 Aria: requisitos de Aria, aprobación e incrementos (404 = la misión no tiene requisitos). */
function MissionRequirements({ missionId, status }: { missionId: string; status: string }) {
  const queryClient = useQueryClient()
  const [adjustment, setAdjustment] = useState('')
  const [target, setTarget] = useState<AdjustTarget>('ARIA')
  const designQuery = useQuery({
    queryKey: ['interactionDesign', missionId],
    queryFn: () => api.interactionDesign(missionId),
    retry: false,
    refetchInterval: 10_000,
  })
  // Solo un diseño en DRAFT se ajusta: uno aprobado, rechazado o reemplazado ya no admite ajuste a Kael.
  const hasDesign = !!designQuery.data?.design && designQuery.data.status === 'DRAFT'
  const effectiveTarget: AdjustTarget = hasDesign ? target : 'ARIA'
  const query = useQuery({
    queryKey: ['requirements', missionId],
    queryFn: () => api.requirements(missionId),
    retry: false,
    refetchInterval: 10_000,
  })
  const refresh = () => {
    void queryClient.invalidateQueries({ queryKey: ['requirements', missionId] })
    void queryClient.invalidateQueries({ queryKey: ['missionDetails', missionId] })
    void queryClient.invalidateQueries({ queryKey: ['interactionDesign', missionId] })
  }
  const decide = useMutation({
    mutationFn: (decision: RequirementsDecision) =>
      api.decideRequirements(missionId, decision, decision === 'ADJUST' ? adjustment : undefined, effectiveTarget),
    onSuccess: () => {
      setAdjustment('')
      refresh()
    },
  })
  const build = useMutation({
    mutationFn: (increment: number) => api.buildIncrement(missionId, increment),
    onSuccess: refresh,
  })
  const view = query.data
  if (!view || !view.spec) {
    return null
  }
  const spec = view.spec
  const nextIncrement = view.builtIncrement + 1
  const canBuild = view.approvedVersion != null && nextIncrement <= view.incrementsTotal && INCREMENT_READY.has(status)
  return (
    <div className="decision-form">
      <h2>Requisitos (Aria)</h2>
      <p>
        Versión {view.version} — {view.versionStatus}
        {view.approvedVersion != null && <> — incremento construido: {view.builtIncrement} de {view.incrementsTotal}</>}
      </p>
      {spec.experience && <p>Tipo: {spec.experience}</p>}
      <p>{spec.summary}</p>
      <h3>Roles</h3>
      <ul>
        {spec.roles.map((r) => (
          <li key={r.name}>
            <strong>{r.name}</strong> — {r.description}
          </li>
        ))}
      </ul>
      {spec.increments.map((inc) => (
        <div key={inc.number}>
          <h3>
            Incremento {inc.number}: {inc.goal}
          </h3>
          {spec.userStories
            .filter((s) => inc.userStoryIds.includes(s.id))
            .map((s) => (
              <div key={s.id}>
                <p>
                  <strong>{s.id}</strong> ({s.role}): quiero {s.iWant} para {s.soThat}
                </p>
                <ul>
                  {s.acceptanceCriteria.map((c) => (
                    <li key={c}>{c}</li>
                  ))}
                  {s.scenarios.map((e) => (
                    <li key={e.id}>
                      {e.id} {e.title}: DADO {e.given.join(' y ')} CUANDO {e.when.join(' y ')} ENTONCES{' '}
                      {e.then.join(' y ')}
                    </li>
                  ))}
                </ul>
              </div>
            ))}
        </div>
      ))}
      {spec.assumptions.length > 0 && <p>Supuestos: {spec.assumptions.join('; ')}</p>}
      {spec.outOfScope.length > 0 && <p>Fuera de alcance: {spec.outOfScope.join('; ')}</p>}
      {status === 'AWAITING_SPEC_APPROVAL' && (
        <div>
          <button disabled={decide.isPending} onClick={() => decide.mutate('APPROVE')}>
            Aprobar
          </button>{' '}
          <button disabled={decide.isPending} onClick={() => decide.mutate('REJECT')}>
            Rechazar
          </button>
          <label>
            Ajuste para{' '}
            <select value={effectiveTarget} onChange={(e) => setTarget(e.target.value as AdjustTarget)}>
              <option value="ARIA">Aria</option>
              {hasDesign && <option value="KAEL">Kael</option>}
            </select>
          </label>
          <textarea
            value={adjustment}
            onChange={(e) => setAdjustment(e.target.value)}
            placeholder={`Qué debe cambiar ${effectiveTarget === 'KAEL' ? 'Kael' : 'Aria'}`}
          />
          <button disabled={decide.isPending || !adjustment.trim()} onClick={() => decide.mutate('ADJUST')}>
            Ajustar
          </button>
        </div>
      )}
      {canBuild && (
        <button disabled={build.isPending} onClick={() => build.mutate(nextIncrement)}>
          Construir incremento {nextIncrement}
        </button>
      )}
      {decide.isError && <p className="error">{decide.error.message}</p>}
      {build.isError && <p className="error">{build.error.message}</p>}
    </div>
  )
}

/** Spec 2026-10-14 Kael: diseño de interacción (pantallas y flujos, o reglas del juego); 404 = sin diseño. */
function MissionDesign({ missionId }: { missionId: string }) {
  const query = useQuery({
    queryKey: ['interactionDesign', missionId],
    queryFn: () => api.interactionDesign(missionId),
    retry: false,
    refetchInterval: 10_000,
  })
  const view = query.data
  if (!view?.design) {
    return null
  }
  const { app, game } = view.design
  const props = (list: DesignProperty[]) => list.map((p) => `${p.name}=${p.value ?? '—'}`).join(', ')
  return (
    <div className="decision-form">
      <h2>Diseño (Kael)</h2>
      <p>
        Versión {view.version} — {view.status}
      </p>
      {app && (
        <>
          <h3>Pantallas</h3>
          <ul>
            {app.screens.map((s) => (
              <li key={s.id}>
                {s.id} {s.name} ({s.role}): {s.purpose} — {s.states.join(', ')}
              </li>
            ))}
          </ul>
          <h3>Flujos</h3>
          <ul>
            {app.flows.map((f) => (
              <li key={f.story}>
                {f.story}: {f.screens.join(' → ')}
              </li>
            ))}
          </ul>
          {app.frictionRisks.length > 0 && (
            <>
              <h3>Hipótesis</h3>
              <ul>
                {app.frictionRisks.map((r) => (
                  <li key={r}>{r}</li>
                ))}
              </ul>
            </>
          )}
        </>
      )}
      {game && (
        <>
          <h3>Estados</h3>
          <p>{game.states.join(', ')}</p>
          <ul>
            {game.transitions.map((t) => (
              <li key={`${t.from}-${t.to}-${t.trigger}`}>
                {t.from} → {t.to} ({t.trigger})
              </li>
            ))}
          </ul>
          <h3>Controles</h3>
          <ul>
            {game.controls.map((c) => (
              <li key={c.action}>
                {c.action} [{c.key}]: {c.effect}
              </li>
            ))}
          </ul>
          <h3>Entidades</h3>
          <ul>
            {game.entities.map((e) => (
              <li key={e.id}>
                {e.name}: {props(e.properties)}
              </li>
            ))}
          </ul>
          <h3>Reglas</h3>
          <ul>
            {game.rules.map((r) => (
              <li key={r.id}>
                {r.id}: SI {r.condition} ENTONCES {r.effect}
              </li>
            ))}
          </ul>
          <p>Gana: {game.win.join('; ')}</p>
          <p>Pierde: {game.lose.join('; ')}</p>
          {game.difficulty.length > 0 && <p>Dificultad: {props(game.difficulty)}</p>}
          {game.balanceHypotheses.length > 0 && <p>Hipótesis de balance: {game.balanceHypotheses.join('; ')}</p>}
        </>
      )}
    </div>
  )
}

export default function MissionDetailPage() {
  const { missionId } = useParams<{ missionId: string }>()
  const queryClient = useQueryClient()
  const navigate = useNavigate()
  const [reasoning, setReasoning] = useState('')
  const [decision, setDecision] = useState<InvestorDecision>('APPROVE')
  const [feedback, setFeedback] = useState<string | null>(null)

  const { data, isLoading, error } = useQuery({
    queryKey: ['missionDetails', missionId],
    queryFn: () => api.missionDetails(missionId!),
    enabled: !!missionId,
    refetchInterval: 5_000,
  })

  const netProfitQuery = useQuery({
    queryKey: ['netProfit', missionId],
    queryFn: () => api.netProfit(missionId!),
    enabled: !!missionId,
  })

  const mutation = useMutation({
    mutationFn: () => api.recordDecision(missionId!, { decision, reasoning }),
    onSuccess: (response) => {
      setFeedback(`Decisión registrada: ${response.decision} sobre ${response.missionId}.`)
      setReasoning('')
      queryClient.invalidateQueries({ queryKey: ['missionDetails', missionId] })
      queryClient.invalidateQueries({ queryKey: ['missions'] })
    },
    onError: () => setFeedback('No se pudo registrar la decisión (¿la misión sigue en curso?).'),
  })

  if (isLoading) return <p>Cargando misión...</p>
  if (error || !data) return <p className="error">No se encontró la misión {missionId}.</p>

  const { mission, tasks } = data
  const canDecide = DECIDABLE.has(mission.status)

  return (
    <div>
      <h1>{mission.missionId}</h1>
      <p>
        {statusDot(mission.status)} <strong>{mission.status}</strong> — {mission.currentStep} ({mission.progress}%)
        {' — '}
        {mission.environment === 'PRODUCTION' ? '🏢 PRODUCTION' : '🧪 TEST'}
        {mission.teamId && <>{' — '}👥 {mission.teamId}</>}
      </p>
      {!showsRecovery(mission) && <p className="mission-message">{mission.message}</p>}
      <DeleteMissionButton
        missionId={mission.missionId}
        status={mission.status}
        onDeleted={() => navigate('/missions')}
      />

      <MissionRecovery mission={mission} />
      <MissionHumanVerify mission={mission} />

      <MissionModelCost missionId={mission.missionId} />

      {mission.teamId && <MissionRequirements missionId={mission.missionId} status={mission.status} />}
      {mission.teamId && <MissionDesign missionId={mission.missionId} />}

      {mission.teamId && <MissionDatabase missionId={mission.missionId} />}

      {mission.financialCriteria && (
        <div className="financial-criteria">
          <h2>Objetivo financiero</h2>
          <p>
            {mission.financialCriteria.metric} ≥ {mission.financialCriteria.targetAmount} {mission.financialCriteria.currency}
            {mission.financialCriteria.deadline ? ` para ${mission.financialCriteria.deadline}` : ' (sin plazo definido)'}
          </p>
          {netProfitQuery.data?.financialCriteriaEvaluation && (
            <p>
              Resultado real: {netProfitQuery.data.netProfitUsd.toFixed(2)} {mission.financialCriteria.currency} (
              {netProfitQuery.data.financialCriteriaEvaluation.progressPct.toFixed(1)}% del objetivo) —{' '}
              {netProfitQuery.data.financialCriteriaEvaluation.criterionMet ? 'cumplido' : 'no cumplido todavía'}
            </p>
          )}
        </div>
      )}

      <h2>Tareas por agente</h2>
      <table className="data-table">
        <thead>
          <tr>
            <th>Agente</th>
            <th>Tipo</th>
            <th>Acción</th>
            <th>Estado</th>
            <th>Commit</th>
            <th>Archivos</th>
            <th>Actualizada</th>
          </tr>
        </thead>
        <tbody>
          {tasks.map((task) => (
            <tr key={task.taskId}>
              <td>{task.agentId.toUpperCase()}</td>
              <td>{task.kind ?? '—'}</td>
              <td>{task.action}</td>
              <td>
                {statusDot(task.status)} {task.status}
              </td>
              <td>{task.commitSha ? <code>{task.commitSha.slice(0, 7)}</code> : '—'}</td>
              <td>{task.files && task.files.length > 0 ? task.files.join(', ') : '—'}</td>
              <td>{new Date(task.updatedAt).toLocaleString()}</td>
            </tr>
          ))}
        </tbody>
      </table>

      {tasks
        .filter((task) => task.validationStatus)
        .map((task) => (
          <section key={`${task.taskId}-validation`}>
            <h2>Validación</h2>
            <p>
              {statusDot(task.validationStatus ?? '')} <strong>{task.validationStatus}</strong> — revisada por{' '}
              {task.agentId.toUpperCase()}.
            </p>
            <ul>
              {parseChecks(task.staticChecks).map((check, index) => (
                <li key={`${check.check}-${index}`}>
                  {check.status === 'PASS' ? '🟢' : '🔴'} {check.check}: {check.detail}
                </li>
              ))}
            </ul>
            {(() => {
              const lines = coverageLines(task.result)
              if (lines.length === 0) return null
              const coverage = parseChecks(task.staticChecks).find((check) => check.check === 'SCENARIO_COVERAGE')
              return (
                <>
                  <h3>Cobertura de escenarios</h3>
                  {coverage && (
                    <p>
                      {coverage.status === 'PASS' ? '🟢' : '🔴'} {coverage.detail}
                    </p>
                  )}
                  <ul>
                    {lines.map((line) => (
                      <li key={line}>{line}</li>
                    ))}
                  </ul>
                </>
              )
            })()}
            {(() => {
              const sandbox = parseSandbox(task.sandboxResult)
              if (!sandbox) return <p className="hint">El sandbox no corrió: la ejecución no está verificada.</p>
              return (
                <>
                  <h3>Sandbox</h3>
                  <ul>
                    {sandbox.steps.map((step) => (
                      <li key={step.name}>
                        {sandboxDot(step)} {step.name}: {step.status} ({Math.round(step.durationMs / 1000)} s)
                        {(step.name === 'test' || step.name.endsWith('/test')) && ` — ${step.testsPassed}/${step.testsPassed + step.testsFailed} tests`}
                        {(step.status === 'FAIL' || step.status === 'TIMEOUT') && step.outputTail && (
                          <pre className="mission-message">{step.outputTail.slice(-1500)}</pre>
                        )}
                      </li>
                    ))}
                  </ul>
                </>
              )
            })()}
          </section>
        ))}

      <h2>Decisión del inversionista</h2>
      {!canDecide && (
        <p className="hint">
          Solo se puede decidir sobre una misión en AWAITING_INVESTOR, FAILED o BLOCKED (estado actual: {mission.status}).
        </p>
      )}
      {canDecide && (
        <form
          className="decision-form"
          onSubmit={(e) => {
            e.preventDefault()
            mutation.mutate()
          }}
        >
          <label>
            Decisión
            <select value={decision} onChange={(e) => setDecision(e.target.value as InvestorDecision)}>
              <option value="APPROVE">Aprobar</option>
              <option value="REJECT">Rechazar</option>
              <option value="REQUEST_MORE_EVIDENCE">Pedir más evidencia</option>
            </select>
          </label>
          <label>
            Razón (obligatoria)
            <textarea
              required
              value={reasoning}
              onChange={(e) => setReasoning(e.target.value)}
              rows={3}
            />
          </label>
          <button type="submit" disabled={mutation.isPending || !reasoning.trim()}>
            {mutation.isPending ? 'Registrando...' : 'Registrar decisión'}
          </button>
        </form>
      )}
      {feedback && <p className="feedback">{feedback}</p>}
    </div>
  )
}
