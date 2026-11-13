# ClassForge: Groq como proveedor único

Esta guía corresponde a la configuración anterior. El repositorio examen2Software
usa ahora [GPT con Codex y Whisper local](assistant-codex.md) por defecto.

Actualización: 8 de noviembre de 2026. Se integraron las partes A y B sobre
`e293845` (MongoDB, actividad, sesiones, notificaciones y asistente compartido).

## Funciones y modelos

| Función | Modelo Groq predeterminado | Flujo conservado |
| --- | --- | --- |
| ASK, REVIEW, SUMMARY_CURRENT | openai/gpt-oss-120b | Conversación compartida y revisión UML |
| EDIT / comandos por voz | openai/gpt-oss-120b | Tools → grounding → BATCH → preview → Apply |
| Imagen → UML y etapas híbridas | qwen/qwen3.8-27b | JSON validado + OpenCV + preview/Apply |
| Voz → texto | whisper-large-v3-turbo | Audio WAV → transcripción → planner |
| Asistente de aplicaciones generadas | GPT-OSS + Whisper en Groq | Consultas / preview-token-Apply existentes |

Los modelos son configurables por entorno. Qwen3.8-27B es un modelo **preview**;
su disponibilidad puede cambiar. No se requiere un proceso local de IA, GPU,
credenciales AWS para IA o permisos Bedrock. OpenCV sigue ejecutándose en CPU.
Los adaptadores anteriores se conservan para regresión, pero no se seleccionan
en la configuración normal ni en el perfil AWS.

## Configuración local

Crear la clave en la consola de Groq. Copiar `.env.example` a `.env` y completar
solo el archivo ignorado, o usar variables de entorno del proceso:

```dotenv
GROQ_API_KEY=clave_privada_solo_en_servidor
GROQ_TEXT_MODEL=openai/gpt-oss-120b
GROQ_VISION_MODEL=qwen/qwen3.8-27b
GROQ_SPEECH_MODEL=whisper-large-v3-turbo
```

Spring carga opcionalmente `.env` desde la raíz o desde su directorio padre al
arrancar en `backend`. La clave no se escribe en Angular/Flutter ni en los ZIP
exportados. Las apps generadas esperan su propia variable GROQ_API_KEY en el
backend de despliegue. No pegar claves en README, commits, capturas o incidencias.

MongoDB debe ser un replica set; puede arrancarse con
`docker compose -f compose.mongo.yml up -d --wait` o configurarse un replica set
propio/Atlas mediante CLASSFORGE_MONGODB_URI. El almacenamiento MongoDB es
independiente del proveedor IA.

```powershell
.\backend\gradlew.bat -p backend bootRun
cd frontend
npm ci
npm start
```

El frontend sigue usando `/api` y `/ws` del backend, sin conectarse a Groq.

## Despliegue AWS

El bundle de `scripts/aws-build-deploy-bundle.ps1` incluye el JAR, Angular,
nginx, `classforge.service` y `classforge.env.example` actualizado.
Configurar `/etc/classforge/classforge.env` con:

- GROQ_API_KEY y, opcionalmente, los tres modelos;
- CLASSFORGE_MONGODB_URI de un replica set;
- CLASSFORGE_JWT_SECRET y el origen WebSocket permitido.

La unidad systemd ya no depende de `classforge-whisper.service`, y el instalador
no instala ni habilita ese servicio. El bundle no incluye el instalador Whisper
ni la política IAM de Bedrock. No es necesario adjuntar un rol para invocar IA.
Los scripts/documentos de IA local y Bedrock que permanecen en el repositorio son
material histórico y de regresión; no son la configuración activa.

## Cuotas, contexto y errores

El plan gratuito está limitado por solicitudes, tokens y segundos de audio por
organización. No equivale a capacidad ilimitada ni garantiza tráfico de producción.
La tabla oficial consultada publica 8K TPM / 1K RPD / 200K TPD para GPT-OSS 120B
y Qwen3.8-27B; los límites exactos de la cuenta se consultan en Groq Console.

El contexto reserva primero instrucciones, documento y pregunta actual, y utiliza
mensajes recientes completos que entren en el presupuesto (máximo veinte).
No se recorta el JSON UML ni se truncan preguntas. El presupuesto inicial es
18.000 caracteres, configurable mediante CLASSFORGE_ASSISTANT_CONVERSATION_MAX_CONTEXT_CHARS.
Si la instantánea obligatoria excede el límite, se devuelve un error explícito.
El límite en caracteres aproxima el uso de tokens; no garantiza evitar una cuota
compartida por varias solicitudes.

Groq devuelve HTTP429 cuando se supera una cuota. ClassForge conserva Retry-After
y la pregunta persistida para reintento manual con el mismo requestId, sin duplicar
respuestas. No hay fallback a servicios de pago ni reintentos automáticos del
transporte. Un error de API key del proveedor se transforma en HTTP503, para no
cerrar la sesión JWT del usuario de ClassForge.

Health consulta y almacena temporalmente la lista de modelos de la cuenta; sin
clave informa NOT_CONFIGURED, y no marca como listo un modelo ausente.
El estado READY indica disponibilidad de modelo/configuración y sigue sujeto
a cuotas y al resultado de cada consulta.

## Fuentes verificadas

- [Modelos activos](https://console.groq.com/docs/models).
- [Visión y límites de imagen](https://console.groq.com/docs/vision).
- [Qwen3.8-27B y razonamiento](https://console.groq.com/docs/model/qwen/qwen3.8-27b).
- [Tool calling](https://console.groq.com/docs/tool-use/overview).
- [Speech to text](https://console.groq.com/docs/speech-to-text).
- [Structured outputs](https://console.groq.com/docs/structured-outputs).
- [Cuotas y Retry-After](https://console.groq.com/docs/rate-limits).

La evidencia final de pruebas se registra en `docs/evidence/parcial2/groq-integration.md`.
