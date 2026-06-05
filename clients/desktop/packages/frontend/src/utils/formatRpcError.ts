/** Extract a human-readable message from RPC / JSON-RPC errors. */
export function formatRpcError(err: unknown): string {
  if (err instanceof Error) {
    return err.message || err.name
  }
  if (typeof err === 'string') {
    return err
  }
  if (err && typeof err === 'object') {
    const o = err as Record<string, unknown>
    if (typeof o.message === 'string' && o.message) return o.message
    if (typeof o.reason === 'string' && o.reason) return o.reason
    if (typeof o.error === 'string' && o.error) return o.error
    if (o.data && typeof o.data === 'object') {
      const d = o.data as Record<string, unknown>
      if (typeof d.message === 'string' && d.message) return d.message
      if (typeof d.hint === 'string' && d.hint) return d.hint
    }
    try {
      return JSON.stringify(err)
    } catch {
      return String(err)
    }
  }
  return String(err ?? 'unknown error')
}
