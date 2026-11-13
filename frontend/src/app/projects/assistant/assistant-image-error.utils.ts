interface ImageAnalysisError {
  readonly status: number;
  readonly error?: {
    readonly error?: string;
    readonly visionReason?: string;
    readonly retryAfterSeconds?: unknown;
  };
}

export function formatAssistantImageError(response: ImageAnalysisError): string {
  if (response.status === 409) {
    return 'El proyecto cambió. Analiza la imagen nuevamente.';
  }
  if (response.status === 429 || response.error?.error === 'GROQ_RATE_LIMIT') {
    const retry = response.error?.retryAfterSeconds;
    const codex = response.error?.error === 'CODEX_RATE_LIMIT';
    const validRetry = typeof retry === 'number' && Number.isInteger(retry) && retry > 0 && retry <= 86400;
    if (codex && !validRetry) {
      return 'Se alcanzó un límite de Codex. Revisa el uso de tu cuenta antes de reintentar.';
    }
    const wait = validRetry
      ? `${retry} segundos`
      : 'un momento';
    return `Se alcanzó la cuota de ${codex ? 'Codex' : 'Groq'}. Espera ${wait} y vuelve a analizar la imagen.`;
  }
  if (response.error?.visionReason === 'OUTPUT_CONTRACT') {
    return 'La IA no devolvió una lectura válida. Vuelve a analizar la imagen.';
  }
  if (response.error?.visionReason === 'TRANSPORT') {
    return 'No se pudo analizar la imagen. Intenta nuevamente.';
  }
  return 'No se pudo completar el análisis.';
}
