import { useState } from 'react'
import { AccountView } from './features/account/AccountView.tsx'
import { ClaimView } from './features/claim/ClaimView.tsx'
import { ClaimQueue } from './features/queue/ClaimQueue.tsx'
import { ResultsView } from './features/results/ResultsView.tsx'
import { api } from './shared/api.ts'
import type { Status } from './shared/types.ts'
import { useResource } from './shared/useResource.ts'
import styles from './App.module.css'

type View = 'desk' | 'accounts' | 'results'
const VIEWS: { id: View; label: string }[] = [
  { id: 'desk', label: 'Claims' },
  { id: 'accounts', label: 'Accounts' },
  { id: 'results', label: 'Results' },
]

export function App() {
  const [view, setView] = useState<View>('desk')
  const [tab, setTab] = useState<Status>('NEEDS_SUPPORT')
  const [selected, setSelected] = useState<string>()
  const queue = useResource(() => api.claims(tab), [tab])

  return (
    <div className={styles.app}>
      <header className={styles.bar}>
        <p className={styles.brand}>Missing-cashback desk</p>
        <nav aria-label="Views" className={styles.views}>
          {VIEWS.map((v) => (
            <button key={v.id} type="button" aria-pressed={view === v.id} onClick={() => setView(v.id)}>
              {v.label}
            </button>
          ))}
        </nav>
      </header>
      {view === 'desk' ? (
        <main className={styles.desk}>
          <ClaimQueue
            tab={tab}
            onTab={(s) => {
              setTab(s)
              setSelected(undefined)
            }}
            list={queue.data}
            loading={queue.loading}
            error={queue.error}
            onRetry={queue.retry}
            selected={selected}
            onSelect={setSelected}
          />
          <ClaimView claimId={selected} onResolved={() => void queue.retry()} />
        </main>
      ) : null}
      {view === 'accounts' ? (
        <main className={styles.single}>
          <AccountView />
        </main>
      ) : null}
      {view === 'results' ? (
        <main className={styles.single}>
          <ResultsView />
        </main>
      ) : null}
    </div>
  )
}
