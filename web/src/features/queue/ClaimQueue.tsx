import { euros, date, reason, status as statusName } from '../../shared/format.ts'
import type { ClaimList, Status } from '../../shared/types.ts'
import { EmptyState, ErrorState, Skeleton } from '../../shared/ui/States.tsx'
import styles from './Queue.module.css'

export const TABS: Status[] = ['NEEDS_SUPPORT', 'FILED', 'REJECTED', 'WAITING', 'ANSWERED']

interface Props {
  tab: Status
  onTab: (s: Status) => void
  list: ClaimList | undefined
  loading: boolean
  error: string | undefined
  onRetry: () => void
  selected: string | undefined
  onSelect: (id: string) => void
}

/** The support queue: one tab per status, oldest claim first. */
export function ClaimQueue({ tab, onTab, list, loading, error, onRetry, selected, onSelect }: Props) {
  return (
    <nav className={styles.queue} aria-label="Claims">
      <div className={styles.tabs} role="tablist">
        {TABS.map((s) => (
          <button
            key={s}
            role="tab"
            type="button"
            aria-selected={s === tab}
            className={styles.tab}
            onClick={() => onTab(s)}
          >
            <span>{statusName(s)}</span>
            <span className={styles.count}>{list ? list.counts[s] : '…'}</span>
          </button>
        ))}
      </div>
      {loading && !list ? <Skeleton label="Loading claims" /> : null}
      {error ? <ErrorState what="The claims" message={error} onRetry={onRetry} /> : null}
      {list && !error && list.claims.length === 0 ? (
        <EmptyState
          title={tab === 'NEEDS_SUPPORT' ? 'Nothing needs a person.' : `No claims ${statusName(tab).toLowerCase()}.`}
          hint="New claims land here as shoppers file them; the desk decides most of them on its own."
        />
      ) : null}
      {list && !error ? (
        <ol className={styles.list}>
          {list.claims.map((c) => (
            <li key={c.claimId}>
              <button
                type="button"
                className={styles.item}
                aria-current={c.claimId === selected ? 'true' : undefined}
                onClick={() => onSelect(c.claimId)}
              >
                <span className={styles.shop}>{c.merchantName}</span>
                <span className={styles.amount}>{c.cashbackCents > 0 ? euros(c.cashbackCents) : ''}</span>
                <span className={styles.why}>{reason(c.reason)}</span>
                <span className={styles.when}>
                  Filed {date(c.filedAtMillis)} by {c.userId}
                </span>
              </button>
            </li>
          ))}
        </ol>
      ) : null}
    </nav>
  )
}
