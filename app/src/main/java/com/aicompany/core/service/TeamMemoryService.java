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
import java.util.Optional;
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

    public static final String TEAM_DEVELOPMENT = "TEAM-DEVELOPMENT";
    /** Id anterior del equipo de desarrollo; solo lo usa la migración de arranque. */
    static final String LEGACY_TEAM_ENGINEERING = "TEAM-ENGINEERING";
    public static final String TEAM_CREATIVE_PRODUCT_INTELLIGENCE = "TEAM-CREATIVE-PRODUCT-INTELLIGENCE";
    public static final String TEAM_MARKETING_GROWTH = "TEAM-MARKETING-GROWTH";

    public static final Set<String> KNOWN_TEAM_IDS = Set.of(
            TEAM_DEVELOPMENT, TEAM_CREATIVE_PRODUCT_INTELLIGENCE, TEAM_MARKETING_GROWTH
    );

    /** Tipo real del equipo desde el catálogo fijo en código ({@link #TEAMS}); vacío si el id no existe. */
    public static Optional<String> teamType(String teamId) {
        return TEAMS.stream()
                .filter(team -> team.teamId().equals(teamId))
                .map(TeamDefinition::teamType)
                .findFirst();
    }

    /** Todas las capabilities del catálogo fijo (para verificar que sean atómicas). */
    static List<String> allCapabilities() {
        return TEAMS.stream()
                .flatMap(team -> team.roles().stream())
                .flatMap(role -> role.capabilities().stream())
                .toList();
    }

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

            new TeamDefinition(TEAM_DEVELOPMENT, "Development Group", "DEVELOPMENT", "engineering", List.of(
                    new RoleDefinition("product-owner", "PRODUCT_OWNER", List.of(
                            "requirements", "user-stories", "bdd", "acceptance-criteria", "backlog")),
                    new RoleDefinition("engineering", "TECH_LEAD", List.of(
                            "architecture", "planning", "technical-review", "integration")),
                    new RoleDefinition("backend", "BACKEND", List.of(
                            "backend", "api", "csharp", "dotnet", "business-logic", "integrations", "authentication")),
                    new RoleDefinition("devops", "DATA_ARCHITECT", List.of(
                            "postgresql", "data-modeling", "persistence", "migrations", "ef-core")),
                    new RoleDefinition("frontend-ui", "UI_UX", List.of(
                            "flutter", "dart", "ui", "ux", "design-system", "web-ui", "game-ui")),
                    new RoleDefinition("interaction-design", "GAME_DEV", List.of(
                            "godot", "csharp", "gameplay", "game-loop", "physics")),
                    new RoleDefinition("specialist-3d", "SPECIALIST_3D", List.of(
                            "blender", "3d-modeling", "rigging", "gltf")),
                    new RoleDefinition("product", "CREATIVE", List.of(
                            "2d-art", "textures", "audio", "sfx")),
                    new RoleDefinition("qa", "QA", List.of(
                            "qa", "bdd", "tests", "regression", "code-review")),
                    new RoleDefinition("delivery", "DEVOPS", List.of(
                            "ci-cd", "docker", "aws", "packaging"))
            )),

            // Kael pasó al Development Group (spec 2026-10-01 §1): Creative lo lidera Maya.
            new TeamDefinition(TEAM_CREATIVE_PRODUCT_INTELLIGENCE, "Creative / Product Intelligence",
                    "CREATIVE_PRODUCT_INTELLIGENCE", "visual-design", List.of(
                    new RoleDefinition("visual-design", "VISUAL_ASSET_DIRECTOR", List.of(
                            "identidad visual", "dirección artística", "branding", "ilustraciones",
                            "assets de marketing", "assets 2D", "assets 3D", "sprites", "animaciones", "iluminación",
                            "consistencia visual", "dirección visual")),
                    new RoleDefinition("telemetry", "TELEMETRY_ANALYTICS", List.of(
                            "análisis de producto", "funnels de conversión", "activación", "retención",
                            "churn", "comportamiento de usuarios", "métricas de sesión",
                            "telemetría de videojuegos", "análisis de gameplay", "análisis de monetización",
                            "experimentación", "insights de datos"))
            )),

            new TeamDefinition(TEAM_MARKETING_GROWTH, "Marketing & Growth",
                    "MARKETING_GROWTH", "growth-content", List.of(
                    new RoleDefinition("growth-content", "GROWTH_CONTENT_COMMUNITY", List.of(
                            "growth", "marketing de contenidos", "SEO", "adquisición orgánica",
                            "Product-Led Growth", "newsletters", "redes sociales", "devlogs", "campañas",
                            "estrategia de adquisición", "construcción de audiencia", "iniciativas de comunidad")),
                    new RoleDefinition("community", "COMMUNITY_MANAGER", List.of(
                            "gestión de comunidades", "interacción con usuarios", "moderación",
                            "Discord", "redes sociales", "recopilación de feedback",
                            "comunicación con comunidad", "eventos comunitarios",
                            "necesidades de usuarios", "escalamiento de feedback"))
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
                migrateEngineeringTeam(tx);
                for (var team : TEAMS) {
                    ensureTeam(tx, team);
                }
                removeStaleMemberships(tx);
                return null;
            });
        }
    }

    /**
     * Spec 2026-10-01 §1: TEAM-ENGINEERING pasa a llamarse TEAM-DEVELOPMENT. Idempotente: si el equipo nuevo
     * ya existe no hace nada; las misiones viejas pasan a apuntar al id nuevo.
     */
    private void migrateEngineeringTeam(TransactionContext tx) {
        tx.run("MATCH (old:Team {id:$legacy}) WHERE NOT EXISTS { MATCH (:Team {id:$current}) } "
                        + "SET old.id = $current",
                Map.of("legacy", LEGACY_TEAM_ENGINEERING, "current", TEAM_DEVELOPMENT));
        tx.run("MATCH (m:Mission {teamId:$legacy}) SET m.teamId = $current",
                Map.of("legacy", LEGACY_TEAM_ENGINEERING, "current", TEAM_DEVELOPMENT));
    }

    /**
     * Un agente que cambió de equipo (Kael, de Creative a Development) conserva MEMBER_OF/LEADS viejos porque
     * ensureTeam solo hace MERGE. Se borran las relaciones con equipos del catálogo que el catálogo ya no declara.
     */
    private void removeStaleMemberships(TransactionContext tx) {
        var members = new ArrayList<String>();
        var leaders = new ArrayList<String>();
        for (var team : TEAMS) {
            team.roles().forEach(role -> members.add(role.agentId() + "|" + team.teamId()));
            leaders.add(team.leaderAgentId() + "|" + team.teamId());
        }
        tx.run("MATCH (a:Agent)-[r:MEMBER_OF]->(t:Team) WHERE t.id IN $teams AND NOT (a.id + '|' + t.id) IN $pairs "
                        + "DELETE r",
                Map.of("teams", new ArrayList<>(KNOWN_TEAM_IDS), "pairs", members));
        tx.run("MATCH (a:Agent)-[r:LEADS]->(t:Team) WHERE t.id IN $teams AND NOT (a.id + '|' + t.id) IN $pairs "
                        + "DELETE r",
                Map.of("teams", new ArrayList<>(KNOWN_TEAM_IDS), "pairs", leaders));
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
