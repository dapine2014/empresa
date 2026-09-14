export function humanizeAction(action: string | null): string {
  if (!action) return 'Inactivo'
  return action
    .toLowerCase()
    .split('_')
    .map((word) => word.charAt(0).toUpperCase() + word.slice(1))
    .join(' ')
}
