export type Status = 'FILING' | 'FILED' | 'ANSWERED' | 'WAITING' | 'REJECTED' | 'NEEDS_SUPPORT'
export type Outcome = 'FILE' | 'ALREADY_TRACKED' | 'WAIT' | 'REJECT' | 'SUPPORT'

export interface ClaimSummary {
  claimId: string
  userId: string
  merchantId: string
  merchantName: string
  filedAtMillis: number
  status: Status
  outcome: Outcome
  reason: string
  deskReason: string
  cashbackCents: number
  orderId: string | null
}

export interface Check {
  name: string
  passed: boolean
  detail: string
}

export interface Reading {
  merchantName: string
  orderId: string
  orderDateText: string
  lines: { description: string; amountCents: number }[]
  voucherCode: string | null
  discountCents: number
  shippingCents: number
  totalCents: number
}

export interface ClaimDetail extends ClaimSummary {
  record: {
    ticket: { confirmationText: string; formOrderId: string; formOrderDate: string; formAmountCents: number }
    decision: {
      outcome: Outcome
      reason: string
      eligibleCents: number
      cashbackCents: number
      checks: Check[]
      reads: number
      reading: Reading | null
    }
    resolution: { actor: string; verdict: Outcome; reason: string } | null
    networkReference: string | null
  }
  clicks: { clickId: string; atMillis: number }[]
}

export interface ClaimList {
  claims: ClaimSummary[]
  counts: Record<Status, number>
}

export interface Reward {
  rewardId: string
  kind: 'ONLINE' | 'IN_STORE'
  merchantId: string
  status: 'PENDING' | 'CONFIRMED' | 'DECLINED'
  cashbackCents: number
  baseCents: number
  reason: string
}

export interface Account {
  userId: string
  pendingCents: number
  confirmedCents: number
  paidOutCents: number
  availableCents: number
  owedCents: number
  rewards: Reward[]
}

export interface DesignScore {
  label: string
  claims: number
  rightShare: number
  filed: number
  invalidFiledShare: number
  wrongAmounts: number
  validDeniedCents: number
  recoveredCents: number
  recoverableCents: number
  supportHours: number
  late: number
}

export interface LedgerScore {
  users: number
  usersExactShare: number
  centsOff: number
  wrongStatus: number
}

export interface Report {
  world: { name: string; claims: number; shoppers: number; onlineOrders: number }
  claims: { designs: Record<string, DesignScore> }
  ledger: { designs: Record<string, LedgerScore>; eventsDelivered: number; repeatedDeliveries: number; outOfOrderDeliveries: number }
}

export interface Results {
  dev: Report | null
  heldout: Report | null
}
