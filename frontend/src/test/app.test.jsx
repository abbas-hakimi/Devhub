import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, it, vi, beforeEach } from 'vitest';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { AuthProvider } from '../context/AuthContext';
import { ThemeProvider } from '../context/ThemeContext';
import { ThemeToggle } from '../components/UI';
import AuthPage from '../features/auth/AuthPage';
import ProtectedRoute from '../features/auth/ProtectedRoute';
import AppLayout from '../layouts/AppLayout';
import { authSession } from '../services/api';

vi.mock('../services/api', () => ({ authSession: { refresh: vi.fn(), me: vi.fn(), subscribe: vi.fn(() => () => {}), login: vi.fn(), logout: vi.fn() }, normalizeError: (e) => ({ status: e.response?.status || 0, message: 'Connection failed', fields: {} }) }));
function mount(initial = '/login') {
  return render(<QueryClientProvider client={new QueryClient()}><ThemeProvider><MemoryRouter initialEntries={[initial]}><AuthProvider><Routes><Route path="/login" element={<AuthPage/>}/><Route element={<ProtectedRoute/>}><Route element={<AppLayout/>}><Route index element={<h1>Private dashboard</h1>}/><Route path="projects" element={<h1>Projects placeholder</h1>}/></Route></Route></Routes></AuthProvider></MemoryRouter></ThemeProvider></QueryClientProvider>);
}
beforeEach(() => { vi.clearAllMocks(); authSession.refresh.mockRejectedValue({ response: { status: 401 } }); authSession.me.mockResolvedValue({ username: 'postmanA', displayName: 'Ada' }); });
describe('authentication UI', () => {
  it('validates empty login fields before calling the backend', async () => {
    mount(); await screen.findByRole('heading', { name: 'Welcome back' });
    await userEvent.click(screen.getByRole('button', { name: 'Sign in' }));
    expect(await screen.findByText('Enter your username or email.')).toBeInTheDocument();
    expect(authSession.login).not.toHaveBeenCalled();
  });
  it('logs in using identifier and opens protected dashboard', async () => {
    authSession.login.mockResolvedValue({ user: { username: 'postmanA' } }); mount();
    await screen.findByRole('heading', { name: 'Welcome back' });
    await userEvent.type(screen.getByLabelText('Username or email'), 'postmanA');
    await userEvent.type(screen.getByLabelText('Password'), 'test-password');
    await userEvent.click(screen.getByRole('button', { name: 'Sign in' }));
    expect(await screen.findByRole('heading', { name: 'Private dashboard' })).toBeInTheDocument();
    expect(authSession.login).toHaveBeenCalledWith({ identifier: 'postmanA', password: 'test-password' });
  });
  it('shows invalid credentials inline', async () => {
    authSession.login.mockRejectedValue({ response: { status: 401 } }); mount();
    await screen.findByRole('heading', { name: 'Welcome back' });
    fireEvent.change(screen.getByLabelText('Username or email'), { target: { value: 'A' } });
    fireEvent.change(screen.getByLabelText('Password'), { target: { value: 'wrong' } });
    await userEvent.click(screen.getByRole('button', { name: 'Sign in' }));
    expect(await screen.findByRole('alert')).toHaveTextContent('incorrect');
  });
  it('redirects an anonymous protected route to login', async () => { mount('/projects'); expect(await screen.findByRole('heading', { name: 'Welcome back' })).toBeInTheDocument(); });
  it('restores session and provides functional main navigation', async () => {
    authSession.refresh.mockResolvedValue({ user: { username: 'postmanA' } }); mount('/');
    expect(await screen.findByRole('heading', { name: 'Private dashboard' })).toBeInTheDocument();
    expect(authSession.me).toHaveBeenCalled();
    await userEvent.click(screen.getByRole('link', { name: 'Projects' }));
    expect(await screen.findByRole('heading', { name: 'Projects placeholder' })).toBeInTheDocument();
  });
  it('shows loading screen until restoration finishes', async () => {
    let finish; authSession.refresh.mockImplementation(() => new Promise((resolve) => { finish = resolve; })); mount('/');
    expect(screen.getByRole('status')).toHaveTextContent('Opening your workspace');
    finish({}); await waitFor(() => expect(screen.getByRole('heading', { name: 'Private dashboard' })).toBeInTheDocument());
  });
});
it('switches theme and persists only the theme preference', async () => {
  localStorage.clear(); render(<ThemeProvider><ThemeToggle/></ThemeProvider>);
  await userEvent.click(screen.getByRole('button', { name: 'Switch to light theme' }));
  expect(document.documentElement.dataset.bsTheme).toBe('light');
  expect(localStorage.getItem('devhub-theme')).toBe('light');
});
