import { useState } from 'react'
import { useMutation } from '@tanstack/react-query'
import { api } from '../api/client'

interface Message {
  from: 'you' | 'ceo'
  text: string
}

export default function ChatPage() {
  const [messages, setMessages] = useState<Message[]>([])
  const [input, setInput] = useState('')

  const mutation = useMutation({
    mutationFn: (message: string) => api.chat(message),
    onSuccess: (response) => {
      setMessages((prev) => [...prev, { from: 'ceo', text: response.response }])
    },
    onError: () => {
      setMessages((prev) => [...prev, { from: 'ceo', text: 'No pude procesar ese mensaje.' }])
    },
  })

  function send() {
    const text = input.trim()
    if (!text) return
    setMessages((prev) => [...prev, { from: 'you', text }])
    setInput('')
    mutation.mutate(text)
  }

  return (
    <div className="chat-page">
      <h1>Empresa Chat</h1>
      <p className="hint">
        Consultas reales ("¿qué agentes están trabajando?", "¿qué misiones necesitan mi aprobación?") y decisiones
        reales ("aprueba la misión MISSION-XXX porque...") — nunca texto libre inventado; el chat no ejecuta nada
        directamente, pasa por la misma gobernanza que el resto de la empresa.
      </p>
      <div className="chat-history">
        {messages.length === 0 && <p className="hint">Todavía no escribiste nada.</p>}
        {messages.map((m, i) => (
          <div key={i} className={`chat-message chat-${m.from}`}>
            <strong>{m.from === 'you' ? 'Tú' : 'CEO'}:</strong> {m.text}
          </div>
        ))}
        {mutation.isPending && <div className="chat-message chat-ceo">CEO está pensando...</div>}
      </div>
      <form
        className="chat-input"
        onSubmit={(e) => {
          e.preventDefault()
          send()
        }}
      >
        <input
          value={input}
          onChange={(e) => setInput(e.target.value)}
          placeholder="Preguntale algo real a la empresa..."
        />
        <button type="submit" disabled={mutation.isPending || !input.trim()}>
          Enviar
        </button>
      </form>
    </div>
  )
}
