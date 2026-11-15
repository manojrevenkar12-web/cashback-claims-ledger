import type { Reading } from '../../shared/types.ts'
import styles from './Claim.module.css'

/**
 * The confirmation as the shopper pasted it, printed on a slip. Lines the desk left out of the eligible amount, and
 * the voucher line, are marked so a person sees at a glance what the arithmetic did.
 */
export function Receipt({ text, reading, excluded }: { text: string; reading: Reading | null; excluded: string[] }) {
  const lines = text.replace(/\s+$/, '').split('\n')
  const voucher = reading?.voucherCode ?? null
  return (
    <figure className={styles.slip} aria-label="Order confirmation as pasted">
      <pre className={styles.slipText}>
        {lines.map((line, n) => {
          const isVoucher = voucher !== null && line.includes(voucher)
          const isExcluded = excluded.some((d) => d && line.includes(d))
          const mark = isExcluded ? styles.markExcluded : isVoucher ? styles.markVoucher : undefined
          return (
            <span key={n} className={mark}>
              {line}
              {'\n'}
            </span>
          )
        })}
      </pre>
      <figcaption className={styles.slipNote}>
        {reading ? (
          <>
            Read as order {reading.orderId}, {reading.lines.length} line{reading.lines.length === 1 ? '' : 's'}, dated{' '}
            {reading.orderDateText}.
          </>
        ) : (
          'No reading added up, so a person reads this one.'
        )}
      </figcaption>
    </figure>
  )
}
