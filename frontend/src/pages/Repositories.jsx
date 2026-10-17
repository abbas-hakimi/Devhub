import { PageHeader } from '../components/UI';
import RepositoryBrowser from '../features/repositories/RepositoryBrowser';
export default function Repositories({explore=false}) { return <><PageHeader eyebrow="YOUR CODE, ALL TOGETHER" title={explore?'Explore':'Repositories'}>{explore?'Discover public repositories.':'Owned repositories and accepted collaborations.'}</PageHeader><RepositoryBrowser explore={explore}/></>; }
