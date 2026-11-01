import { useId } from 'react'

// Original vector illustration, for the fictional demo products only.
export default function Backpack({ variant = 'BP-A', className = '' }: { variant?: string; className?: string }) {
  const uid = useId().replaceAll(':', '')
  const green = variant === 'BP-A'
  const color = green ? '#4f695e' : '#b5a98e'
  const dark = green ? '#273e35' : '#7d7059'
  return <svg className={className} viewBox="0 0 240 270" fill="none" aria-hidden="true">
    <defs>
      <linearGradient id={`${uid}-body`} x1="64" y1="64" x2="195" y2="245" gradientUnits="userSpaceOnUse">
        <stop stopColor={green ? '#73897c' : '#d5cbb6'} /><stop offset="1" stopColor={color} />
      </linearGradient>
      <linearGradient id={`${uid}-pocket`} x1="85" y1="158" x2="154" y2="246" gradientUnits="userSpaceOnUse">
        <stop stopColor={green ? '#607c6d' : '#c6bba3'} /><stop offset="1" stopColor={green ? '#465f51' : '#a89a7f'} />
      </linearGradient>
      <filter id={`${uid}-shadow`} x="-50%" y="-100%" width="200%" height="300%"><feGaussianBlur stdDeviation="7" /></filter>
    </defs>
    <ellipse cx="122" cy="251" rx="63" ry="7" fill={dark} opacity=".16" filter={`url(#${uid}-shadow)`} />
    <path d="M77 68C43 74 44 165 57 212l14-5C59 150 59 90 85 88M161 68c35 6 34 97 22 144l-15-6c12-55 12-116-15-119" stroke={dark} strokeWidth="12" strokeLinecap="round" />
    <path d="M102 63V48c0-19 36-19 36 0v15" stroke={dark} strokeWidth="9" />
    <path d="M64 107c0-33 21-54 57-54s58 21 58 54l10 114c2 19-12 25-66 25s-70-6-68-25l9-114Z" fill={`url(#${uid}-body)`} stroke={dark} strokeWidth="1.5" />
    <path d="M76 115c1-29 15-45 45-45s45 16 46 45" stroke={dark} strokeWidth="3" opacity=".65" />
    <path d="M79 115c1-25 14-41 42-41s42 16 43 41" stroke="white" strokeWidth="1" opacity=".3" />
    <path d="m163 110 3 15" stroke={green ? '#d3c5a2' : '#ede1bf'} strokeWidth="3" strokeLinecap="round" />
    <rect x="77" y="157" width="90" height="71" rx="15" fill={`url(#${uid}-pocket)`} stroke={dark} strokeWidth="1" />
    <path d="M80 172h83" stroke={dark} strokeWidth="2.5" /><path d="m155 172 1 13" stroke={green ? '#d3c5a2' : '#ede1bf'} strokeWidth="3" strokeLinecap="round" />
    <rect x="108" y="113" width="28" height="16" rx="2" fill={green ? '#c6c7ab' : '#e9e1ce'} />
    <path d="m117 122 4-5 5 5" stroke={dark} strokeWidth="1.5" />
    <path d="M63 150c-7 1-11 6-10 15l5 48 16-2-4-53c0-6-3-9-7-8Z" fill={color} stroke={dark} strokeWidth="1" />
    {green && <path d="M178 151c8 1 12 6 11 15l-4 47-16-2 3-52c0-6 2-9 6-8Z" fill={color} stroke={dark} strokeWidth="1" />}
    <path d="M88 235c19 4 49 5 68 0" stroke="white" opacity=".16" />
  </svg>
}
