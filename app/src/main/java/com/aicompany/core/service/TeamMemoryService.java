package com.aicompany.core.service;

import com.aicompany.core.model.TeamMemberInfo;
import com.aicompany.core.model.TeamSnapshot;
import org.neo4j.driver.Driver;
import org.neo4j.driver.TransactionContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Unidades organizativas persistentes de Forjai — una capa aparte sobre
 * los {@code Agent} ya creados por {@link CompanyMemoryService} (no
 * duplica esa lógica de identidad, solo agrega la estructura de equipo:
 * {@code Team}, membresía, liderazgo, y los campos específicos de rol
 * de cada agente). Generaliza el servicio original de un solo equipo
 * (Engineering) a los 3 equipos reales de Forjai. Ver
 * docs/superpowers/specs/2026-09-20-engineering-team-design.md (diseño
 * original de Engineering, sin cambios de contenido) y
 * docs/superpowers/specs/2026-09-21-creative-marketing-teams-design.md
 * (generalización + los 2 equipos nuevos).
 *
 * <p>Crear/asegurar un equipo nunca cambia {@code Agent.status} ni crea
 * ninguna {@code AgentTask} — es estructura organizativa, no ejecución
 * (regla dura heredada del spec original, sin cambios).
 */
@Service
public class TeamMemoryService {

    private static final Logger log =
            LoggerFactory.getLogger(TeamMemoryService.class);

    public static final String TEAM_ENGINEERING = "TEAM-ENGINEERING";
    public static final String TEAM_CREATIVE_PRODUCT_INTELLIGENCE = "TEAM-CREATIVE-PRODUCT-INTELLIGENCE";
    public static final String TEAM_MARKETING_GROWTH = "TEAM-MARKETING-GROWTH";

    public static final Set<String> KNOWN_TEAM_IDS = Set.of(
            TEAM_ENGINEERING, TEAM_CREATIVE_PRODUCT_INTELLIGENCE, TEAM_MARKETING_GROWTH
    );

    private record RoleDefinition(
            String agentId,
            String roleCode,
            List<String> capabilities) {
    }

    private record TeamDefinition(
            String teamId,
            String teamName,
            String teamType,
            String leaderAgentId,
            List<RoleDefinition> roles) {
    }

    private static final List<TeamDefinition> TEAMS = List.of(

            new TeamDefinition(TEAM_ENGINEERING, "Engineering Team", "ENGINEERING", "engineering", List.of(
                    new RoleDefinition("engineering", "CLOUD_ARCHITECT_LEAD_BACKEND", List.of(
                            "arquitectura de soluciones", "arquitectura cloud AWS", "arquitectura backend",
                            "decisiones técnicas", "diseño de sistemas",
                            "diseño de arquitectura de videojuegos y aplicaciones cuando aplique",
                            "descomposición técnica del trabajo", "liderazgo técnico", "revisión técnica",
                            "coordinación del Engineering Team", "AWS", "bases de datos SQL",
                            "bases de datos NoSQL", "C#", "Java", "JavaScript / TypeScript", "Flutter"
                    )),
                    new RoleDefinition("qa", "QA_CLOUD_PERFORMANCE_ENGINEER", List.of(
                            "QA", "pruebas funcionales", "pruebas de integración", "pruebas de regresión",
                            "pruebas de rendimiento", "pruebas de carga", "validación de estabilidad",
                            "análisis de errores", "playtesting cuando corresponda",
                            "validación de performance", "validación de calidad"
                    )),
                    new RoleDefinition("devops", "CLOUD_DB_SRE_DEVOPS", List.of(
                            "PostgreSQL", "Redis", "bases de datos SQL", "bases de datos NoSQL",
                            "bases de datos cloud", "infraestructura", "SRE", "observabilidad",
                            "rendimiento", "backups", "recuperación", "Terraform / Pulumi",
                            "operación cloud AWS", "capacidades de infraestructura cuando el Proyecto B esté implementado"
                    )),
                    new RoleDefinition("backend", "DEV_BACKEND_INTEGRATIONS", List.of(
                            "backend", "APIs", "integraciones", "microservicios", "lógica de negocio",
                            "servicios backend", "integraciones con terceros",
                            "componentes backend para aplicaciones y videojuegos",
                            "C#", "Java", "JavaScript / TypeScript", "Flutter"
                    )),
                    new RoleDefinition("frontend-ui", "FRONTEND_GAME_UI_SPECIALIST", List.of(
                            "frontend", "interfaces web", "UI", "UX técnica", "Game UI", "HUD", "menús",
                            "interfaces de aplicaciones y videojuegos",
                            "C#", "Java", "JavaScript / TypeScript", "Flutter"
                    ))
            )),

            new TeamDefinition(TEAM_CREATIVE_PRODUCT_INTELLIGENCE, "Creative / Product Intelligence",
                    "CREATIVE_PRODUCT_INTELLIGENCE", "interaction-design", List.of(
                    new RoleDefinition("interaction-design", "INTERACTIVE_LOGIC_PRODUCT_DESIGNER", List.of(
                            "UX y arquitectura de interacción", "flujos de usuario", "sistemas de gamificación",
                            "engagement y retención", "game design", "game loop", "reglas y mecánicas",
                            "curva de aprendizaje", "balance de gameplay",
                            "economía interna de productos interactivos"
                    )),
                    new RoleDefinition("visual-design", "VISUAL_ASSET_DIRECTOR", List.of(
                            "identidad visual", "dirección artística", "branding", "ilustraciones",
                            "assets de marketing", "assets 2D/3D", "sprites", "animaciones", "iluminación",
                            "consistencia visual del producto", "dirección visual para videojuegos y aplicaciones"
                    )),
                    new RoleDefinition("telemetry", "TELEMETRY_ANALYTICS", List.of(
                            "análisis de producto", "funnels de conversión", "activación", "retención",
                            "churn", "comportamiento de usuarios", "métricas de sesión",
                            "telemetría de videojuegos", "análisis de gameplay", "análisis de monetización",
                            "experimentación", "generación de insights y recomendaciones basadas en datos"
                    ))
            )),

            new TeamDefinition(TEAM_MARKETING_GROWTH, "Marketing & Growth",
                    "MARKETING_GROWTH", "growth-content", List.of(
                    new RoleDefinition("growth-content", "GROWTH_CONTENT_COMMUNITY", List.of(
                            "growth", "marketing de contenidos", "SEO", "adquisición orgánica",
                            "Product-Led Growth", "newsletters", "redes sociales", "devlogs", "campañas",
                            "estrategia de adquisición", "construcción de audiencia",
                            "coordinación de iniciativas de comunidad"
                    )),
                    new RoleDefinition("community", "COMMUNITY_MANAGER", List.of(
                            "gestión diaria de comunidades", "interacción con usuarios", "moderación",
                            "Discord", "redes sociales", "recopilación de feedback",
                            "comunicación con la comunidad", "eventos y actividades",
                            "identificación de necesidades y problemas de usuarios",
                            "escalamiento de feedback relevante hacia Product, Growth y CEO"
                    ))
            ))
    );

    private final Driver driver;

    public TeamMemoryService(Driver driver) {
        this.driver = driver;
    }

    /**
     * Idempotente: por cada uno de los 3 {@link TeamDefinition}, {@code
     * MERGE} del {@code Team}, {@code SET} de
     * {@code roleCode}/{@code capabilities} sobre cada {@code Agent} ya
     * existente (nunca {@code MERGE} de Agent -- esos ya los crea
     * {@link CompanyMemoryService#initializeCompanyAndAgents()}), y
     * {@code MERGE} de {@code MEMBER_OF}/{@code LEADS}. Llamado desde
     * {@code CompanyMemoryInitializer} después de que los agentes ya
     * existan.
     */
    public void ensureAllTeams() {
        try (var session = driver.session()) {
            session.executeWrite(tx -> {
                for (var team : TEAMS) {
                    ensureTeam(tx, team);
                }
                return null;
            });
        }
    }

    private void ensureTeam(TransactionContext tx, TeamDefinition team) {

        tx.run("MERGE (t:Team {id:$id}) SET t.name=$name, t.type=$type, t.status=$status",
                Map.of("id", team.teamId(), "name", team.teamName(),
                        "type", team.teamType(), "status", "ACTIVE"));

        for (var role : team.roles()) {
            var result = tx.run("MATCH (a:Agent {id:$agentId}), (t:Team {id:$teamId}) "
                            + "SET a.roleCode=$roleCode, a.capabilities=$capabilities "
                            + "MERGE (a)-[:MEMBER_OF]->(t)",
                    Map.of("agentId", role.agentId(), "teamId", team.teamId(),
                            "roleCode", role.roleCode(), "capabilities", role.capabilities()));

            // SET corre siempre, en cada arranque, sin importar si
            // MEMBER_OF ya existía -- a diferencia de un MERGE de
            // relación (idempotente, "no creado" es normal en un
            // restart), propertiesSet()==0 acá solo puede significar
            // que el MATCH de Agent no encontró nada: señal confiable
            // de un agentId hardcodeado en ROLES que dejó de existir,
            // sin falsos positivos en restarts normales.
            if (result.consume().counters().propertiesSet() == 0) {
                log.warn("TEAM_MEMBER_NOT_FOUND teamId={} agentId={} — no se pudo agregar al equipo",
                        team.teamId(), role.agentId());
            }
        }

        // Nota: no se agrega un chequeo equivalente para LEADS -- es un
        // MERGE puro (sin SET), así que relationshipsCreated()==0 es el
        // camino NORMAL en cualquier arranque posterior al primero (la
        // relación ya existe), no una señal de que leaderAgentId no
        // matcheó ningún Agent. Mismo criterio ya usado en el servicio
        // original de Engineering.
        tx.run("MATCH (a:Agent {id:$leaderId}), (t:Team {id:$teamId}) MERGE (a)-[:LEADS]->(t)",
                Map.of("leaderId", team.leaderAgentId(), "teamId", team.teamId()));
    }

    /**
     * Los 3 equipos reales, en el mismo orden fijo de {@link #TEAMS}
     * (Engineering, Creative/Product Intelligence, Marketing & Growth)
     * — para el organigrama del Command Center web
     * ({@code GET /api/company/teams}). Reusa {@link #snapshot(String)},
     * mismo criterio de "nunca inventar" (un equipo sin agentes reales
     * todavía sale con {@code members} vacío, no se omite).
     */
    public List<TeamSnapshot> snapshotAll() {
        return TEAMS.stream()
                .map(team -> snapshot(team.teamId()))
                .toList();
    }

    /**
     * Lectura real para el Company Chat — nunca inventa un miembro, rol,
     * capability o líder que no esté en Company Memory. Devuelve
     * {@code members} vacío (nunca {@code null}) si el {@code teamId}
     * todavía no existe o no es uno de los 3 conocidos.
     */
    public TeamSnapshot snapshot(String teamId) {
        try (var session = driver.session()) {

            var rows = session.run(
                    "MATCH (a:Agent)-[:MEMBER_OF]->(t:Team {id:$teamId}) "
                            + "OPTIONAL MATCH (a)-[leads:LEADS]->(t) "
                            + "RETURN t.id AS teamId, t.name AS teamName, t.status AS teamStatus, "
                            + "a.id AS agentId, a.name AS name, a.role AS role, a.roleCode AS roleCode, "
                            + "a.capabilities AS capabilities, a.model AS model, "
                            + "leads IS NOT NULL AS isLeader "
                            + "ORDER BY a.id",
                    Map.of("teamId", teamId)
            ).list();

            if (rows.isEmpty()) {
                return new TeamSnapshot(teamId, null, null, null, List.of());
            }

            var members = new ArrayList<TeamMemberInfo>();
            String leaderAgentId = null;

            for (var row : rows) {

                var agentId = row.get("agentId").asString();

                members.add(new TeamMemberInfo(
                        agentId,
                        row.get("name").asString(),
                        row.get("role").asString(),
                        row.get("roleCode").isNull() ? null : row.get("roleCode").asString(),
                        row.get("capabilities").isNull()
                                ? List.of()
                                : row.get("capabilities").asList(v -> v.asString()),
                        row.get("model").isNull() ? null : row.get("model").asString()
                ));

                if (row.get("isLeader").asBoolean()) {
                    leaderAgentId = agentId;
                }
            }

            var first = rows.get(0);

            return new TeamSnapshot(
                    first.get("teamId").asString(),
                    first.get("teamName").asString(),
                    first.get("teamStatus").asString(),
                    leaderAgentId,
                    members
            );
        }
    }
}
