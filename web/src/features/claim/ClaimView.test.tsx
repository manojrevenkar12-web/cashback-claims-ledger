import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import type { ClaimDetail } from '../../shared/types.ts'
import { ClaimView, excludedLines } from './ClaimView.tsx'

const TEXT = `Bestellbestätigung – Nordlicht Mode
Bestellnummer: NO-48213-77
  1 x Geschenkkarte 25 €                 25,00 €
Gesamtsumme                            25,00 €`

function claim(status: ClaimDetail['status']): ClaimDetail {
  return {
    claimId: 'c2', userId: 'u-2', merchantId: 'm-nordlicht', merchantName: 'Nordlicht Mode', filedAtMillis: Date.UTC(2026, 7, 20),
    status, outcome: 'SUPPORT', reason: 'order_claimed_by_other_account', deskReason: 'order_claimed_by_other_account',
    cashbackCents: 0, orderId: 'NO-48213-77',
    record: {
      ticket: { confirmationText: TEXT, formOrderId: 'NO-48213-77', formOrderDate: '2026-08-03', formAmountCents: 2500 },
      decision: {
        outcome: 'SUPPORT', reason: 'order_claimed_by_other_account', eligibleCents: 0, cashbackCents: 0, reads: 1, reading: null,
        checks: [
          { name: 'reading', passed: true, detail: 'figures add up' },
          { name: 'one claimant', passed: false, detail: 'order also claimed by 1 other account(s)' },
        ],
      },
      resolution: null,
      networkReference: null,
    },
    clicks: [],
  }
}

function reply(body: unknown, status = 200) {
  return Promise.resolve(new Response(JSON.stringify(body), { status, headers: { 'content-type': 'application/json' } }))
}

afterEach(() => vi.restoreAllMocks())

describe('ClaimView', () => {
  it('asks to pick a claim when none is open', () => {
    render(<ClaimView claimId={undefined} onResolved={() => {}} />)
    expect(screen.getByText('Pick a claim.')).toBeTruthy()
  })

  it('shows the checks in order and lets support reject with a reason', async () => {
    const fetchMock = vi.spyOn(globalThis, 'fetch')
      .mockImplementationOnce(() => reply(claim('NEEDS_SUPPORT')))
      .mockImplementationOnce(() => reply({ ...claim('REJECTED'), record: { ...claim('REJECTED').record, resolution: { actor: 'support:demo', verdict: 'REJECT', reason: 'Copied from another account.' } } }))
    const onResolved = vi.fn()
    render(<ClaimView claimId="c2" onResolved={onResolved} />)
    await screen.findByText('Same order claimed by another account')
    const checks = screen.getAllByRole('listitem')
    expect(checks[0].textContent).toContain('reading')
    expect(checks[1].textContent).toContain('did not pass')
    fireEvent.click(screen.getByLabelText('Reject'))
    const submit = screen.getByRole('button', { name: 'Reject' }) as HTMLButtonElement
    expect(submit.disabled).toBe(true)
    fireEvent.change(screen.getByLabelText('What the shopper reads'), { target: { value: 'Copied from another account.' } })
    fireEvent.click(submit)
    await screen.findByText(/support:demo decided/)
    expect(onResolved).toHaveBeenCalled()
    const [, init] = fetchMock.mock.calls[1]
    expect(JSON.parse(String(init?.body))).toEqual({ verdict: 'REJECT', reason: 'Copied from another account.' })
  })

  it('offers a retry when the claim does not load', async () => {
    vi.spyOn(globalThis, 'fetch').mockImplementation(() => reply({ error: 'no claim c9' }, 404))
    render(<ClaimView claimId="c9" onResolved={() => {}} />)
    await waitFor(() => expect(screen.getByRole('alert').textContent).toContain('no claim c9'))
    expect(screen.getByText('Try again')).toBeTruthy()
  })

  it('reads which lines the desk left out', () => {
    const c = claim('FILED')
    c.record.decision.checks = [{ name: 'eligible items', passed: true, detail: 'left out: Geschenkkarte 25 € (gift_card), Aufbauservice (service)' }]
    expect(excludedLines(c)).toEqual(['Geschenkkarte 25 €', 'Aufbauservice'])
    c.record.decision.checks = [{ name: 'eligible items', passed: true, detail: 'all items eligible' }]
    expect(excludedLines(c)).toEqual([])
  })
})
