import { useEffect, useRef, useState } from 'react';
import { Link, NavLink, Outlet, useLocation } from 'react-router-dom';
import { Bell, BookOpen, ChevronDown, ChevronRight, Compass, FolderKanban, GitPullRequest, LayoutDashboard, LogOut, Menu, MessageSquare, Search, Settings, CircleDot, X } from 'lucide-react';
import { Brand, ThemeToggle } from '../components/UI';
import { useAuth } from '../context/AuthContext';

export const navigation = [
  ['/', 'Dashboard', LayoutDashboard], ['/explore', 'Explore', Compass], ['/repositories', 'Repositories', BookOpen],
  ['/issues', 'Issues', CircleDot], ['/pull-requests', 'Pull requests', GitPullRequest], ['/projects', 'Projects', FolderKanban],
  ['/discussions', 'Discussions', MessageSquare], ['/notifications', 'Notifications', Bell], ['/settings', 'Settings', Settings],
];
export default function AppLayout() {
  const [sidebarOpen, setSidebarOpen] = useState(false);
  const [collapsed, setCollapsed] = useState(false);
  const [profileOpen, setProfileOpen] = useState(false);
  const profile = useRef(null);
  const { user, logout } = useAuth();
  const location = useLocation();
  const title = navigation.find(([path]) => path === location.pathname)?.[1] || 'Workspace';
  useEffect(() => {
    function close(event) { if (!profile.current?.contains(event.target)) setProfileOpen(false); }
    function escape(event) { if (event.key === 'Escape') { setProfileOpen(false); setSidebarOpen(false); } }
    document.addEventListener('pointerdown', close); document.addEventListener('keydown', escape);
    return () => { document.removeEventListener('pointerdown', close); document.removeEventListener('keydown', escape); };
  }, []);
  return <div className={`workspace ${collapsed ? 'sidebar-collapsed' : ''}`}><a href="#main" className="skip-link">Skip to content</a>{sidebarOpen && <button className="sidebar-scrim" onClick={() => setSidebarOpen(false)} aria-label="Close navigation"/>}<aside id="workspace-navigation" className={`sidebar ${sidebarOpen ? 'mobile-open' : ''}`}><div className="sidebar-brand"><Brand/><button className="icon-button mobile-only" onClick={() => setSidebarOpen(false)} aria-label="Close navigation"><X size={19}/></button></div><div className="workspace-label"><span className="workspace-badge">D</span><span className="nav-copy">Personal workspace<small>Let's build something.</small></span></div><nav aria-label="Main navigation">{navigation.map(([path, label, Icon], index) => <NavLink key={path} to={path} end={path === '/'} title={collapsed ? label : undefined} className={({ isActive }) => `nav-item ${isActive ? 'active' : ''} ${index === 7 ? 'nav-divider' : ''}`} onClick={() => setSidebarOpen(false)}><Icon size={19}/><span className="nav-copy">{label}</span></NavLink>)}</nav><div className="sidebar-bottom"><div className="workspace-note"><span className="status-dot"/><span className="nav-copy">A space for your next idea</span></div><button className="collapse-button" onClick={() => setCollapsed(!collapsed)} aria-label={collapsed ? 'Expand sidebar' : 'Collapse sidebar'}><Menu size={17}/><span className="nav-copy">Collapse navigation</span></button></div></aside><div className="workspace-body"><header className="topbar"><div className="topbar-heading"><button className="icon-button mobile-only" aria-label="Open navigation" aria-expanded={sidebarOpen} aria-controls="workspace-navigation" onClick={() => setSidebarOpen(true)}><Menu size={21}/></button><nav aria-label="Breadcrumb"><span>Workspace</span><ChevronRight size={13}/><strong>{title}</strong></nav></div><div className="topbar-actions"><div className="search-shell" title="Workspace search is coming in a future phase"><Search size={16}/><input aria-label="Workspace search (coming soon)" placeholder="Search workspace · coming soon" disabled/></div><ThemeToggle/><Link className="icon-button notification-link" to="/notifications" aria-label="Notifications"><Bell size={19}/></Link><div className="profile" ref={profile}><button className="profile-trigger" aria-label="Account menu" aria-expanded={profileOpen} onClick={() => setProfileOpen(!profileOpen)}><span className="avatar">{(user?.displayName || user?.username || 'D').slice(0, 2).toUpperCase()}</span><ChevronDown size={13}/></button>{profileOpen && <div className="profile-menu"><strong>{user?.displayName || user?.username}</strong><small>@{user?.username}</small><Link to="/settings" onClick={() => setProfileOpen(false)}><Settings size={16}/> Settings</Link><button onClick={logout}><LogOut size={16}/> Sign out</button></div>}</div></div></header><main id="main" className="content" tabIndex={-1}><Outlet/></main><footer className="workspace-footer"><span>devhub.</span><span>Your ideas, in good company.</span></footer></div></div>;
}
