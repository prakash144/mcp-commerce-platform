const STORAGE_KEY = 'apnakart-correlation'

let _correlationId: string | null = null

export function correlationId(): string {
  if (_correlationId) return _correlationId
  const existing = window.localStorage.getItem(STORAGE_KEY)
  if (existing) {
    _correlationId = existing
    return existing
  }
  const fresh = crypto.randomUUID()
  _correlationId = fresh
  window.localStorage.setItem(STORAGE_KEY, fresh)
  return fresh
}

export function sendEvent(evt: string, data?: Record<string, unknown>): void {
  fetch('/api/telemetry', {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
      'X-Correlation-Id': correlationId(),
    },
    body: JSON.stringify({ evt, ...data }),
  }).catch(() => {
    // telemetry is best-effort — never block the UI on it
  })
}