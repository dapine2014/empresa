export default function Logo({ size = 22 }: { size?: number }) {
  return (
    <svg width={size} height={size} viewBox="0 0 32 32" aria-hidden="true">
      <rect width="32" height="32" rx="8" fill="#4f8dfd" />
      <path d="M10 7h13v4.2h-8.7v4.1h7.6v4.1h-7.6V25H10V7z" fill="#0b0f14" />
      <circle cx="24.5" cy="8" r="2.6" fill="#ffffff" />
    </svg>
  )
}
