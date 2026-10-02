import { useState } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { api } from '../api/client'
import type { DatabaseConnectionForm, DatabaseConnectionInfo } from '../api/types'

const EMPTY: DatabaseConnectionForm = {
  name: '', engine: 'POSTGRESQL', host: '', port: 5432, database: '', username: '', password: '',
  tls: 'REQUIRE', caCertPem: '', environment: 'TEST',
}

/** Spec 2026-10-02 §4: conexiones del fundador; la clave nunca vuelve del servidor (solo ****1234). */
export function DatabasesSettings() {
  const queryClient = useQueryClient()
  const list = useQuery({ queryKey: ['databases'], queryFn: api.databases })
  const [form, setForm] = useState<DatabaseConnectionForm>(EMPTY)
  const [editing, setEditing] = useState<string | null>(null)
  const save = useMutation({
    mutationFn: () => (editing ? api.updateDatabase(editing, form) : api.createDatabase(form)),
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: ['databases'] })
      setForm(EMPTY)
      setEditing(null)
    },
  })
  const test = useMutation({ mutationFn: (id: string) => api.testDatabase(id),
    onSuccess: () => void queryClient.invalidateQueries({ queryKey: ['databases'] }) })
  const remove = useMutation({ mutationFn: (id: string) => api.deleteDatabase(id),
    onSuccess: () => void queryClient.invalidateQueries({ queryKey: ['databases'] }) })
  const edit = (c: DatabaseConnectionInfo) => {
    setEditing(c.id)
    setForm({ name: c.name, engine: 'POSTGRESQL', host: c.host, port: c.port, database: c.database,
      username: c.username, password: '', tls: c.tls, caCertPem: '', environment: c.environment })
  }
  const set = (key: keyof DatabaseConnectionForm) => (e: { target: { value: string } }) =>
    setForm({ ...form, [key]: key === 'port' ? Number(e.target.value) : e.target.value })

  return (
    <section>
      <h2>Bases de datos</h2>
      <p className="hint">Diego crea las bases con estas credenciales. La clave se guarda cifrada y nunca llega a un modelo.</p>
      <table>
        <tbody>
          {(list.data ?? []).map((c) => (
            <tr key={c.id}>
              <td>{c.name}</td>
              <td>{c.engine} · {c.environment} · TLS {c.tls}</td>
              <td>{c.username}@{c.host}:{c.port}/{c.database}</td>
              <td>{c.passwordHint}</td>
              <td>{c.lastCheckResult ?? '—'}</td>
              <td>
                <button onClick={() => test.mutate(c.id)}>Probar</button>
                <button onClick={() => edit(c)}>Editar</button>
                <button onClick={() => remove.mutate(c.id)}>Eliminar</button>
              </td>
            </tr>
          ))}
        </tbody>
      </table>
      {remove.isError && <p className="error">{remove.error.message}</p>}
      <div className="decision-form">
        <h3>{editing ? 'Editar conexión' : 'Agregar conexión'}</h3>
        <input placeholder="nombre (p. ej. citas-dev-aws)" value={form.name} onChange={set('name')} />
        <input placeholder="host" value={form.host} onChange={set('host')} />
        <input type="number" placeholder="puerto" value={form.port} onChange={set('port')} />
        <input placeholder="base de datos" value={form.database} onChange={set('database')} />
        <input placeholder="usuario" value={form.username} onChange={set('username')} />
        <input type="password" placeholder={editing ? 'clave (vacía = conservar)' : 'clave'} value={form.password}
          onChange={set('password')} />
        <select value={form.tls} onChange={set('tls')}>
          <option value="REQUIRE">TLS obligatorio (AWS RDS)</option>
          <option value="VERIFY_FULL">TLS con verificación (requiere CA)</option>
          <option value="DISABLE">Sin TLS</option>
        </select>
        {form.tls === 'VERIFY_FULL' && (
          <textarea placeholder="Certificado CA en PEM" value={form.caCertPem} onChange={set('caCertPem')} />
        )}
        <select value={form.environment} onChange={set('environment')}>
          <option value="TEST">TEST</option>
          <option value="PRODUCTION">PRODUCTION</option>
        </select>
        <button disabled={save.isPending} onClick={() => save.mutate()}>Probar y guardar</button>
        {editing && <button onClick={() => { setEditing(null); setForm(EMPTY) }}>Cancelar</button>}
        {save.isError && <p className="error">{save.error.message}</p>}
        {test.isSuccess && <p className="hint">{test.data.result}</p>}
      </div>
    </section>
  )
}
