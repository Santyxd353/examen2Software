# GPT mediante Codex

La configuración predeterminada de examen2Software usa GPT-6.1 Sol mediante
Codex CLI 0.167.2 para chat, revisión, resumen, propuestas UML e imágenes.
El modelo se cambia con CLASSFORGE_CODEX_MODEL. Las consultas consumen la cuota
de la cuenta ChatGPT/Codex que inició sesión. Whisper local transcribe el audio.

Instalar el CLI oficial, iniciar sesión con ChatGPT y configurar el backend.
El ejecutable debe ser el binario nativo, especialmente en Windows. El
directorio indicado debe contener la sesión creada por `codex login`.

```dotenv
CLASSFORGE_CODEX_EXECUTABLE=/opt/classforge/bin/codex
CLASSFORGE_CODEX_HOME=/opt/classforge/data/codex
CLASSFORGE_CODEX_MODEL=gpt-6.1-sol
CLASSFORGE_CODEX_TIMEOUT_MILLIS=90000
CLASSFORGE_ASSISTANT_TEXT_PROVIDER=codex
CLASSFORGE_ASSISTANT_VISION_PROVIDER=codex
CLASSFORGE_ASSISTANT_VOICE_PROVIDER=whisper-cpp
```

En Azure VM, después de instalar ClassForge y antes de iniciar su servicio,
ejecutar los instaladores de runtime del bundle y autorizar el acceso.

```bash
sudo bash install-codex.sh
sudo bash install-whisper.sh
sudo -u classforge env CODEX_HOME=/opt/classforge/data/codex \
  /opt/classforge/bin/codex -c cli_auth_credentials_store=file login --device-auth
sudo systemctl restart classforge
```

El código de dispositivo se autoriza en el navegador del dueño de la cuenta.
auth.json permanece privado, con permisos 0600 en una carpeta 0700 del usuario
classforge. Se necesita acceso de red al servicio de Codex durante las consultas.
Las características de voz y su instalación están en [Whisper local](whisper-local.md).

El transporte usa un directorio temporal por petición, schema JSON, un plazo
máximo y una consulta concurrente. Las herramientas del agente están desactivadas.
El proceso recibe un entorno explícito y solo devuelve propuestas validadas.
Grounding, referencias, revisión, preview y Apply siguen siendo obligatorios.
Los errores de cuota/auth pertenecen al asistente y conservan la sesión de
ClassForge. El tiempo de espera de cuota se muestra cuando el proveedor lo indica.

Los asistentes exportados incluyen el mismo transporte de fuentes y esperan
su propia configuración `app.assistant.codex.*`. Su README explica el login y
las variables; la sesión privada se configura en la máquina que los ejecuta.

Verificaciones realizadas con la versión fijada

- CLI invocado contra servidor de protocolo local, con lista efectiva de tools vacía.
- Inferencia real de texto y propuesta de herramienta con la sesión ChatGPT.
- Lectura real de una foto de pizarra con seis clases, con grounding y bounds.
- WAV español transcrito por el runtime instalado en la VM.

Verificación del despliegue (12 de noviembre de 2026)

- Backend: 612 tests, 602 aprobados y 10 omitidos; sin fallos ni errores.
- Frontend: 18 pruebas de caracterización aprobadas y build de producción correcto.
- Protocolo Whisper: 18 pruebas aprobadas. Modelo local small-q8_0.
- API pública: ASK, REVIEW, SUMMARY_CURRENT, EDIT, imagen y voz respondieron HTTP 200.
  Las propuestas conservaron el documento y su revisión, sin ejecutar Apply.
- Voz: la orden para crear Factura con un atributo id de tipo Long conservó ambos
  elementos. Se cubrió también la variante «Cree a una clase…» en las regresiones.
- Health del asistente: texto, imagen y voz disponibles. Sitio, rutas Angular,
  protección de API y handshake WebSocket comprobados.
- Artefacto activo: código `5ad9265b41b8f123f5131d27048c2ba4e63310de`, SHA-256
  `85b567eda59be5f0bda7e4c82bcf14c6f5cb21e8796f97955749b3e2d183fcc9`.

Las respuestas dependen de la cuota y la sesión de Codex autorizada. El chequeo
de salud comprueba el runtime y la sesión; no consume una inferencia del modelo.

Fuentes oficiales

- [Autenticación](https://learn.chatgpt.com/docs/auth)
- [Modo no interactivo](https://learn.chatgpt.com/docs/non-interactive-mode)
- [Modelos](https://learn.chatgpt.com/docs/models)
