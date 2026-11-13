import assert from 'node:assert/strict';
import test from 'node:test';
import { formatAssistantImageError } from './assistant-image-error.utils.ts';

test('shows Codex quota without attributing it to another provider', () => {
  assert.equal(formatAssistantImageError({ status: 429, error: { error: 'CODEX_RATE_LIMIT', retryAfterSeconds: 12 } }),
    'Se alcanzó la cuota de Codex. Espera 12 segundos y vuelve a analizar la imagen.');
});

test('asks to review Codex usage when its reset time is unavailable', () => {
  assert.equal(formatAssistantImageError({ status: 429, error: { error: 'CODEX_RATE_LIMIT', retryAfterSeconds: 0 } }),
    'Se alcanzó un límite de Codex. Revisa el uso de tu cuenta antes de reintentar.');
});

test('shows Groq quota and server retry time instead of blaming the image', () => {
  assert.equal(formatAssistantImageError({ status: 429, error: { error: 'GROQ_RATE_LIMIT', retryAfterSeconds: 19 } }),
    'Se alcanzó la cuota de Groq. Espera 19 segundos y vuelve a analizar la imagen.');
});

test('does not render arbitrary provider messages or invalid retry values', () => {
  for (const retryAfterSeconds of [undefined, -4, Infinity, 'provider-secret', 90000]) {
    assert.equal(formatAssistantImageError({ status: 429, error: { retryAfterSeconds } }),
      'Se alcanzó la cuota de Groq. Espera un momento y vuelve a analizar la imagen.');
  }
});

test('keeps conflict and contract errors distinct from quota failures', () => {
  assert.match(formatAssistantImageError({ status: 409 }), /El proyecto cambió/);
  assert.match(formatAssistantImageError({ status: 503, error: { visionReason: 'OUTPUT_CONTRACT' } }), /lectura válida/);
  assert.match(formatAssistantImageError({ status: 503, error: { visionReason: 'TRANSPORT' } }), /Intenta nuevamente/);
  assert.equal(formatAssistantImageError({ status: 500 }), 'No se pudo completar el análisis.');
});
