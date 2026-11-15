import { api } from '../../shared/api.ts'
import { date } from '../../shared/format.ts'
import type { ClaimDetail, Outcome } from '../../shared/types.ts'
import { useResource } from '../../shared/useResource.ts'
import { EmptyState, ErrorState, Skeleton } from '../../shared/ui/States.tsx'
import { Checks } from './Checks.tsx'
import { DecisionPanel } from './DecisionPanel.tsx'
import { Receipt } from './Receipt.tsx'
import styles from './Claim.module.css'

/** Lines the desk left out: the "eligible items" check names them as "description (category)". */
export function excludedLines(claim: ClaimDetail): string[] {
  const check = claim.record.decision.checks.find((c) => c.name === 'eligible items')
  if (!check) return []
  const left = check.detail.replace(/^left out: |^nothing eligible: /, '')
  if (left === check.detail) return []
  return left.split(/, (?=[^()]*\()/).map((part) => part.replace(/ \([a-z_]+\)$/, ''))
}

export function ClaimView({ claimId, onResolved }: { claimId: string | undefined; onResolved: () => void }) {
  const { data, setData, error, loading, retry } = useResource<ClaimDetail | undefined>(
    () => (claimId ? api.claim(claimId) : Promise.resolve(undefined)),
    [claimId],
  )

  if (!claimId) {
    return (
      <section className={styles.view}>
        <EmptyState title="Pick a claim." hint="The confirmation, the desk's checks and the decision open here." />
      </section>
    )
  }
  if (loading && !data) {
    return (
      <section className={styles.view}>
        <Skeleton label="Loading the claim" />
      </section>
    )
  }
  if (error || !data) {
    return (
      <section className={styles.view}>
        <ErrorState what="The claim" message={error ?? 'It was not found.'} onRetry={retry} />
      </section>
    )
  }

  async function resolve(verdict: Outcome, why: string) {
    const next = await api.resolve(data!.claimId, verdict, why)
    setData(next)
    onResolved()
  }

  return (
    <section className={styles.view} aria-label={`Claim ${data.claimId}`}>
      <header className={styles.head}>
        <h1 className={styles.title}>{data.merchantName}</h1>
        <p className={styles.sub}>
          Claim {data.claimId} from {data.userId}, filed {date(data.filedAtMillis)}.{' '}
          {data.clicks.length === 0
            ? 'No clicks through us to this shop.'
            : `${data.clicks.length} click${data.clicks.length === 1 ? '' : 's'} through us, last on ${date(
                data.clicks[data.clicks.length - 1].atMillis,
              )}.`}
        </p>
      </header>
      <div className={styles.body}>
        <Receipt text={data.record.ticket.confirmationText} reading={data.record.decision.reading} excluded={excludedLines(data)} />
        <Checks checks={data.record.decision.checks} claimId={data.claimId} />
      </div>
      <DecisionPanel claim={data} onResolve={resolve} />
    </section>
  )
}
