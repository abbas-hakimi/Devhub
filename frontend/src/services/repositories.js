import { api } from './api';
export async function listPublicRepositories(signal) { return (await api.get('/repositories', { signal })).data; }
export async function getRepository(id, signal) { return (await api.get(`/repositories/${id}`, { authRequired: true, signal })).data; }
export async function createRepository(values) { return (await api.post('/repositories', values, { authRequired: true })).data; }
export async function updateRepository(id, values) { return (await api.put(`/repositories/${id}`, values, { authRequired: true })).data; }
export async function deleteRepository(id) { await api.delete(`/repositories/${id}`, { authRequired: true }); }
export async function listBranches(id, signal) { return (await api.get(`/repositories/${id}/branches`, { authRequired: true, signal })).data; }
export async function listCollaborators(id, signal) { return (await api.get(`/repositories/${id}/collaborators`, { authRequired: true, signal })).data; }
export async function listMyRepositories(signal) {
  return (await api.get('/repositories/mine', { authRequired: true, signal })).data;
}
