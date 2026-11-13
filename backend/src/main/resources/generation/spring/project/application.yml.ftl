spring:
  datasource:
    url: jdbc:h2:mem:${model.artifactName};DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE
    username: sa
    password:
  jpa:
    hibernate:
      ddl-auto: create-drop
    open-in-view: false
app:
  assistant:
    text:
      provider: ${r'${ASSISTANT_TEXT_PROVIDER:codex}'}
    voice:
      provider: ${r'${ASSISTANT_VOICE_PROVIDER:whisper-cpp}'}
    codex:
      executable: ${r'${CODEX_EXECUTABLE:codex}'}
      home: ${r'${CODEX_HOME:}'}
      model: ${r'${CODEX_MODEL:gpt-6.1-sol}'}
      request-timeout-millis: ${r'${CODEX_REQUEST_TIMEOUT_MILLIS:90000}'}
    whisper-url: ${r'${WHISPER_URL:http://127.0.0.1:8093}'}
    whisper-language: ${r'${WHISPER_LANGUAGE:es}'}
    groq:
      api-key: ${r'${GROQ_API_KEY:}'}
      base-url: ${r'${GROQ_BASE_URL:https://api.groq.com/openai/v1}'}
      text-model: ${r'${GROQ_TEXT_MODEL:openai/gpt-oss-120b}'}
      speech-model: ${r'${GROQ_SPEECH_MODEL:whisper-large-v3-turbo}'}
      language: ${r'${GROQ_LANGUAGE:es}'}
      request-timeout-millis: ${r'${GROQ_REQUEST_TIMEOUT_MILLIS:90000}'}
    api-base-url: ${r'${APP_API_BASE_URL:http://127.0.0.1:8080}'}
<#if api.authEnabled()>  security:
    jwt-secret: ${r'${JWT_SECRET:classforge-change-me-before-production}'}
    jwt-expiration-seconds: 3600
</#if>
