import React, { useState, useEffect } from 'react';
import UploadPage from './pages/UploadPage';
import ResultPage from './pages/ResultPage';
import BatchResultPage from './pages/BatchResultPage';
import LoginPage from './pages/LoginPage';
import RegisterPage from './pages/RegisterPage';
import DashboardPage from './pages/DashboardPage';
import PricingPage from './pages/PricingPage';
import { getToken, getUser, logout } from './services/auth';
import './App.css';

export default function App() {
  const [user, setUser] = useState(getUser());
  const [page, setPage] = useState('upload');
  const [result, setResult] = useState(null);
  const [filename, setFilename] = useState('');
  const [batchResult, setBatchResult] = useState(null);

  useEffect(() => {
    const params = new URLSearchParams(window.location.search);
    if (params.get('upgrade') === 'success') {
      setPage('dashboard');
    }
  }, []);

  const handleLogin = (userData) => {
    setUser(userData);
    setPage('upload');
  };

  const handleLogout = () => {
    logout();
    setUser(null);
    setPage('upload');
    setResult(null);
    setBatchResult(null);
  };

  const handleResult = (data, name) => {
    setResult(data);
    setFilename(name);
    setPage('result');
    if (data.remainingDocuments === 0 && !data.isPro) {
      setTimeout(() => setPage('pricing'), 3000);
    }
  };

  const handleBatchResult = (data) => {
    setBatchResult(data);
    setPage('batch-result');
  };

  const handleReset = () => { setResult(null); setBatchResult(null); setPage('upload'); };

  if (!user && page === 'upload') {
    return <div className="app"><Header user={user} onLogout={handleLogout} onNav={setPage} /><LoginPage onLogin={handleLogin} onRegister={() => setPage('register')} /></div>;
  }
  if (page === 'register') {
    return <div className="app"><Header user={user} onLogout={handleLogout} onNav={setPage} /><RegisterPage onLogin={handleLogin} onBack={() => setPage('upload')} /></div>;
  }

  return (
    <div className="app">
      <Header user={user} onLogout={handleLogout} onNav={setPage} currentPage={page} />
      {page === 'upload' && <UploadPage onResult={handleResult} onBatchResult={handleBatchResult} user={user} onUpgrade={() => setPage('pricing')} />}
      {page === 'result' && <ResultPage result={result} filename={filename} onReset={handleReset} onUpgrade={() => setPage('pricing')} />}
      {page === 'batch-result' && <BatchResultPage batchResult={batchResult} onReset={handleReset} onUpgrade={() => setPage('pricing')} />}
      {page === 'dashboard' && <DashboardPage user={user} onUpgrade={() => setPage('pricing')} onAnalyze={() => setPage('upload')} />}
      {page === 'pricing' && <PricingPage user={user} onBack={() => setPage('upload')} />}
    </div>
  );
}

function Header({ user, onLogout, onNav, currentPage }) {
  return (
    <header className="header">
      <div className="header-inner">
        <div className="logo" onClick={() => onNav('upload')} style={{cursor:'pointer'}}>
          <span className="logo-icon">⬡</span>
          <span className="logo-text">FRAUD<span className="logo-accent">DETECT</span></span>
        </div>
        <nav className="header-nav">
          {user && <>
            <button className={`nav-link ${currentPage==='upload'?'active':''}`} onClick={() => onNav('upload')}>Analyser</button>
            <button className={`nav-link ${currentPage==='dashboard'?'active':''}`} onClick={() => onNav('dashboard')}>Dashboard</button>
            <button className={`nav-link ${currentPage==='pricing'?'active':''}`} onClick={() => onNav('pricing')}>Tarifs</button>
          </>}
        </nav>
        <div className="header-right">
          {user ? (
            <div className="header-user">
              <span className={`plan-badge ${user.isPro ? 'pro' : 'free'}`}>{user.isPro ? 'PRO' : 'FREE'}</span>
              {!user.isPro && <span className="docs-left">{user.remainingDocuments} doc{user.remainingDocuments !== 1 ? 's' : ''} restant{user.remainingDocuments !== 1 ? 's' : ''}</span>}
              <span className="user-email">{user.email}</span>
              <button className="logout-btn" onClick={onLogout}>Déconnexion</button>
            </div>
          ) : (
            <div className="badge-dot-wrap"><span className="badge-dot"></span><span style={{fontSize:12,color:'var(--text-muted)',fontFamily:'var(--font-mono)'}}>Bulletin de salaire · France</span></div>
          )}
        </div>
      </div>
    </header>
  );
}
