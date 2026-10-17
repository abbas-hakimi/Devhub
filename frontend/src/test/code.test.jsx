import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe,it,expect,vi } from 'vitest';
import { MemoryRouter } from 'react-router-dom';
import { safePath,validBranch } from '../services/code';
import { TreeView,TextView,HistoryView,DiffView,CodeBreadcrumbs } from '../features/repositories/CodeViews';
describe('code path and branch boundaries',()=>{
  it('encodes each path segment once including spaces Unicode and percent',()=>{expect(safePath('src/hello #世界%.txt')).toBe('src/hello%20%23%E4%B8%96%E7%95%8C%25.txt');});
  it.each(['../secret','a/../b','/root','a\\b','a//b','a/./b','a\0b'])('rejects invalid path %s',path=>expect(()=>safePath(path)).toThrow());
  it.each(['HEAD','../main','a b','/main','a.lock','a//b','a@{b'])('rejects invalid branch %s',name=>expect(validBranch(name)).toBe(false));
  it('allows hierarchical branch names',()=>expect(validBranch('feature/new-ui')).toBe(true));
});
describe('code views',()=>{
  it('renders directory/file names and navigates using their actual types',async()=>{const open=vi.fn();render(<TreeView data={{entries:[{name:'src',type:'DIRECTORY'},{name:'README.md',type:'FILE',size:8}]}} onOpen={open}/>);await userEvent.click(screen.getByRole('button',{name:'src'}));expect(open).toHaveBeenCalledWith({name:'src',type:'DIRECTORY'});expect(screen.getByText('8 bytes')).toBeInTheDocument();});
  it('renders empty directories',()=>{render(<TreeView data={{entries:[]}}/>);expect(screen.getByText('This directory is empty.')).toBeInTheDocument();});
  it('navigates breadcrumbs',async()=>{const navigate=vi.fn();render(<CodeBreadcrumbs path="src/utils" onPath={navigate}/>);await userEvent.click(screen.getByRole('button',{name:'src'}));expect(navigate).toHaveBeenCalledWith('src');});
  it('renders text as escaped code with line numbers',()=>{render(<TextView bytes={new TextEncoder().encode('<script>alert(1)</script>\nsecond')}/>);expect(screen.getByText('<script>alert(1)</script>')).toBeInTheDocument();expect(screen.getByText('2')).toBeInTheDocument();expect(document.querySelector('script')).toBeNull();});
  it('falls back for binary content',()=>{render(<TextView bytes={new Uint8Array([0,255])}/>);expect(screen.getByText(/Binary/)).toBeInTheDocument();});
  it('limits long files',()=>{render(<TextView bytes={new TextEncoder().encode('a'.repeat(200001))}/>);expect(screen.getByText(/exceeds/)).toBeInTheDocument();});
  it('renders history with author and opens details',async()=>{const open=vi.fn();render(<MemoryRouter><HistoryView data={{commits:[{commitHash:'a'.repeat(64),message:'Initial commit',author:{username:'ada'},createdAt:'2026-10-17'}]}} repositoryId="1" onCommit={open}/></MemoryRouter>);await userEvent.click(screen.getByRole('button',{name:'Initial commit'}));expect(open).toHaveBeenCalledWith('a'.repeat(64));expect(screen.getByText(/ada/)).toBeInTheDocument();});
  it('renders actual patch additions/deletions and binary fallback',()=>{render(<DiffView data={{filesChanged:2,additions:1,deletions:1,changes:[{path:'a.txt',status:'MODIFIED',patch:'-old\n+new'},{path:'a.bin',status:'ADDED',binary:true}]}}/>);expect(screen.getByText('+new')).toHaveClass('diff-line-add');expect(screen.getByText('-old')).toHaveClass('diff-line-remove');expect(screen.getByText('Binary file changed.')).toBeInTheDocument();});
  it('renders unchanged comparison',()=>{render(<DiffView data={{filesChanged:0,additions:0,deletions:0,changes:[]}}/>);expect(screen.getByText('No changes between these branches.')).toBeInTheDocument();});
});
