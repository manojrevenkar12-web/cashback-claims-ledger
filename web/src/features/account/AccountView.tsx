import { useState } from 'react'
import { api } from '../../shared/api.ts'
import { euros } from '../../shared/format.ts'
import { useResource } from '../../shared/useResource.ts'
import { EmptyState, ErrorState, Skeleton } from '../../shared/ui/States.tsx'
import styles from './Account.module.css'

const STATUS: Record<string, string> = { PENDING: 'Pending', CONFIRMED: 'Confirmed', DECLINED: 'Declined' }

/** A shopper's ledger as support sees it: what is pending, what is confirmed, and every reward behind the totals. */
export function AccountView() {
  const [draft, setDraft] = useState('u-demo')
  const [userId, setUserId] = useState('u-demo')
  const { data, error, loading, retry } = useResource(() => api.account(userId), [userId])
  return (
    <section className={styles.view} aria-label="Shopper account">
      <form
        className={styles.lookup}
        onSubmit={(e) => {
          e.preventDefault()
          setUserId(draft.trim())
        }}
      >
        <label>
          Shopper
          <input value={draft} onChange={(e) => setDraft(e.target.value)} />
        </label>
        <button type="submit">Open account</button>
      </form>
      {loading && !data ? <Skeleton label="Loading the account" /> : null}
      {error ? <ErrorState what="The account" message={error} onRetry={retry} /> : null}
      {data && !error ? (
        <>
          <dl className={styles.totals}>
            <div>
              <dt>Pending</dt>
              <dd>{euros(data.pendingCents)}</dd>
            </div>
            <div>
              <dt>Confirmed</dt>
              <dd>{euros(data.confirmedCents)}</dd>
            </div>
            <div>
              <dt>Paid out</dt>
              <dd>{euros(data.paidOutCents)}</dd>
            </div>
            <div>
              <dt>{data.owedCents > 0 ? 'Owed after a decline' : 'Available'}</dt>
              <dd className={styles.coin}>{euros(data.owedCents > 0 ? data.owedCents : data.availableCents)}</dd>
            </div>
          </dl>
          {data.rewards.length === 0 ? (
            <EmptyState title={`No cashback for ${data.userId} yet.`} hint="Rewards appear when a network or the card scheme reports a purchase." />
          ) : (
            <table className={styles.rewards}>
              <thead>
                <tr>
                  <th scope="col">Reward</th>
                  <th scope="col">Where</th>
                  <th scope="col">State</th>
                  <th scope="col">On</th>
                  <th scope="col">Cashback</th>
                </tr>
              </thead>
              <tbody>
                {data.rewards.map((r) => (
                  <tr key={r.rewardId}>
                    <td>{r.rewardId}</td>
                    <td>{r.kind === 'IN_STORE' ? 'In store' : 'Online'}, {r.merchantId.replace(/^m-/, '')}</td>
                    <td>
                      {STATUS[r.status]}
                      <span className={styles.why}>{r.reason}</span>
                    </td>
                    <td>{euros(r.baseCents)}</td>
                    <td className={styles.coin}>{euros(r.cashbackCents)}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          )}
        </>
      ) : null}
    </section>
  )
}
