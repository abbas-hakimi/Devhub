import { createContext, useContext, useState } from 'react';
const ThemeContext = createContext(null);
function initialTheme() {
  try { return localStorage.getItem('devhub-theme') === 'light' ? 'light' : 'dark'; }
  catch { return 'dark'; }
}
export function ThemeProvider({ children }) {
  const [theme, setTheme] = useState(initialTheme);
  document.documentElement.dataset.bsTheme = theme;
  function toggleTheme() {
    const next = theme === 'dark' ? 'light' : 'dark';
    setTheme(next);
    try { localStorage.setItem('devhub-theme', next); } catch { /* Storage may be disabled. */ }
  }
  return <ThemeContext.Provider value={{ theme, toggleTheme }}>{children}</ThemeContext.Provider>;
}
export function useTheme() { return useContext(ThemeContext); }
