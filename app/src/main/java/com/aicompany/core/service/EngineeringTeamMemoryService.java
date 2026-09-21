package com.aicompany.core.service;

import com.aicompany.core.model.EngineeringTeamSnapshot;
import com.aicompany.core.model.TeamMemberInfo;
import org.neo4j.driver.Driver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Primera unidad organizativa persistente de Forjai — una capa aparte
 * sobre los {@code Agent} ya creados por {@link CompanyMemoryService}
 * (no duplica esa lógica de identidad, solo agrega la estructura de
 * equipo: {@code Team}, membresía, liderazgo, y los campos específicos
 * de rol de ingeniería). Ver
 * docs/superpowers/specs/2026-09-20-engineering-team-design.md.
 *
 * <p>Crear el equipo nunca cambia {@code Agent.status} ni crea ninguna
 * {@code AgentTask} — es estructura organizativa, no ejecución (esa
 * distinción es una regla dura del spec).
 */
@Service
public class EngineeringTeamMemoryService {

    private static final Logger log =
            LoggerFactory.getLogger(EngineeringTeamMemoryService.class);

    private static final String TEAM_ID = "TEAM-ENGINEERING";
    private static final String LEADER_AGENT_ID = "engineering";

    private record RoleDefinition(
            String agentId,
            String roleCode,
            List<String> capabilities) {
    }

    private static final List<RoleDefinition> ROLES = List.of(
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
    );

    private final Driver driver;

    public EngineeringTeamMemoryService(Driver driver) {
        this.driver = driver;
    }

    /**
     * Idempotente: {@code MERGE} del {@code Team}, {@code SET} de
     * {@code roleCode}/{@code capabilities} sobre cada {@code Agent} ya
     * existente (nunca {@code MERGE} de Agent -- esos ya los crea
     * {@link CompanyMemoryService#initializeCompanyAndAgents()}), y
     * {@code MERGE} de {@code MEMBER_OF}/{@code LEADS}. Llamado desde
     * {@code CompanyMemoryInitializer} después de que los agentes ya
     * existan.
     */
    public void ensureEngineeringTeam() {
        try (var session = driver.session()) {
            session.executeWrite(tx -> {

                tx.run("MERGE (t:Team {id:$id}) SET t.name=$name, t.type=$type, t.status=$status",
                        Map.of("id", TEAM_ID, "name", "Engineering Team",
                                "type", "ENGINEERING", "status", "ACTIVE"));

                for (var role : ROLES) {
                    var result = tx.run("MATCH (a:Agent {id:$agentId}), (t:Team {id:$teamId}) "
                                    + "SET a.roleCode=$roleCode, a.capabilities=$capabilities "
                                    + "MERGE (a)-[:MEMBER_OF]->(t)",
                            Map.of("agentId", role.agentId(), "teamId", TEAM_ID,
                                    "roleCode", role.roleCode(), "capabilities", role.capabilities()));

                    // SET corre siempre, en cada arranque, sin importar si
                    // MEMBER_OF ya existía -- a diferencia de un MERGE de
                    // relación (idempotente, "no creado" es normal en un
                    // restart), propertiesSet()==0 acá solo puede significar
                    // que el MATCH de Agent no encontró nada: señal
                    // confiable de un agentId hardcodeado en ROLES que dejó
                    // de existir, sin falsos positivos en restarts normales.
                    if (result.consume().counters().propertiesSet() == 0) {
                        log.warn("ENGINEERING_TEAM_MEMBER_NOT_FOUND agentId={} — no se pudo agregar al Engineering Team",
                                role.agentId());
                    }
                }

                // Nota: no se agrega un chequeo equivalente para la relación
                // LEADS -- es un MERGE puro (sin SET), así que
                // relationshipsCreated()==0 es el camino NORMAL en cualquier
                // arranque posterior al primero (la relación ya existe), no
                // una señal de que LEADER_AGENT_ID no matcheó ningún Agent.
                // Distinguir ambos casos exigiría un MATCH de solo lectura
                // aparte; se prefiere no agregar un WARN que podría generar
                // falsos positivos en cada restart normal.
                tx.run("MATCH (a:Agent {id:$leaderId}), (t:Team {id:$teamId}) MERGE (a)-[:LEADS]->(t)",
                        Map.of("leaderId", LEADER_AGENT_ID, "teamId", TEAM_ID));

                return null;
            });
        }
    }

    /**
     * Lectura real para el Company Chat — nunca inventa un miembro, rol,
     * capability o líder que no esté en Company Memory. Devuelve
     * {@code members} vacío (nunca {@code null}) si el Team todavía no
     * existe (defensivo; en la práctica {@link #ensureEngineeringTeam()}
     * ya corrió al arrancar).
     */
    public EngineeringTeamSnapshot snapshot() {
        try (var session = driver.session()) {

            var rows = session.run(
                    "MATCH (a:Agent)-[:MEMBER_OF]->(t:Team {id:$teamId}) "
                            + "OPTIONAL MATCH (a)-[leads:LEADS]->(t) "
                            + "RETURN t.id AS teamId, t.name AS teamName, t.status AS teamStatus, "
                            + "a.id AS agentId, a.name AS name, a.role AS role, a.roleCode AS roleCode, "
                            + "a.capabilities AS capabilities, a.model AS model, "
                            + "leads IS NOT NULL AS isLeader "
                            + "ORDER BY a.id",
                    Map.of("teamId", TEAM_ID)
            ).list();

            if (rows.isEmpty()) {
                return new EngineeringTeamSnapshot(TEAM_ID, null, null, null, List.of());
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

            return new EngineeringTeamSnapshot(
                    first.get("teamId").asString(),
                    first.get("teamName").asString(),
                    first.get("teamStatus").asString(),
                    leaderAgentId,
                    members
            );
        }
    }
}
