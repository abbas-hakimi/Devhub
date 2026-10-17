import { api } from './api';
export async function listMyRepositories(signal) {
  return (await api.get('/repositories/mine', { authRequired: true, signal })).data;
}
