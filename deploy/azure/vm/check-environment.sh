#!/usr/bin/env bash
set -euo pipefail
for variable in CLASSFORGE_MONGODB_URI CLASSFORGE_JWT_SECRET CLASSFORGE_WEBSOCKET_ALLOWED_ORIGINS; do
  if [[ -z ${!variable:-} ]]; then
    echo "Required server setting is empty: $variable" >&2
    exit 1
  fi
done
if [[ ${CLASSFORGE_ASSISTANT_TEXT_PROVIDER:-codex} == codex || ${CLASSFORGE_ASSISTANT_VISION_PROVIDER:-codex} == codex ]]; then
  if [[ -z ${CLASSFORGE_CODEX_HOME:-} || ! -r "$CLASSFORGE_CODEX_HOME/auth.json" ]]; then
    echo 'Configure a private Codex ChatGPT login in CLASSFORGE_CODEX_HOME.' >&2
    exit 1
  fi
  if ! command -v "${CLASSFORGE_CODEX_EXECUTABLE:-codex}" >/dev/null; then
    echo 'The configured Codex executable is unavailable.' >&2
    exit 1
  fi
fi
if [[ ${CLASSFORGE_ASSISTANT_TEXT_PROVIDER:-codex} == groq || ${CLASSFORGE_ASSISTANT_VISION_PROVIDER:-codex} == groq || ${CLASSFORGE_ASSISTANT_VOICE_PROVIDER:-whisper-cpp} == groq ]]; then
  if [[ -z ${GROQ_API_KEY:-} ]]; then
    echo 'GROQ_API_KEY is required when a Groq provider is selected.' >&2
    exit 1
  fi
fi
if [[ ${CLASSFORGE_PUSH_ENABLED:-false} == true ]]; then
  for variable in CLASSFORGE_PUSH_VAPID_PUBLIC_KEY CLASSFORGE_PUSH_VAPID_PRIVATE_KEY CLASSFORGE_PUSH_VAPID_SUBJECT; do
    if [[ -z ${!variable:-} ]]; then
      echo "Required push setting is empty: $variable" >&2; exit 1
    fi
  done
fi
if [[ ${#CLASSFORGE_JWT_SECRET} -lt 64 ]]; then
  echo 'CLASSFORGE_JWT_SECRET must contain at least 64 characters.' >&2
  exit 1
fi
if [[ ! $CLASSFORGE_MONGODB_URI =~ ^mongodb(\+srv)?://[^/]+/[^?/]+ ]]; then
  echo 'MongoDB URI must name a database on a replica set.' >&2
  exit 1
fi
if [[ ! $CLASSFORGE_WEBSOCKET_ALLOWED_ORIGINS =~ ^https://[a-zA-Z0-9.-]+$ ]] || [[ $CLASSFORGE_WEBSOCKET_ALLOWED_ORIGINS == *REPLACE* ]]; then
  echo 'Set a single exact public HTTPS origin for this VM.' >&2
  exit 1
fi
