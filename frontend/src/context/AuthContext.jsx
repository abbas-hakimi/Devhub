import { createContext, useCallback, useContext, useEffect, useState } from 'react';
import { useQueryClient } from '@tanstack/react-query';
import { authSession, normalizeError } from '../services/api';

const AuthContext = createContext(null);
export function AuthProvider({ children }) {
  const [user, setUser] = useState(null);
  const [status, setStatus] = useState('restoring');
  const [notice, setNotice] = useState('');
  const queryClient = useQueryClient();
  useEffect(() => authSession.subscribe((nextUser) => {
    setUser(nextUser);
    setStatus(nextUser ? 'authenticated' : 'anonymous');
    if (!nextUser) queryClient.clear();
  }), [queryClient]);
  useEffect(() => {
    let active = true;
    authSession.refresh().then(() => authSession.me()).then((current) => {
      if (active) { setUser(current); setStatus('authenticated'); }
    }).catch((error) => {
      if (active) {
        const normalized = normalizeError(error);
        if (normalized.status !== 401) setNotice('We could not restore your session. Please sign in.');
        setStatus('anonymous');
      }
    });
    return () => { active = false; };
  }, []);
  const login = useCallback(async (values) => {
    const data = await authSession.login(values);
    const current = await authSession.me();
    setUser(current); setStatus('authenticated'); setNotice('');
    return data;
  }, []);
  const logout = useCallback(async () => {
    try { await authSession.logout(); setNotice('You have been signed out.'); }
    catch { setNotice('Signed out locally. Server logout could not be confirmed; retry when connected.'); }
  }, []);
  return <AuthContext.Provider value={{ user, status, login, logout, notice, setNotice }}>{children}</AuthContext.Provider>;
}
export function useAuth() { return useContext(AuthContext); }
