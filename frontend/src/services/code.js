import { api } from './api';
const auth = { authRequired: true };
export function safePath(path) {
  if (!path || path.length>4096 || path.startsWith('/') || path.includes('\\') || /[\u0000-\u001f\u007f]/.test(path) || path.split('/').some(p=>!p || p==='.' || p==='..')) throw new Error('Invalid repository path.');
  return path.split('/').map(encodeURIComponent).join('/');
}
export function validBranch(name) { return !!name && new TextEncoder().encode(name).length<=255 && name!=='HEAD' && !/[\s\u0000-\u001f\u007f~^:?*\[\\]/u.test(name) && !name.startsWith('/') && !name.endsWith('/') && !name.endsWith('.') && !name.endsWith('.lock') && !name.includes('//') && !name.includes('@{') && !name.split('/').some(p=>p==='.' || p==='..'); }
export const initialize = async id => (await api.post(`/repositories/${id}/git/init`,null,auth)).data;
export const createBranch = async (id,values) => (await api.post(`/repositories/${id}/branches`,values,auth)).data;
export const commitFiles = async (id,values) => (await api.post(`/repositories/${id}/commits`,values,auth)).data;
export const history = async (id,branch,page,signal) => (await api.get(`/repositories/${id}/commits`,{...auth,params:{branch,page,size:20},signal})).data;
export const commitDetails = async (hash,signal) => (await api.get(`/commits/${hash}`,{...auth,signal})).data;
export const tree = async (hash,path,signal) => (await api.get(`/commits/${hash}/tree${path?'/'+safePath(path):''}`,{...auth,signal})).data;
export const blob = async (hash,path,signal) => (await api.get(`/commits/${hash}/blob/${safePath(path)}`,{...auth,signal,responseType:'arraybuffer'})).data;
export const compare = async (id,base,head,page,signal) => (await api.get(`/repositories/${id}/compare`,{...auth,params:{base,head,page,size:10},signal})).data;
