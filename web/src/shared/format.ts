import { config } from './tokens.ts'

const money = new Intl.NumberFormat(config.locale, { style: 'currency', currency: config.currency })
const day = new Intl.DateTimeFormat(config.locale, { day: 'numeric', month: 'short', year: 'numeric', timeZone: 'UTC' })
const share = new Intl.NumberFormat(config.locale, { style: 'percent', maximumFractionDigits: 1 })

export const euros = (cents: number) => money.format(cents / 100)
export const date = (millis: number) => day.format(new Date(millis))
export const percent = (value: number) => share.format(value)

/** The desk's reason codes, in the words a support person uses. */
const reasons: Record<string, string> = {
  eligible: 'Eligible',
  high_value: 'Large amount, needs approval',
  unreadable: 'Confirmation could not be read',
  other_merchant: 'Confirmation is from another shop',
  order_claimed_by_other_account: 'Same order claimed by another account',
  order_after_claim: 'Order date is after the claim',
  already_tracked: 'Already tracked',
  too_early: 'Too early, network still reporting',
  too_late: 'Claim window closed',
  no_qualifying_click: 'No click through us in the cookie window',
  foreign_voucher: 'Used a code we did not publish',
  excluded_items: 'Only excluded items',
}
export const reason = (code: string) => reasons[code] ?? code

const statuses: Record<string, string> = {
  NEEDS_SUPPORT: 'Needs a person',
  FILED: 'Filed',
  FILING: 'Filing',
  REJECTED: 'Rejected',
  WAITING: 'Told to wait',
  ANSWERED: 'Already tracked',
}
export const status = (code: string) => statuses[code] ?? code
