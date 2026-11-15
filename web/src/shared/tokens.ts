/** Numbers the components need in script (motion and skeleton shapes); colours and sizes live in theme.css. */
export const motion = {
  stampStagger: 0.07,
  stampDuration: 0.22,
  stampFrom: 6,
  panelDuration: 0.18,
  ease: [0.2, 0.7, 0.2, 1] as const,
}

export const layout = {
  skeletonRows: 5,
  skeletonWidths: ['92%', '76%', '84%', '60%', '70%'],
  queuePreviewChars: 42,
}

export const config = {
  apiKey: (import.meta.env.VITE_API_KEY as string | undefined) ?? 'support-demo-key',
  locale: 'de-DE',
  currency: 'EUR',
}
