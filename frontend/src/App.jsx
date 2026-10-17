import { Route, Routes } from 'react-router-dom';
import AppLayout from './layouts/AppLayout';
import AuthPage from './features/auth/AuthPage';
import ProtectedRoute from './features/auth/ProtectedRoute';
import Dashboard from './features/dashboard/Dashboard';
import Repositories from './pages/Repositories';
import CreateRepository from './features/repositories/CreateRepository';
import RepositoryOverview from './features/repositories/RepositoryOverview';
import CodePage from './features/repositories/CodePage';
import { Forbidden, NotFound, Placeholder } from './pages/Placeholder';
export default function App() { return <Routes><Route path="/login" element={<AuthPage key="login"/>}/><Route path="/register" element={<AuthPage key="register" registration/>}/><Route element={<ProtectedRoute/>}><Route element={<AppLayout/>}><Route index element={<Dashboard/>}/><Route path="repositories" element={<Repositories/>}/><Route path="repositories/new" element={<CreateRepository/>}/><Route path="repositories/:repositoryId" element={<RepositoryOverview/>}/><Route path="repositories/:repositoryId/settings" element={<RepositoryOverview settings/>}/><Route path="repositories/:repositoryId/code" element={<CodePage/>}/><Route path="explore" element={<Repositories explore/>}/>{[['issues','Issues','Keep the details moving forward.'],['pull-requests','Pull requests','Bring a second perspective to your code.'],['projects','Projects','Make room for the plan.'],['discussions','Discussions','Good ideas start with a conversation.'],['notifications','Notifications','Stay connected to the work that matters.'],['settings','Settings','Make your workspace feel like yours.']].map(([path,title,description]) => <Route key={path} path={path} element={<Placeholder title={title} description={description}/>}/>)}<Route path="forbidden" element={<Forbidden/>}/></Route></Route><Route path="*" element={<NotFound/>}/></Routes>; }
