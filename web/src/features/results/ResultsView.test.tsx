import { render, screen } from '@testing-library/react'
import { describe, expect, it } from 'vitest'
import type { Report } from '../../shared/types.ts'
import { ReportTables } from './ResultsView.tsx'

const score = (label: string, rightShare: number, supportHours: number) => ({
  label, claims: 100, rightShare, filed: 30, invalidFiledShare: 0.01, wrongAmounts: 0, validDeniedCents: 1234,
  recoveredCents: 0, recoverableCents: 0, supportHours, late: 0,
})

const report: Report = {
  world: { name: 'dev', claims: 1845, shoppers: 6000, onlineOrders: 14000 },
  claims: { designs: { desk: score('Model reads, code decides', 0.9933, 15.8), llm_alone: score('A model reads and decides alone', 0.6743, 0) } },
  ledger: {
    designs: { ledger: { users: 10, usersExactShare: 1, centsOff: 0, wrongStatus: 0 }, arrival_order: { users: 10, usersExactShare: 0.9, centsOff: 302143, wrongStatus: 578 } },
    eventsDelivered: 35968, repeatedDeliveries: 3873, outOfOrderDeliveries: 1258,
  },
}

describe('ReportTables', () => {
  it('puts the desk first and shows the ledger comparison', () => {
    render(<ReportTables report={report} />)
    const rows = screen.getAllByRole('row')
    expect(rows[1].textContent).toContain('Model reads, code decides')
    expect(rows[1].textContent).toContain('99,3')
    expect(screen.getByText('Apply each event as it arrives')).toBeTruthy()
    expect(screen.getByText(/3.021,43/)).toBeTruthy()
  })
})
