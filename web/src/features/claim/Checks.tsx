import { motion, useReducedMotion } from 'framer-motion'
import { motion as timing } from '../../shared/tokens.ts'
import type { Check } from '../../shared/types.ts'
import styles from './Claim.module.css'

/**
 * The desk's checks in the order it ran them. They stamp in one after another when a claim opens: the one moment of
 * motion on the page, because the order is the reasoning.
 */
export function Checks({ checks, claimId }: { checks: Check[]; claimId: string }) {
  const still = useReducedMotion()
  return (
    <ol className={styles.checks} aria-label="Checks the desk ran, in order">
      {checks.map((c, n) => (
        <motion.li
          key={`${claimId}-${c.name}`}
          className={c.passed ? styles.checkPass : styles.checkFail}
          initial={still ? false : { opacity: 0, x: timing.stampFrom }}
          animate={{ opacity: 1, x: 0 }}
          transition={{ delay: n * timing.stampStagger, duration: timing.stampDuration, ease: timing.ease }}
        >
          <span className={styles.checkMark} aria-hidden="true">
            {c.passed ? '✓' : '✕'}
          </span>
          <span className={styles.checkName}>
            {c.name}
            <span className={styles.srOnly}>{c.passed ? ' passed' : ' did not pass'}</span>
          </span>
          <span className={styles.checkDetail}>{c.detail}</span>
        </motion.li>
      ))}
    </ol>
  )
}
