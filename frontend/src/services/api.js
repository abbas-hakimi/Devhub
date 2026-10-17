import axios from 'axios';

export function normalizeError(error) {
  return { status: error.response?.status || 0,
    message: error.response?.data?.error || (error.code === 'ERR_CANCELED' ? 'Request cancelled.' : 'Unable to connect. Please try again.'),
    fields: error.response?.data?.fields || {} };
}

// One module instance per browser tab. Tokens never enter browser storage.
export function createApiSession(baseURL = '/api') {
  const transport = axios.create({ baseURL, withCredentials: true, timeout: 20000 });
  const api = axios.create({ baseURL, withCredentials: true, timeout: 20000 });
  let token = null;
  let refreshPromise = null;
  let generation = 0;
  const listeners = new Set();
  const notify = (value) => listeners.forEach((listener) => listener(value));
  function clear() { generation += 1; token = null; notify(null); }
  function accept(data) { token = data.accessToken; notify(data.user); return data; }
  async function refresh() {
    if (!refreshPromise) {
      const started = generation;
      refreshPromise = transport.post('/auth/refresh').then(({ data }) => {
        if (started !== generation) throw new axios.CanceledError('Session changed');
        return accept(data);
      }).catch((error) => {
        if (started === generation) clear();
        throw error;
      }).finally(() => { refreshPromise = null; });
    }
    return refreshPromise;
  }
  api.interceptors.request.use((config) => {
    if (config.authRequired && token) config.headers.Authorization = `Bearer ${token}`;
    config.sessionGeneration = generation;
    return config;
  });
  api.interceptors.response.use((response) => response, async (error) => {
    const config = error.config;
    if (error.response?.status !== 401 || !config?.authRequired || config.retried || config.signal?.aborted) throw error;
    if (config.sessionGeneration !== generation) throw error;
    config.retried = true;
    const sentToken = config.headers.Authorization;
    // A late 401 for the old JWT can reuse a refresh already completed by a sibling request.
    if (!token || sentToken === `Bearer ${token}`) await refresh();
    if (config.signal?.aborted) throw new axios.CanceledError();
    return api(config);
  });
  return { api, transport, refresh, clear,
    subscribe(listener) { listeners.add(listener); return () => listeners.delete(listener); },
    async login(values) {
      generation += 1;
      if (refreshPromise) await refreshPromise.catch(() => {});
      const { data } = await transport.post('/auth/login', values);
      return accept(data);
    },
    async register(values) { return (await transport.post('/auth/register', values)).data; },
    async me(signal) { return (await api.get('/auth/me', { authRequired: true, signal })).data; },
    async logout() {
      clear();
      if (refreshPromise) await refreshPromise.catch(() => {});
      await transport.post('/auth/logout');
    },
  };
}

export const authSession = createApiSession(import.meta.env.VITE_API_BASE_URL || '/api');
export const api = authSession.api;
