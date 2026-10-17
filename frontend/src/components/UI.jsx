import { Component } from 'react';
import { Link } from 'react-router-dom';
import { Box, Moon, Sun, ArrowUpRight } from 'lucide-react';
import { useTheme } from '../context/ThemeContext';
export function Brand() { return <Link className="brand" to="/"><span className="brand-icon"><Box size={21}/></span>devhub<span className="brand-dot">.</span></Link>; }
export function ThemeToggle() {
  const { theme, toggleTheme } = useTheme();
  return <button className="icon-button" onClick={toggleTheme} aria-label={`Switch to ${theme === 'dark' ? 'light' : 'dark'} theme`}>{theme === 'dark' ? <Sun size={19}/> : <Moon size={19}/>}</button>;
}
export function LoadingScreen({ label = 'Opening your workspace…' }) { return <main className="loading-screen" role="status"><Brand/><span className="spinner-border spinner-border-sm" aria-hidden="true"/>{label}</main>; }
export function PageHeader({ eyebrow, title, children }) { return <header className="page-heading"><div className="eyebrow">{eyebrow}</div><h1>{title}</h1>{children && <p>{children}</p>}</header>; }
export function FeatureCard({ to, icon: Icon, title, children }) { return <Link className="feature-card" to={to}><span className="feature-icon"><Icon size={22}/></span><ArrowUpRight size={17} className="card-arrow"/><h3>{title}</h3><p>{children}</p></Link>; }
export function Alert({ children, variant = 'danger' }) { return children ? <div className={`alert alert-${variant}`} role={variant === 'danger' ? 'alert' : 'status'}>{children}</div> : null; }
export class ErrorBoundary extends Component {
  state = { failed: false };
  static getDerivedStateFromError() { return { failed: true }; }
  render() { return this.state.failed ? <main className="error-page"><h1>Something interrupted this page.</h1><p>Reload the workspace to try again.</p><button className="btn btn-primary" onClick={() => window.location.reload()}>Reload DevHub</button></main> : this.props.children; }
}
