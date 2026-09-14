import { NavLink, Outlet } from 'react-router-dom'
import { useQuery } from '@tanstack/react-query'
import { api } from '../api/client'
import Logo from './Logo'

function HealthBadge() {
  const { data, isError } = useQuery({
    queryKey: ['health'],
    queryFn: api.health,
    refetchInterval: 15_000,
    retry: false,
  })

  const up = !isError && data?.status === 'UP'

  return (
    <span className={`health-badge ${up ? 'health-up' : 'health-down'}`}>
      {up ? '🟢 OPERATING' : '🔴 DOWN'}
    </span>
  )
}

export default function Layout() {
  return (
    <div className="app-shell">
      <aside className="sidebar">
        <div className="brand">
          <Logo size={22} />
          FORJAI
        </div>
        <nav>
          <NavLink to="/" end>
            Dashboard
          </NavLink>
          <NavLink to="/chat">Chat</NavLink>
          <NavLink to="/agents">Agents</NavLink>
          <NavLink to="/missions">Missions</NavLink>
          <NavLink to="/activity">Activity</NavLink>
          <NavLink to="/settings">Settings</NavLink>
        </nav>
      </aside>
      <div className="main">
        <header className="topbar">
          <HealthBadge />
        </header>
        <main className="content">
          <Outlet />
        </main>
      </div>
    </div>
  )
}
