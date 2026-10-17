import { useNavigate } from 'react-router-dom';
import { useQueryClient } from '@tanstack/react-query';
import { createRepository } from '../../services/repositories';
import { PageHeader } from '../../components/UI';
import RepositoryForm from './RepositoryForm';
export default function CreateRepository(){const navigate=useNavigate();const queries=useQueryClient();async function save(values){const result=await createRepository(values);await queries.invalidateQueries({queryKey:['repositories']});navigate(`/repositories/${result.id}`,{state:{created:true}});}return <><PageHeader eyebrow="START SOMETHING NEW" title="Create a repository">Your authenticated account becomes the owner.</PageHeader><RepositoryForm onSave={save}/></>;}
