import { createContext, useContext } from 'react'

/**
 * With `agent-platform.security.enabled` false (the compose default), both services skip JWT
 * authentication and read the caller's identity straight from these headers. Switching identity is
 * therefore the main lever for testing authorization and HITL audience routing, so it lives in the
 * top bar rather than buried in settings.
 */
export interface Identity {
  licenseCode: string
  userId: string
  roles: string[]
}

export const ALL_ROLES = [
  'AGENT_EDITOR',
  'AGENT_PUBLISHER',
  'AGENT_ADMIN',
  'PLATFORM_ADMIN',
  'RUN_REQUESTER',
  'RUN_OPERATOR',
  'APPROVER',
  'AGENT_OWNER',
] as const

export const DEFAULT_IDENTITY: Identity = {
  licenseCode: 'DEV_LICENSE',
  userId: 'dev-user',
  roles: ['AGENT_EDITOR', 'AGENT_PUBLISHER', 'AGENT_ADMIN', 'RUN_REQUESTER', 'RUN_OPERATOR', 'APPROVER'],
}

const STORAGE_KEY = 'agent-console.identity'

export function loadIdentity(): Identity {
  try {
    const raw = localStorage.getItem(STORAGE_KEY)
    if (!raw) return DEFAULT_IDENTITY
    const parsed = JSON.parse(raw) as Partial<Identity>
    return {
      licenseCode: parsed.licenseCode?.trim() || DEFAULT_IDENTITY.licenseCode,
      userId: parsed.userId?.trim() || DEFAULT_IDENTITY.userId,
      roles: Array.isArray(parsed.roles) && parsed.roles.length ? parsed.roles : DEFAULT_IDENTITY.roles,
    }
  } catch {
    return DEFAULT_IDENTITY
  }
}

export function saveIdentity(identity: Identity): void {
  try {
    localStorage.setItem(STORAGE_KEY, JSON.stringify(identity))
  } catch {
    // A dev console that cannot persist identity is still perfectly usable.
  }
}

export const IdentityContext = createContext<Identity>(DEFAULT_IDENTITY)

export function useIdentity(): Identity {
  return useContext(IdentityContext)
}
