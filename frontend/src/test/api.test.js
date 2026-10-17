import { describe, expect, it, vi } from 'vitest';
import { createApiSession } from '../services/api';
function response(config, data, status = 200) { return { config, data, status, statusText: '', headers: {} }; }
function unauthorized(config) { return Promise.reject({ config, response: { status: 401, data: { error: 'Unauthorized' } } }); }
describe('authentication transport', () => {
  it('uses the identifier contract and stores JWT only in memory', async () => {
    const session = createApiSession();
    session.transport.defaults.adapter = async (config) => {
      expect(JSON.parse(config.data)).toEqual({ identifier: 'postmanA', password: 'test-password' });
      return response(config, { accessToken: 'jwt', user: { username: 'postmanA' } });
    };
    await session.login({ identifier: 'postmanA', password: 'test-password' });
    session.api.defaults.adapter = async (config) => { expect(config.headers.Authorization).toBe('Bearer jwt'); return response(config, {}); };
    await session.me();
    expect(localStorage.getItem('accessToken')).toBeNull();
    expect(sessionStorage.getItem('accessToken')).toBeNull();
  });
  it('deduplicates concurrent refresh calls and emits the restored user', async () => {
    const session = createApiSession();
    let release;
    const listener = vi.fn(); session.subscribe(listener);
    session.transport.defaults.adapter = vi.fn((config) => new Promise((resolve) => { release = () => resolve(response(config, { accessToken: 'new', user: { username: 'A' } })); }));
    const first = session.refresh(); const second = session.refresh();
    await Promise.resolve(); release(); await Promise.all([first, second]);
    expect(session.transport.defaults.adapter).toHaveBeenCalledTimes(1);
    expect(listener).toHaveBeenCalledWith({ username: 'A' });
  });
  it('retries protected 401 once after refresh, without refreshing public errors or 403', async () => {
    const session = createApiSession();
    session.transport.defaults.adapter = vi.fn(async (config) => response(config, { accessToken: 'new', user: {} }));
    session.api.defaults.adapter = vi.fn(async (config) => config.retried ? response(config, { username: 'A' }) : unauthorized(config));
    expect(await session.me()).toEqual({ username: 'A' });
    expect(session.transport.defaults.adapter).toHaveBeenCalledTimes(1);
    await expect(session.api.get('/public')).rejects.toBeDefined();
    session.api.defaults.adapter = async (config) => Promise.reject({ config, response: { status: 403 } });
    await expect(session.me()).rejects.toBeDefined();
    expect(session.transport.defaults.adapter).toHaveBeenCalledTimes(1);
  });
  it('expires a session on failed refresh without a retry loop', async () => {
    const session = createApiSession(); const listener = vi.fn(); session.subscribe(listener);
    session.transport.defaults.adapter = vi.fn(unauthorized);
    session.api.defaults.adapter = unauthorized;
    await expect(session.me()).rejects.toBeDefined();
    expect(session.transport.defaults.adapter).toHaveBeenCalledTimes(1);
    expect(listener).toHaveBeenCalledWith(null);
  });
  it('clears local authentication and sends credentialed logout', async () => {
    const session = createApiSession(); const listener = vi.fn(); session.subscribe(listener);
    session.transport.defaults.adapter = async (config) => { expect(config.url).toBe('/auth/logout'); expect(config.withCredentials).toBe(true); return response(config, null, 204); };
    await session.logout(); expect(listener).toHaveBeenCalledWith(null);
  });
});
