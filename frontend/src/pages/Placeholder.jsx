import { Link } from 'react-router-dom';
import { ArrowLeft, Blocks, ShieldAlert } from 'lucide-react';
import { PageHeader } from '../components/UI';
export function Placeholder({ title, description }) { return <><PageHeader eyebrow="THE WORKSPACE IS TAKING SHAPE" title={title}>{description}</PageHeader><div className="empty-panel"><Blocks size={36}/><span className="pill">COMING IN A LATER PHASE</span><h2>A little more space to build.</h2><p>This area is prepared for the next frontend phases. Its tools aren’t available yet.</p><Link className="btn btn-outline-secondary" to="/"><ArrowLeft size={16}/> Back to dashboard</Link></div></>; }
export function Forbidden() { return <div className="error-page"><ShieldAlert size={38}/><h1>This space needs permission.</h1><p>Your account doesn’t have access to this action.</p><Link className="btn btn-primary" to="/">Back to dashboard</Link></div>; }
export function NotFound() { return <div className="error-page"><div className="error-number">404</div><h1>Looks like a loose end.</h1><p>We couldn’t find that page. Your workspace is still right here.</p><Link className="btn btn-primary" to="/">Back to workspace</Link></div>; }
