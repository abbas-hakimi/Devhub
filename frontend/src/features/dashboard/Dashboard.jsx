import { Link } from 'react-router-dom';
import { useAuth } from '../../context/AuthContext';
import { PageHeader } from '../../components/UI';
import RepositoryBrowser from '../repositories/RepositoryBrowser';
export default function Dashboard(){const {user}=useAuth();return <><PageHeader eyebrow="YOUR PERSONAL WORKSPACE" title={`Welcome back, ${user?.displayName || user?.username}.`}>Your ideas, your collaborators, your next chapter.</PageHeader><section className="dashboard-intro"><div><span className="pill">BUILD SOMETHING THAT MATTERS</span><h2>A clear view of the work ahead.</h2><p>Find your code and keep moving.</p></div><div className="dashboard-actions"><Link className="btn btn-outline-secondary" to="/explore">Explore repositories</Link><Link className="btn btn-outline-secondary" to="/repositories">Go to repositories</Link></div></section><RepositoryBrowser dashboard/></>;}
