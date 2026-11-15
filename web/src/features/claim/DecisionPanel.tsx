import { AnimatePresence, motion, useReducedMotion } from 'framer-motion'
import { useState } from 'react'
import { euros, reason, status as statusName } from '../../shared/format.ts'
import { motion as timing } from '../../shared/tokens.ts'
import type { ClaimDetail, Outcome } from '../../shared/types.ts'
import styles from './Claim.module.css'

const VERDICTS: { verdict: Outcome; label: string; done: string; template: string }[] = [
  { verdict: 'FILE', label: 'File with the network', done: 'Filed', template: 'Checked the confirmation; the claim is valid.' },
  { verdict: 'REJECT', label: 'Reject', done: 'Rejected', template: '' },
  { verdict: 'ALREADY_TRACKED', label: 'Tell them it is tracked', done: 'Answered', template: 'The network has already reported this order.' },
  { verdict: 'WAIT', label: 'Ask them to wait', done: 'Asked to wait', template: 'The network has not reported yet; please check again in a few days.' },
]

interface Props {
  claim: ClaimDetail
  onResolve: (verdict: Outcome, reason: string) => Promise<void>
}

/** What the desk concluded, and for claims that need a person, the four things a person can do. */
export function DecisionPanel({ claim, onResolve }: Props) {
  const d = claim.record.decision
  const [choice, setChoice] = useState<Outcome>()
  const [why, setWhy] = useState('')
  const [busy, setBusy] = useState(false)
  const [failure, setFailure] = useState<string>()
  const still = useReducedMotion()
  const open = claim.status === 'NEEDS_SUPPORT' && !claim.record.resolution

  async function submit() {
    if (!choice) return
    setBusy(true)
    setFailure(undefined)
    try {
      await onResolve(choice, why.trim())
      setChoice(undefined)
    } catch (e) {
      setFailure(e instanceof Error ? e.message : String(e))
    } finally {
      setBusy(false)
    }
  }

  return (
    <aside className={styles.panel} aria-label="Decision">
      <p className={styles.statusLine}>{statusName(claim.status)}</p>
      <h2 className={styles.reason}>{reason(d.reason)}</h2>
      {d.cashbackCents > 0 ? (
        <dl className={styles.figures}>
          <div>
            <dt>Eligible order value</dt>
            <dd>{euros(d.eligibleCents)}</dd>
          </div>
          <div>
            <dt>Cashback</dt>
            <dd className={styles.coin}>{euros(d.cashbackCents)}</dd>
          </div>
        </dl>
      ) : null}
      <p className={styles.meta}>
        Read {d.reads} time{d.reads === 1 ? '' : 's'}. The shopper typed order {claim.record.ticket.formOrderId},{' '}
        {claim.record.ticket.formOrderDate}, {euros(claim.record.ticket.formAmountCents)}.
      </p>
      {claim.record.networkReference ? <p className={styles.meta}>Network reference {claim.record.networkReference}</p> : null}
      {claim.record.resolution ? (
        <p className={styles.resolution}>
          {claim.record.resolution.actor} decided: {claim.record.resolution.reason}
        </p>
      ) : null}

      {open ? (
        <form
          className={styles.verdicts}
          onSubmit={(e) => {
            e.preventDefault()
            void submit()
          }}
        >
          <fieldset>
            <legend>Your decision</legend>
            {VERDICTS.map((v) => (
              <label key={v.verdict} className={styles.verdict}>
                <input
                  type="radio"
                  name="verdict"
                  value={v.verdict}
                  checked={choice === v.verdict}
                  onChange={() => {
                    setChoice(v.verdict)
                    setWhy(v.template)
                  }}
                />
                {v.verdict === 'FILE' && d.cashbackCents > 0 ? `${v.label} at ${euros(d.cashbackCents)}` : v.label}
              </label>
            ))}
          </fieldset>
          <AnimatePresence initial={false}>
            {choice ? (
              <motion.div
                key="why"
                initial={still ? false : { opacity: 0, height: 0 }}
                animate={{ opacity: 1, height: 'auto' }}
                exit={{ opacity: 0, height: 0 }}
                transition={{ duration: timing.panelDuration }}
              >
                <label className={styles.whyLabel}>
                  What the shopper reads
                  <textarea value={why} onChange={(e) => setWhy(e.target.value)} rows={3} required />
                </label>
                <button type="submit" className={styles.primary} disabled={busy || why.trim() === ''}>
                  {busy ? 'Saving…' : VERDICTS.find((v) => v.verdict === choice)?.label}
                </button>
              </motion.div>
            ) : null}
          </AnimatePresence>
          {failure ? (
            <p role="alert" className={styles.failure}>
              Not saved: {failure}
            </p>
          ) : null}
        </form>
      ) : null}
    </aside>
  )
}
