import { config } from './tokens.ts'
import type { Account, ClaimDetail, ClaimList, Outcome, Results, Status } from './types.ts'

async function call<T>(path: string, init?: RequestInit): Promise<T> {
  const res = await fetch(path, {
    ...init,
    headers: { 'X-Api-Key': config.apiKey, 'content-type': 'application/json', ...(init?.headers ?? {}) },
  })
  const text = await res.text()
  const body = text ? JSON.parse(text) : {}
  if (!res.ok) {
    throw new Error(body.error ?? `The server answered ${res.status}.`)
  }
  return body as T
}

export const api = {
  claims: (status?: Status) => call<ClaimList>(`/api/claims${status ? `?status=${status}` : ''}`),
  claim: (id: string) => call<ClaimDetail>(`/api/claims/${encodeURIComponent(id)}`),
  resolve: (id: string, verdict: Outcome, reason: string) =>
    call<ClaimDetail>(`/api/claims/${encodeURIComponent(id)}/resolution`, {
      method: 'POST',
      body: JSON.stringify({ verdict, reason }),
    }),
  account: (userId: string) => call<Account>(`/api/users/${encodeURIComponent(userId)}/account`),
  results: () => call<Results>('/api/results'),
}
