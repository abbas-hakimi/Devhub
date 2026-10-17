import { useQuery } from '@tanstack/react-query';
import { BookOpen, LockKeyhole, Globe2 } from 'lucide-react';
import { listMyRepositories } from '../services/repositories';
import { normalizeError } from '../services/api';
import { Alert, PageHeader } from '../components/UI';
export default function Repositories() {
  const { data, error, isPending, refetch } = useQuery({ queryKey: ['repositories', 'mine'], queryFn: ({ signal }) => listMyRepositories(signal), retry: false });
  return <><PageHeader eyebrow="YOUR CODE, ALL TOGETHER" title="Repositories">The repositories you own. Repository workspaces arrive in a later phase.</PageHeader>{isPending ? <p role="status"><span className="spinner-border spinner-border-sm me-2"/>Loading repositories…</p> : error ? <><Alert>{normalizeError(error).message}</Alert><button className="btn btn-outline-secondary" onClick={() => refetch()}>Try again</button></> : data?.length ? <div className="repository-list">{data.map((repository) => <article className="repository-card" key={repository.id}><BookOpen size={22}/><div><h2>{repository.owner.username}<span> / </span>{repository.name}</h2><p>{repository.description || 'No description provided.'}</p><small>{repository.visibility === 'PRIVATE' ? <LockKeyhole size={13}/> : <Globe2 size={13}/>} {repository.visibility.toLowerCase()}<span>Repository workspace coming soon</span></small></div></article>)}</div> : <div className="empty-panel"><BookOpen size={34}/><h2>Your next project has a home here.</h2><p>You don’t own any repositories yet. Repository creation and workspace tools are coming in a later phase.</p></div>}</>;
}
