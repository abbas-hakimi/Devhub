import { useState } from 'react';
import { Link, Navigate, useLocation, useNavigate } from 'react-router-dom';
import { useForm } from 'react-hook-form';
import { zodResolver } from '@hookform/resolvers/zod';
import { z } from 'zod';
import { ArrowRight, Eye, EyeOff, GitBranch, ShieldCheck, Layers3 } from 'lucide-react';
import { useAuth } from '../../context/AuthContext';
import { authSession, normalizeError } from '../../services/api';
import { Alert, Brand, LoadingScreen, ThemeToggle } from '../../components/UI';

const loginSchema = z.object({ identifier: z.string().trim().min(1, 'Enter your username or email.'), password: z.string().min(1, 'Enter your password.') });
const registerSchema = z.object({ username: z.string().trim().min(3, 'Use at least 3 characters.').max(50), email: z.email('Enter a valid email.').max(254), displayName: z.string().max(100), password: z.string().min(8, 'Use at least 8 characters.').max(72, 'Use no more than 72 characters.') });

export default function AuthPage({ registration = false }) {
  const { login, status, notice } = useAuth();
  const [showPassword, setShowPassword] = useState(false);
  const [error, setError] = useState('');
  const location = useLocation();
  const navigate = useNavigate();
  const { register, handleSubmit, setError: setFieldError, formState: { errors, isSubmitting } } = useForm({ resolver: zodResolver(registration ? registerSchema : loginSchema) });
  if (status === 'restoring') return <LoadingScreen/>;
  if (status === 'authenticated') return <Navigate to="/" replace/>;
  async function submit(values) {
    setError('');
    try {
      if (registration) {
        await authSession.register(values);
        navigate('/login', { replace: true, state: { registered: true } });
      } else {
        await login(values);
        const from = location.state?.from;
        navigate(typeof from === 'string' && from.startsWith('/') && !from.startsWith('//') ? from : '/', { replace: true });
      }
    } catch (failure) {
      const normalized = normalizeError(failure);
      Object.entries(normalized.fields).forEach(([field, message]) => setFieldError(field, { message }));
      setError(normalized.status === 401 ? 'That username, email, or password is incorrect.' : normalized.message);
    }
  }
  function field(name, label, props = {}) {
    return <div className="mb-3"><label htmlFor={name} className="form-label">{label}</label><input id={name} className={`form-control ${errors[name] ? 'is-invalid' : ''}`} aria-invalid={!!errors[name]} aria-describedby={errors[name] ? `${name}-error` : undefined} {...register(name)} {...props}/>{errors[name] && <div id={`${name}-error`} className="invalid-feedback">{errors[name].message}</div>}</div>;
  }
  return <div className="auth-page"><header className="auth-top"><Brand/><ThemeToggle/></header><main className="auth-grid"><section className="auth-story"><div className="eyebrow">A LITTLE MORE FOCUS. A LOT MORE POSSIBILITY.</div><h1>Your code.<br/>Your people.<br/><span>Your next chapter.</span></h1><p>A thoughtful space to build, review, and move ideas forward. Welcome to your development workspace.</p><div className="story-lines"><span><GitBranch size={18}/> A home for every repository</span><span><Layers3 size={18}/> Collaboration with context</span><span><ShieldCheck size={18}/> Your workspace, securely connected</span></div><div className="code-mark" aria-hidden="true"><span>workspace</span><b>build something that matters_</b><i>ready when you are.</i></div></section><section className="auth-form-panel"><div className="eyebrow">{registration ? 'MAKE ROOM FOR YOUR NEXT IDEA' : 'PICK UP WHERE YOU LEFT OFF'}</div><h2>{registration ? 'Create your account' : 'Welcome back'}</h2><p className="text-secondary mb-4">{registration ? 'Start building your space on DevHub.' : 'Sign in to your DevHub workspace.'}</p><Alert>{error}</Alert><Alert variant="success">{!registration && location.state?.registered ? 'Account created. Sign in to start a secure session.' : ''}</Alert><Alert variant="info">{notice || (!registration && location.state?.expired ? 'Please sign in to continue.' : '')}</Alert><form onSubmit={handleSubmit(submit)} noValidate>{registration ? <>{field('displayName', 'Display name (optional)', { autoComplete: 'name' })}{field('username', 'Username', { autoComplete: 'username' })}{field('email', 'Email address', { type: 'email', autoComplete: 'email' })}</> : field('identifier', 'Username or email', { autoComplete: 'username', autoFocus: true })}<div className="mb-3"><label className="form-label" htmlFor="password">Password</label><div className="password-field"><input id="password" type={showPassword ? 'text' : 'password'} autoComplete={registration ? 'new-password' : 'current-password'} className={`form-control ${errors.password ? 'is-invalid' : ''}`} {...register('password')} aria-invalid={!!errors.password} aria-describedby={errors.password ? 'password-error' : undefined}/><button type="button" className="password-toggle" aria-label={showPassword ? 'Hide password' : 'Show password'} onClick={() => setShowPassword(!showPassword)}>{showPassword ? <EyeOff size={18}/> : <Eye size={18}/>}</button></div>{errors.password && <div className="field-error" id="password-error">{errors.password.message}</div>}{registration && <small className="text-secondary">Use 8–72 characters.</small>}</div><button className="btn btn-primary w-100 mt-2 submit-button" disabled={isSubmitting}>{isSubmitting ? <><span className="spinner-border spinner-border-sm" aria-hidden="true"/> Please wait…</> : <>{registration ? 'Create account' : 'Sign in'}<ArrowRight size={17}/></>}</button></form><p className="auth-alternate">{registration ? 'Already have a workspace?' : 'New to DevHub?'} <Link to={registration ? '/login' : '/register'}>{registration ? 'Sign in' : 'Create an account'}</Link></p><div className="auth-footnote"><ShieldCheck size={15}/> Secure sessions. More time for your work.</div></section></main><footer className="auth-footer">DEVHUB <span>Built for the work ahead.</span></footer></div>;
}
