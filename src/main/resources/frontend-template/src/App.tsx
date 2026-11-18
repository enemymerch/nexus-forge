import { NavLink, Navigate, Route, Routes } from 'react-router-dom';
import { CategoryPage } from './features/categories/CategoryPage';
const services = ['Catalog', 'Attributes', 'Content', 'Media', 'Workflow', 'Search'];
export function App() {
  return <div className="app-shell"><aside><div className="brand"><span className="brand-mark">N</span>nexus<span className="brand-label">PIM</span></div>
    <p className="nav-label">WORKSPACE</p><NavLink to="/categories">Categories <span>01</span></NavLink>
    <p className="nav-label">PLANNED CONNECTIONS</p>{services.map(name => <div className="nav-pending" key={name}>{name}<small>Not connected</small></div>)}
    <footer>LOCAL DEVELOPMENT<br/><small>Taxonomy · localhost:8083</small></footer></aside>
    <main><header><span>Workspace <b>/</b> Taxonomy</span><span className="environment">Development</span></header>
    <Routes><Route path="/categories" element={<CategoryPage/>}/><Route path="*" element={<Navigate to="/categories" replace/>}/></Routes>
    </main></div>;
}
