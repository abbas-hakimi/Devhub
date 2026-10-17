import { Navigate, Outlet, useLocation } from 'react-router-dom';
import { useAuth } from '../../context/AuthContext';
import { LoadingScreen } from '../../components/UI';
export default function ProtectedRoute() {
  const { status } = useAuth();
  const location = useLocation();
  if (status === 'restoring') return <LoadingScreen/>;
  return status === 'authenticated' ? <Outlet/> : <Navigate to="/login" replace state={{ from: location.pathname, expired: true }}/>;
}
