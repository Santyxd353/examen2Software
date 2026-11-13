# ClassForge

> Model it. Generate it. Talk to it.

ClassForge es una herramienta CASE web, colaborativa y offline-first para modelar diagramas de clases UML y utilizarlos como fuente de verdad para futuras transformaciones y generación de aplicaciones.

La visión completa del producto está en `docs/product/product.md`. El estado realmente implementado está en `docs/puds/current-status.md`.

Cliente Android conectado: [descargar APK firmado](https://classforge-tiago-15124.eastus.cloudapp.azure.com/android/classforge.apk).
Usa la misma instancia de Azure, cuentas, proyectos e IA que la web. Requiere
Internet y navegador Android compatible. [Instalación y compilación](docs/runtime/android-app.md).

## Estado actual

**Fase de Transición — Ciclos 1–10 CERRADOS; CU-27 demo reproducible final CERRADO.**

En ClassForge llamamos **Ciclo** a una **iteración PUDS**.

El incremento ejecutable actual incluye:

- autenticación local, JWT, BCrypt y ownership;
- CU-01 crear proyecto;
- CU-02 abrir y guardar `ProjectDocument` con revisión;
- CU-03 modelado UML manual mediante Angular + JointJS;
- clases, atributos, layout, relaciones y multiplicidades;
- Association, Aggregation, Composition y Generalization;
- CU-04 validación UML explícita;
- CU-05 Command Bus + Undo/Redo;
- CU-06 colaboración autoritativa Spring WebSocket/STOMP;
- CU-07 presencia efímera;
- CU-08 lenguaje natural y voz para proponer cambios UML con GPT mediante una sesión de Codex;
- GPT (`gpt-6.1-sol`, configurable) como planificador de propuestas estructuradas;
- Whisper local como Speech-to-Text;
- preview, grounding, validación, BATCH atómico y protección por revisión;
- health de la sesión Codex y del transcriptor local, y diagnóstico del pipeline;
- CU-09 Imagen -> UML con GPT y validación Java, BATCH/preview/Apply canónico y persistencia validada;
- CU-10/CU-11 XMI 2.1 / Enterprise Architect: import seguro con preview/apply y export determinista del subconjunto UML canónico, con round-trip semántico;
- CU-12 UML -> `RelationalModel`: IR interna, efímera y determinista;
- CU-13 generación Spring Boot/JPA: Java 21, Spring Boot 4.0.8, Gradle Wrapper 9.2.0, entidades/repositorios, H2/PostgreSQL, ZIP determinista y acceptance compilable.
- CU-14 API CRUD expresiva: DTOs/services/controllers, búsqueda/filtros/orden/paginación/conteo y dos perfiles explícitos: CRUD simple o Sistema de Información con Auth (BCrypt + JWT);
- CU-15 OpenAPI/Postman: `openapi.yaml` 3.0.3 + `postman_collection.json` 2.1 deterministas desde un contrato HTTP canónico;
- CU-16 Domain Manifest: `domain-manifest.json` schema 1.0 determinista con UUIDs estables, IDs, atributos, relaciones, herencia, capacidades, operationIds y metadata Auth;
- CU-17 frontend web Angular: SPA standalone dentro de `frontend/`, dashboard, componentes específicos por entidad, CRUD/relaciones, Simple/Auth y color primario seleccionable;
- CU-18 frontend mobile Flutter: app independiente dentro de `mobile/`, dashboard, pantallas específicas por entidad, Simple/Auth, almacenamiento seguro de JWT y Android validado;
- CU-19 interacción con datos mediante chat y voz en la aplicación generada: GPT por Codex y Whisper local, grounding Java, consultas directas y mutaciones con preview/token/apply.
- hardening post-CU-19: las apps generadas autogeneran PK simples seguras (`UUID`, `Integer`, `Long`, `String`), las ocultan en formularios web/mobile salvo una PK `String` elegida explícitamente como credencial Auth, ocultan navegación web Auth hasta login y reutilizan estilos globales en los controles del Assistant.

También están implementados como infraestructura transversal:

- CU-28 registrar cuenta;
- CU-29 iniciar sesión;
- CU-30 ownership persistente, ampliado en CU-31 con proyectos compartidos;
- CU-24 STT local y CU-25 GPT en el contexto del Asistente UML.

CU-27 — demo reproducible final — está **CERRADO**. El perfil `demo` crea un proyecto Veterinaria determinista con OWNER/EDITOR, fixture XMI 2.1, scripts de reset/start/smoke, acceptance agregado y smoke opcional con Enterprise Architect real.

CU-26 — auditoría histórica — quedó **DESCARTADO / FUERA DE ALCANCE** por decisión de producto; se conserva en la trazabilidad histórica.

CU-31 quedó cerrado en C2-cu31-003. CU-09 — Imagen → UML — quedó cerrado el 5 de septiembre de 2026 después del hardening hybrid-CV, política fail-closed, E2E canónico, canonicalización de identificadores y smoke manual de producto. La validación multi-pizarra adicional se conserva como recomendación de robustez y no se presenta como evidencia ejecutada.

En el Ciclo 3, CU-12 cerró la transformación determinista UML -> modelo relacional, CU-13 la generación/exportación Spring Boot/JPA y CU-14 la API CRUD expresiva con perfiles Simple/Auth. La evidencia de CU-13 está en `docs/evidence/cu13/` y la de CU-14 en `docs/evidence/cu14/`.

El **Ciclo 4** está CERRADO con CU-15. Cada export CU-14 incluye `openapi.yaml` y `postman_collection.json` generados determinísticamente desde el mismo contrato canónico de API; Auth documenta bootstrap/login/Bearer JWT y Simple no declara seguridad.

El **Ciclo 5** está CERRADO con CU-16. Cada export CU-14/15 incluye `domain-manifest.json` schema `1.0`, generado directamente desde los modelos/planes canónicos y validado contra OpenAPI/Postman antes del ZIP.

El **Ciclo 6** está CERRADO con CU-17. El mismo ZIP incluye `frontend/`, un Angular standalone con dashboard, componentes específicos list/detail/form por entidad, soporte Simple/Auth y theme primario `#RRGGBB` seleccionado al exportar.

El **Ciclo 7** está CERRADO con CU-18. El mismo ZIP incluye `mobile/`, una app Flutter con dashboard, pantallas específicas por entidad, relaciones, IDs compuestos, Simple/Auth, `flutter_secure_storage` para JWT y scaffold Android. El color primario se reutiliza desde la misma opción de exportación de CU-17.

## Arquitectura vigente

```text
UI manual / colaboración / Asistente
                |
                v
          UmlCommand / BATCH
                |
                v
          UmlCommandBus
                |
                v
       UmlCommandExecutor
                |
                v
         ProjectDocument
        /               \
   UmlModel          DiagramLayout
        |
        +--> validación
        +--> JointJS como proyección
        +--> persistencia autoritativa
```

Principios:

1. `ProjectDocument` es la raíz documental persistida.
2. `UmlModel` es la fuente de verdad semántica.
3. `DiagramLayout` se mantiene separado de la semántica.
4. JointJS representa el modelo; no es el modelo.
5. UUID es identidad estable.
6. Las mutaciones pasan por comandos tipados.
7. El backend valida, controla revisión y persiste como autoridad final.
8. Presencia es efímera y no incrementa revisión.
9. Whisper local transcribe y GPT propone las operaciones UML; Java resuelve, valida y previsualiza.
10. La IA nunca modifica directamente `ProjectDocument`.

## Ejecutar backend

```powershell
cd backend
.\gradlew.bat bootRun
```

Backend:

```text
http://localhost:8082
```

Health:

```text
GET http://localhost:8082/api/health
```

## Ejecutar frontend

```powershell
cd frontend
npm install
npm start
```

Frontend:

```text
http://localhost:4200
```

El proxy Angular reenvía `/api/*` a `http://localhost:8082`.

## GPT con la sesión de Codex

```text
CLASSFORGE_CODEX_EXECUTABLE=/ruta/al/binario/codex
CLASSFORGE_CODEX_HOME=/ruta/privada/de/codex
CLASSFORGE_CODEX_MODEL=gpt-6.1-sol
```

Autenticar el CLI con `codex login` y configurar su directorio privado. Copiar
`.env.example` a `.env` o definir las variables en el servidor. La sesión usa
la cuota de la cuenta ChatGPT/Codex autorizada. Whisper local atiende el audio.
La configuración está en [asistente GPT/Codex](docs/runtime/assistant-codex.md).

## Persistencia

Para preparar una VM Azure (Ubuntu, JAR completo, nginx HTTPS/WSS y systemd), ver
[despliegue Azure](docs/runtime/azure-deployment.md). La preparación no crea recursos.

- MongoDB con Spring Data MongoDB para toda la base propia de ClassForge;
- replica set y transacciones para documento, auditoría, sesiones y notificaciones;
- volumen persistente local con Docker; las pruebas usan bases aisladas sobre MongoDB real.

Antes de iniciar el backend o ejecutar sus pruebas:

```powershell
docker compose -f compose.mongo.yml up -d --wait
```

`CLASSFORGE_MONGODB_URI` permite usar otro replica set o MongoDB Atlas.
La migración preserva usuarios, IDs, contraseñas cifradas, proyectos, membresías,
invitaciones, chat e historial de una base H2 anterior. Ver
[cierre de Persona A y migración NoSQL](docs/parcial2/persona-a-cierre.md).
Los generadores del primer parcial conservan su salida Spring/JPA; esa salida
no es la base de datos interna de ClassForge.

Un posible archivo `*.classforge` continúa siendo un formato portable futuro, no la persistencia primaria.

## Documentación

- `docs/README.md`: índice documental y fuente prevista para el Word final;
- `docs/product/`: visión y alcance del producto objetivo;
- `docs/puds/`: proceso, ciclos, casos de uso y estado;
- `docs/architecture/`: decisiones técnicas y correspondencia con código;
- `docs/runtime/`: configuración de GPT/Codex, despliegue y guías de runtimes locales;
- `docs/uml/`: catálogo de diagramas UML académicos y su estado.

## Cierre funcional

Los **Ciclos 1–10 están formalmente CERRADOS**. CU-27 es el último caso funcional planificado: la demo Veterinaria se reconstruye con `scripts/demo-reset.ps1`, luego Spring y Angular se arrancan manualmente en consolas separadas; se recertifica con `scripts/demo-acceptance.ps1` y dispone de `scripts/demo-ea-smoke.ps1` para la prueba de transición con una instalación real de Enterprise Architect. CU-26 permanece DESCARTADO / FUERA DE ALCANCE. El trabajo restante es de presentación, documentación académica y ejecución del runbook final.

La evidencia de cierre está en:

- CU-09: `docs/evidence/cu09/cu09-closure-report.md`;
- CU-13: `docs/evidence/cu13/cu13-closure-report.md` y `docs/evidence/cu13/cu13-acceptance.json`.
- CU-17: `docs/evidence/cu17/cu17-closure-report.md` y `docs/evidence/cu17/cu17-acceptance.json`;
- CU-18: `docs/evidence/cu18/cu18-closure-report.md` y `docs/evidence/cu18/cu18-acceptance.json`;
- CU-19: `docs/evidence/cu19/cu19-closure-report.md` y `docs/evidence/cu19/cu19-acceptance.json`;
- CU-10/CU-11: `docs/evidence/cu10-cu11/cu10-cu11-closure-report.md` y `docs/evidence/cu10-cu11/cu10-cu11-acceptance.json`;
- CU-27: `docs/evidence/cu27/cu27-closure-report.md`, `docs/evidence/cu27/cu27-acceptance.json` y `docs/runtime/cu27-demo.md`.

## Baseline para documentación formal

El cierre de legibilidad del 14 de septiembre de 2026 no modifica funcionalidades. Para estudiar y defender el sistema usar primero:

- `docs/formal/code-map.md`: mapa de navegación por responsabilidades;
- `docs/formal/traceability-matrix.md`: CU -> arquitectura -> código -> pruebas/evidencia;
- `docs/formal/refactor-readability-report.md`: alcance del refactor, decisiones conservadoras y gates finales.

## Deployment AWS de demostración

El perfil `aws-demo` mantiene Angular/Spring en una EC2 detrás de CloudFront/Nginx,
con MongoDB replica set y Groq para toda la IA. El bundle activo no requiere
Whisper local ni permisos Bedrock. Ver
[`docs/runtime/assistant-groq.md`](docs/runtime/assistant-groq.md) para la
configuración actual; los documentos anteriores de Bedrock describen el despliegue histórico.
