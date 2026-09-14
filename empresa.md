from pathlib import Path

content = """# Forjai — Documento Fundacional

**Versión:** 0.1  
**Estado:** Conceptualización / definición del MVP  
**Propósito:** Mantener el foco estratégico, funcional y técnico del proyecto.

---

## 1. Resumen ejecutivo

El proyecto busca crear una **empresa real operada principalmente por agentes de Inteligencia Artificial**, donde el fundador/accionista humano define la visión, los objetivos y las decisiones estratégicas, mientras que agentes especializados realizan gran parte de la investigación, operación, construcción de productos, ventas, análisis y seguimiento.

La empresa **no tiene como objetivo vender la plataforma de IA ni vender agentes**. Los agentes son la **fuerza laboral interna** de la empresa.

El objetivo económico es:

> **Detectar oportunidades reales, crear productos y servicios, comercializarlos, generar ingresos y utilizar el conocimiento obtenido para identificar y ejecutar nuevas oportunidades.**

La empresa debe poder trabajar durante períodos en los que el accionista está ocupado en otras actividades, manteniendo autonomía operativa y solicitando intervención humana únicamente cuando una decisión sea importante, riesgosa o estratégica.

---

## 2. Visión

Construir una empresa capaz de funcionar con una cantidad mínima de intervención humana y una fuerza laboral compuesta principalmente por agentes de IA.

> **El humano define el rumbo; los agentes hacen avanzar el barco.**

El fundador no debe convertirse en el operador diario de cada actividad. Su función principal es establecer dirección, evaluar oportunidades, aprobar decisiones importantes y corregir el rumbo estratégico.

---

## 3. Qué NO es este proyecto

Este proyecto no busca inicialmente:

- Vender agentes de IA.
- Vender una plataforma de agentes.
- Crear únicamente un chatbot empresarial.
- Crear solamente un sistema de RAG.
- Construir una demostración tecnológica sin modelo económico.
- Sustituir toda decisión humana desde el primer día.
- Crear decenas de agentes sin una necesidad empresarial concreta.

La IA es el **medio**, no el producto principal.

Los productos y servicios reales que la empresa sea capaz de crear y comercializar serán el resultado económico.

---

## 4. Qué SÍ es el proyecto

Es una empresa real con una organización virtual basada en agentes.

```text
                         ACCIONISTA / FUNDADOR
                                  |
                                  v
                           CEO / ORQUESTADOR
                                  |
                    +-------------+-------------+
                    |                           |
                    v                           v
                 VENTAS                      PRODUCTO
                    |                           |
                    |                    +------+------+
                    |                    |             |
                    |                    v             v
                    |                Desarrollo       QA
                    |                    |
                    |                    v
                    |               Infraestructura
                    |
                    +-------------+-------------+
                                  |
                                  v
                         KNOWLEDGE LAYER
                              NEO4J
                                  |
                                  v
                           MUNDO REAL
                                  |
                    +-------------+-------------+
                    |                           |
                    v                           v
                 CLIENTES                  PRODUCTOS
                 
                 
                 from pathlib import Path

content = """# Forjai Autónoma — Documento de Diseño Inicial

**Fecha:** 31 de agosto de 2026  
**Estado:** Diseño / MVP — Ingeniería local 100%

---

## 1. Visión

Construir una **empresa de IA autónoma**, inicialmente operando 100% de forma local, capaz de generar productos y servicios para un mundo cada vez más virtual.

La empresa no se plantea inicialmente como un producto para vender. La empresa **utiliza agentes de IA para crear, validar, construir y operar productos y servicios reales**.

El humano (Alexander) actúa como **accionista / director estratégico**, aportando ideas, dirección y aprobaciones para las decisiones que realmente requieren intervención humana.

La empresa debe poder continuar trabajando cuando Alexander no está frente al computador.

---

## 2. Principio fundamental: autonomía

La empresa debe funcionar de manera autónoma.

La regla principal es:

> **La IA tiene autonomía operativa por defecto. Alexander interviene únicamente en decisiones estratégicas, financieras, legales o irreversibles.**

Si un agente necesita utilizar Internet para investigar, consultar documentación, analizar mercados o realizar una tarea autorizada, **no debe preguntar previamente**.

El objetivo es que el computador pueda permanecer encendido y la empresa continúe trabajando durante horas o días sin intervención constante.

---

## 3. Funcionamiento esperado

Ejemplo:

```text
Alexander propone una idea
        |
        v
   Forjai
        |
        +--> Sales analiza mercado
        |
        +--> Product analiza producto
        |
        +--> Engineering analiza viabilidad
        |
        +--> Finance analiza rentabilidad
        |
        +--> QA analiza calidad/riesgos
        |
        v
    CEO / Orchestrator
        |
        v
  Recomendación consolidada
        |
        v
 Alexander toma decisión estratégica
        |
        v
 Empresa continúa autónomamente
 
 
 
 Pero la empresa también debe generar trabajo por iniciativa propia.

Por ejemplo:

Scheduler
    |
    v
"¿Qué debería estar haciendo la empresa ahora?"
    |
    +--> investigar mercado
    +--> buscar oportunidades
    +--> analizar productos
    +--> revisar proyectos
    +--> mejorar software
    +--> analizar métricas
    +--> generar propuestas
4. Junta semanal

Uno de los procesos principales será una junta semanal de la empresa.

Inicialmente los participantes serán:

Sales Agent
Product Agent
Engineering Agent
Finance / Operations Agent
CEO / Orchestrator
Alexander, como accionista humano

La junta debe generar un reporte semanal.

Sales

Debe proponer periódicamente nuevas oportunidades:

mercados
necesidades detectadas
productos potenciales
servicios
tendencias
posibles clientes
competencia
Product

Debe evaluar:

problema
usuario
propuesta de valor
MVP
funcionalidades
diferenciadores
viabilidad
Engineering

Debe evaluar:

arquitectura
tecnologías
esfuerzo
riesgos técnicos
infraestructura
tiempo estimado
Finance / Operations

Debe evaluar:

costos
rentabilidad
recursos
presupuesto
riesgos económicos
CEO / Orchestrator

Debe consolidar las opiniones y presentar:

estado de la empresa
oportunidades
problemas
decisiones pendientes
recomendaciones
prioridades de la siguiente semana
5. Niveles de autonomía
🟢 Nivel 1 — Autónomo

Los agentes pueden realizar estas actividades sin pedir autorización:

navegar Internet
investigar
leer documentación
analizar mercados
analizar competencia
generar ideas
escribir código
crear proyectos
ejecutar pruebas
corregir errores
generar documentación
analizar métricas
actualizar Neo4j
crear tareas
comunicarse entre agentes
generar reportes
trabajar durante la noche
buscar nuevas oportunidades
🟡 Nivel 2 — Autónomo con límites

Puede realizar acciones mientras respete políticas previamente definidas:

consumir APIs
utilizar servicios gratuitos
crear recursos técnicos
realizar operaciones de desarrollo
desplegar ambientes de desarrollo
enviar solicitudes
ejecutar acciones comerciales autorizadas
realizar gastos dentro de un presupuesto autorizado

Ejemplo:

Presupuesto autorizado
        |
        v
     $50 USD
        |
   +----+----+
   |         |
 API $5    API $10
   |         |
   +----+----+
        |
      $15
        |
    permitido
🔴 Nivel 3 — Requiere aprobación humana

Debe solicitar intervención de Alexander para:

inversiones importantes
contratos
obligaciones legales
contratar personas
vender o transferir activos
eliminar información crítica
decisiones estratégicas importantes
acciones irreversibles o de alto riesgo
6. Arquitectura conceptual

La arquitectura inicial propuesta:

                         👤 ALEXANDER
                              |
                              v
                    +--------------------+
                    |   AI EXECUTIVE     |
                    |       OFFICE       |
                    +---------+----------+
                              |
                              v
                    +--------------------+
                    |   ORCHESTRATOR     |
                    |     / AI CEO       |
                    +---------+----------+
                              |
             +----------------+----------------+
             |                |                |
             v                v                v
          SALES            PRODUCT        ENGINEERING
             |                |                |
             +----------------+----------------+
                              |
             +----------------+----------------+
             |                |                |
             v                v                v
            QA             FINANCE         OPERATIONS
                              |
                              v
                         TOOL LAYER
                              |
          +-------------------+-------------------+
          |                   |                   |
          v                   v                   v
       Internet              Git                Docker
          |                   |                   |
          +-------------------+-------------------+
                              |
                              v
                         Knowledge
                            Graph
                           Neo4j
7. Neo4j

Neo4j no será simplemente un "RAG de comunicación".

Su función principal será ser la memoria y conocimiento estructurado de la empresa.

La comunicación entre agentes deberá estar gestionada por el Orchestrator y/o una capa de mensajería.

Neo4j almacenará información como:

decisiones
proyectos
ideas
productos
clientes
tareas
agentes
conocimientos
tecnologías
mercados
reportes
objetivos
relaciones entre todos estos elementos

Ejemplo conceptual:

Alexander
    |
    +-- OWNS --> Company
    |
    +-- PROPOSED --> Idea
                       |
                       +-- ANALYZED_BY --> SalesAgent
                       |
                       +-- ANALYZED_BY --> ProductAgent
                       |
                       +-- EVALUATED_BY --> EngineeringAgent

El objetivo es que la empresa pueda recordar:

qué decidió, por qué lo decidió, quién lo analizó y qué ocurrió posteriormente.

8. Agentes iniciales
CEO / Orchestrator

Responsabilidades:

recibir instrucciones humanas
recibir eventos del sistema
dividir problemas
asignar tareas
seleccionar agentes/modelos
consolidar resultados
detectar conflictos
aplicar políticas
solicitar aprobaciones
generar reportes
mantener objetivos de la empresa

El CEO no debe ejecutar directamente todo.

Su principal función es coordinar.

Product Agent

Responsabilidades:

identificar problemas
evaluar ideas
definir productos
definir MVP
estudiar usuarios
analizar propuesta de valor
analizar competencia
determinar viabilidad
Sales Agent

Responsabilidades:

buscar oportunidades
investigar mercados
detectar necesidades
estudiar competencia
identificar potenciales clientes
generar propuestas comerciales
proponer nuevas oportunidades semanalmente

Este agente es fundamental para que la empresa tenga iniciativa propia.

Engineering Agent

Responsabilidades:

arquitectura
diseño de software
programación
APIs
bases de datos
Docker
Kubernetes
cloud
debugging
pruebas
documentación técnica
QA Agent

Responsabilidades:

revisar código
crear pruebas
ejecutar pruebas
detectar errores
analizar regresiones
evaluar calidad
Finance / Operations Agent

Responsabilidades:

costos
rentabilidad
presupuesto
infraestructura
métricas
productividad
indicadores operativos
9. Los agentes no requieren un modelo diferente cada uno

Un agente es principalmente:

ROLE
+
SYSTEM PROMPT
+
TOOLS
+
MEMORY
+
POLICIES
+
MODEL

Por lo tanto:

6 agentes

no significa necesariamente:

6 modelos

Podemos utilizar diferentes modelos dependiendo de la tarea.

10. Modelos locales disponibles actualmente

La máquina ya tiene Ollama configurado y funcionando con GPU.

Ollama principal

Puerto:

11434

Modelos disponibles:

qwen2.5-coder:7b
qwen2.5-coder:14b
llava:7b
Segundo Ollama

Contenedor:

ats-ollama

Puerto:

11435

Modelo:

mistral:7b
11. Hardware actual

Equipo:

Acer Nitro ANV16S-71

RAM:

32 GB

CPU:

Intel Core 7 240H
16 threads

GPU:

NVIDIA GeForce RTX 5060 Laptop GPU
8 GB VRAM

Disco:

2 TB

Sistema operativo:

Fedora Linux 44 Workstation
64-bit

Kernel:

Linux 7.1.10-200.fc44.x86_64

GNOME:

50

Windowing:

Wayland
12. GPU y Docker

Se verificó que Docker tiene acceso a NVIDIA.

Prueba realizada:

docker run --rm --gpus all nvidia/cuda:13.3.0-base-ubuntu24.04 nvidia-smi

Resultado:

NVIDIA GeForce RTX 5060 Laptop GPU
VRAM: 8151 MiB
CUDA UMD Version: 13.3

Los dos contenedores de Ollama tienen acceso a la GPU mediante:

NVIDIA Device Request
Driver: nvidia
Capabilities: gpu
13. Ollama y CUDA

Los logs de Ollama confirmaron:

library=CUDA
compute=12.0
name=CUDA0
description=NVIDIA GeForce RTX 5060 Laptop GPU
driver=13.3
total=8.0 GiB
available=7.6 GiB

Por tanto:

Docker
   |
   v
NVIDIA Container Toolkit
   |
   v
Ollama
   |
   v
CUDA
   |
   v
RTX 5060

está funcionando correctamente.

14. Consideración sobre la VRAM

La GPU tiene aproximadamente:

8 GB VRAM

Por lo tanto no debemos asumir que todos los modelos pueden mantenerse simultáneamente en GPU.

El modelo:

qwen2.5-coder:14b

ocupa aproximadamente:

8.99 GB

en disco.

El modelo puede utilizar una combinación de GPU y RAM dependiendo de la configuración y del contexto.

Por esta razón será necesario medir:

VRAM utilizada
RAM utilizada
velocidad de inferencia
tiempo de respuesta
calidad
concurrencia
estabilidad

antes de definir qué modelo utilizará cada agente.

15. Docker como estrategia de instalación

La infraestructura debe ser fácil de instalar, actualizar y eliminar.

La intención es encapsular los componentes en Docker.

Componentes previstos:

Docker
 |
 +-- Ollama
 |
 +-- Orchestrator
 |
 +-- Agents
 |
 +-- Neo4j
 |
 +-- Scheduler
 |
 +-- Message Bus
 |
 +-- Tool Services

La infraestructura debe poder levantarse y detenerse mediante Docker Compose.

16. Scheduler

El Scheduler será fundamental para la autonomía.

No queremos agentes esperando permanentemente una petición.

El Scheduler deberá generar eventos como:

cada hora
cada día
cada semana
ante un evento
ante una tarea pendiente
ante una condición

Ejemplo:

Scheduler
    |
    v
"Ejecutar revisión nocturna"
    |
    v
Orchestrator
    |
    +--> Sales
    +--> Product
    +--> Engineering
    +--> Finance
17. Supervisor y recuperación

La empresa debe ser capaz de recuperarse de errores.

Ejemplo:

Agent
  |
  v
Crash
  |
  v
Supervisor
  |
  v
Detecta error
  |
  v
Reinicia agente
  |
  v
Recupera contexto
desde Neo4j
  |
  v
Continúa trabajo

Esto es necesario para conseguir autonomía real.

18. Reportes

La empresa deberá producir reportes periódicos.

Reporte diario

Debe contener:

actividades realizadas
tareas completadas
tareas fallidas
decisiones tomadas
oportunidades detectadas
problemas
métricas
Reporte semanal

Debe contener:

estado general
oportunidades nuevas
productos propuestos
progreso de proyectos
resultados comerciales
resultados técnicos
costos
riesgos
decisiones pendientes
recomendaciones
Alertas inmediatas

Solo para eventos importantes:

bloqueo
fallo crítico
decisión estratégica
riesgo importante
límite de presupuesto
incidente grave
19. Empresa híbrida

La empresa no se limita a inventar productos.

Debe poder operar en dos modos:

Modo A — Crear nuevos productos
Mercado
   |
   v
Sales
   |
   v
Idea
   |
   v
Product
   |
   v
Engineering
   |
   v
MVP
Modo B — Operar un producto/servicio existente
Producto existente
       |
       +--> Sales
       +--> Marketing
       +--> Support
       +--> Engineering
       +--> Operations
       +--> Finance

Esto permite que la empresa tenga tanto:

productos propios
servicios
oportunidades comerciales existentes

como nuevas iniciativas generadas autónomamente.

20. Objetivo del MVP

El MVP inicial no será construir toda la empresa.

El primer MVP será demostrar que una pequeña organización de agentes puede:

trabajar localmente
utilizar modelos locales
utilizar Internet sin supervisión constante
mantener memoria en Neo4j
comunicarse mediante un Orchestrator
ejecutar tareas automáticamente
generar nuevas ideas
generar un reporte
detectar decisiones que requieren intervención humana
continuar trabajando después de una decisión humana
21. Primera fase técnica

La primera fase debe enfocarse exclusivamente en Ingeniería.

Objetivo:

Construir el primer núcleo operativo de la empresa.

Componentes iniciales:

1. Ollama
2. Orchestrator
3. Engineering Agent
4. Tool Layer
5. Neo4j
6. Scheduler
7. Supervisor
8. Reporting

Después incorporaremos:

Sales
Product
Finance
QA
Marketing
Operations
22. Arquitectura inicial propuesta
                 +-------------------+
                 |     ALEXANDER     |
                 +---------+---------+
                           |
                           v
                 +-------------------+
                 |   AI EXECUTIVE    |
                 |      OFFICE       |
                 +---------+---------+
                           |
                           v
                 +-------------------+
                 |   ORCHESTRATOR    |
                 +---------+---------+
                           |
              +------------+------------+
              |                         |
              v                         v
        +-----------+             +-----------+
        | Scheduler |             | Policies  |
        +-----------+             +-----------+
              |                         |
              +------------+------------+
                           |
                           v
                    +-------------+
                    |    AGENTS   |
                    +------+------+
                           |
             +-------------+-------------+
             |             |             |
             v             v             v
          Product      Engineering      Sales
             |             |             |
             +-------------+-------------+
                           |
                           v
                     +-----------+
                     |   TOOLS   |
                     +-----------+
                           |
       +-------------------+-------------------+
       |                   |                   |
       v                   v                   v
   Internet              Git                Docker
       |
       v
    External
    Systems

                           |
                           v
                      +---------+
                      |  Neo4j  |
                      +---------+

                           |
                           v
                      +---------+
                      | Reports |
                      +---------+
23. Principio de diseño

La empresa debe separar claramente:

MODELO
    = razona

AGENTE
    = tiene un rol

ORCHESTRATOR
    = coordina

TOOLS
    = ejecutan acciones

POLICIES
    = establecen límites

SCHEDULER
    = genera trabajo

NEO4J
    = conserva conocimiento y memoria

SUPERVISOR
    = mantiene la operación

ALEXANDER
    = dirección estratégica
24. Visión final

La visión a largo plazo es:

                    👤 ALEXANDER
                         |
                   Dirección estratégica
                         |
                         v
                 +---------------+
                 |   FORJAI  |
                 +-------+-------+
                         |
              +----------+----------+
              |                     |
          HUMAN INPUT          AUTONOMOUS LOOP
                                    |
                     +--------------+--------------+
                     |              |              |
                   Sales          Product      Engineering
                     |              |              |
                     +--------------+--------------+
                                    |
                                 Build
                                    |
                                  Test
                                    |
                                Deploy
                                    |
                                Measure
                                    |
                                Learn
                                    |
                              Generate
                              new ideas
                                    |
                                    +----> NEXT CYCLE

El objetivo final es que Alexander pueda decir:

"Quiero construir una empresa que genere productos y servicios para el mundo real."

y la empresa pueda:

investigar
   ↓
proponer
   ↓
analizar
   ↓
construir
   ↓
probar
   ↓
operar
   ↓
medir
   ↓
aprender
   ↓
proponer nuevamente

con intervención humana únicamente cuando realmente sea necesaria.

25. Próximo paso

Antes de añadir más modelos o más agentes, construir el primer núcleo:

Docker
  +
Ollama
  +
Engineering Agent
  +
Orchestrator
  +
Neo4j
  +
Scheduler
  +
Tools
  +
Supervisor

La primera prueba de éxito será:

Dejar la empresa ejecutándose, darle un objetivo y comprobar que puede investigar, planificar, ejecutar tareas técnicas, guardar lo aprendido en Neo4j y generar un reporte sin necesitar que Alexander esté interactuando constantemente.

Ese será el verdadero MVP de la empresa autónoma de IA.
"""

path = Path("/mnt/data/AI_Company_Autonoma_Diseno_Inicial.md")
path.write_text(content, encoding="utf-8")
print(f"Archivo creado: {path}")
print(f"Tamaño: {path.stat().st_size:,} bytes")
