import { fireEvent, render, screen } from '@testing-library/react'
import { describe, expect, it, vi } from 'vitest'
import type { ClaimList } from '../../shared/types.ts'
import { ClaimQueue } from './ClaimQueue.tsx'

const counts = { NEEDS_SUPPORT: 1, FILED: 4, REJECTED: 2, WAITING: 0, ANSWERED: 0, FILING: 0 }
const list: ClaimList = {
  counts,
  claims: [
    {
      claimId: 'c1', userId: 'u-1', merchantId: 'm-brightcart', merchantName: 'Brightcart', filedAtMillis: Date.UTC(2026, 7, 20),
      status: 'NEEDS_SUPPORT', outcome: 'SUPPORT', reason: 'high_value', deskReason: 'high_value', cashbackCents: 4199, orderId: 'BR-1',
    },
  ],
}

const base = { tab: 'NEEDS_SUPPORT' as const, onTab: () => {}, loading: false, error: undefined, onRetry: () => {}, selected: undefined }

describe('ClaimQueue', () => {
  it('lists claims with their reason and cashback, and selects one', () => {
    const onSelect = vi.fn()
    render(<ClaimQueue {...base} list={list} onSelect={onSelect} />)
    expect(screen.getByText('Large amount, needs approval')).toBeTruthy()
    expect(screen.getByText(/41,99/)).toBeTruthy()
    fireEvent.click(screen.getByText('Brightcart'))
    expect(onSelect).toHaveBeenCalledWith('c1')
  })

  it('says when nothing needs a person', () => {
    render(<ClaimQueue {...base} list={{ counts, claims: [] }} onSelect={() => {}} />)
    expect(screen.getByText('Nothing needs a person.')).toBeTruthy()
  })

  it('shows a skeleton while loading and a retry on failure', () => {
    const onRetry = vi.fn()
    const { rerender } = render(<ClaimQueue {...base} list={undefined} loading onSelect={() => {}} />)
    expect(screen.getByRole('status', { name: 'Loading claims' })).toBeTruthy()
    rerender(<ClaimQueue {...base} list={undefined} error="The server answered 502." onRetry={onRetry} onSelect={() => {}} />)
    fireEvent.click(screen.getByText('Try again'))
    expect(onRetry).toHaveBeenCalled()
  })
})
