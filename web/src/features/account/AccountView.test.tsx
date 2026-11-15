import { render, screen } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { AccountView } from './AccountView.tsx'

afterEach(() => vi.restoreAllMocks())

describe('AccountView', () => {
  it('shows what a decline after payout leaves owed', async () => {
    vi.spyOn(globalThis, 'fetch').mockResolvedValue(new Response(JSON.stringify({
      userId: 'u-demo', pendingCents: 120, confirmedCents: 0, paidOutCents: 1500, availableCents: -1500, owedCents: 1500,
      rewards: [{ rewardId: 'r1', kind: 'ONLINE', merchantId: 'm-nordlicht', status: 'DECLINED', cashbackCents: 0, baseCents: 18000, reason: 'network declined' }],
    })))
    render(<AccountView />)
    expect(await screen.findByText('Owed after a decline')).toBeTruthy()
    expect(screen.getByText('network declined')).toBeTruthy()
  })

  it('says when a shopper has no cashback yet', async () => {
    vi.spyOn(globalThis, 'fetch').mockResolvedValue(new Response(JSON.stringify({
      userId: 'u-demo', pendingCents: 0, confirmedCents: 0, paidOutCents: 0, availableCents: 0, owedCents: 0, rewards: [],
    })))
    render(<AccountView />)
    expect(await screen.findByText('No cashback for u-demo yet.')).toBeTruthy()
  })
})
