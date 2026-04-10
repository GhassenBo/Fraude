import React, { useState, useEffect } from 'react';
import { api, refreshMe } from '../services/auth';
import './DashboardPage.css';

export default function DashboardPage({ user, onUpgrade, onAnalyze }) {
  const [history, setHistory] = useState([]);
  const [loading, setLoading] = useState(true);
  const [currentUser, setCurrentUser] = useState(user);

  useEffect(() => {
    Promise.all([
      api.get('/api/history').then(r => setHistory(r.data)).catch(() => {}),
      refreshMe().then(u => setCurrentUser(u)).catch(() => {})
    ]).finally(() => setLoading(false));
  }, []);

  const handleManageBilling = async () => {
    try {
      const res = await api.post('/api/stripe/portal');
      window.location.href = res.data.url;
    } catch (e) {
      alert('Erreur : ' + (e.response?.data?.error || e.message));
    }
  };

  const freeLimit = 10;
  const usedPct = Math.min(100, (currentUser.documentsUsed / freeLimit) * 100);

  return (
    <div className="dashboard-page">
      <div className="dash-header">
        <h1>Dashboard</h1>
        <p>Bienvenue, <strong>{currentUser.email}</strong></p>
      </div>

      {/* Stats */}
      <div className="stats-grid">
        <div className="stat-card">
          <div className="stat-icon">📊</div>
          <div className="stat-value">{currentUser.documentsUsed}</div>
          <div className="stat-label">Documents analysés</div>
        </div>
        <div className="stat-card">
          <div className="stat-icon">{currentUser.isPro ? '♾️' : '🎁'}</div>
          <div className="stat-value">{currentUser.isPro ? '∞' : currentUser.remainingDocuments}</div>
          <div className="stat-label">Analyses restantes</div>
        </div>
        <div className={`stat-card plan-card ${currentUser.isPro ? 'pro' : ''}`}>
          <div className="stat-icon">{currentUser.isPro ? '⭐' : '🆓'}</div>
          <div className="stat-value">{currentUser.isPro ? 'PRO' : 'FREE'}</div>
          <div className="stat-label">Plan actuel</div>
        </div>
      </div>

      {/* Quota bar (free users) */}
      {!currentUser.isPro && (
        <div className="quota-card">
          <div className="quota-header">
            <span>Utilisation du plan gratuit</span>
            <span className="quota-count">{currentUser.documentsUsed} / {freeLimit}</span>
          </div>
          <div className="quota-bar">
            <div className="quota-fill" style={{
              width: `${usedPct}%`,
              background: usedPct >= 80 ? 'var(--red)' : usedPct >= 50 ? 'var(--orange)' : 'var(--accent)'
            }}></div>
          </div>
          {currentUser.remainingDocuments <= 3 && (
            <div className="quota-warning">
              ⚠ Plus que {currentUser.remainingDocuments} analyse{currentUser.remainingDocuments !== 1 ? 's' : ''} gratuite{currentUser.remainingDocuments !== 1 ? 's' : ''}
            </div>
          )}
          <button className="upgrade-btn" onClick={onUpgrade}>
            Passer au plan Pro — 49€/mois →
          </button>
        </div>
      )}

      {/* Pro — billing management */}
      {currentUser.isPro && (
        <div className="pro-card">
          <div className="pro-badge">⭐ Plan Pro actif</div>
          <p>Vous bénéficiez d'analyses illimitées.</p>
          <button className="manage-btn" onClick={handleManageBilling}>
            Gérer mon abonnement
          </button>
        </div>
      )}

      {/* Quick actions */}
      <div className="quick-actions">
        <button className="action-btn primary" onClick={onAnalyze}>
          + Nouvelle analyse
        </button>
        {!currentUser.isPro && (
          <button className="action-btn secondary" onClick={onUpgrade}>
            Passer Pro
          </button>
        )}
      </div>

      {/* History */}
      <div className="history-section">
        <h2>Historique des analyses</h2>
        {loading ? (
          <div className="loading">Chargement...</div>
        ) : history.length === 0 ? (
          <div className="empty-history">
            <span>📭</span>
            <p>Aucune analyse pour le moment</p>
            <button className="action-btn primary" onClick={onAnalyze}>Lancer ma première analyse</button>
          </div>
        ) : (
          <div className="history-list">
            {history.map(item => (
              <div key={item.id} className={`history-item verdict-${item.verdict?.toLowerCase()}`}>
                <div className="hist-file">📄 {item.filename}</div>
                <div className="hist-meta">
                  <span className={`hist-verdict ${item.color}`}>{item.verdict}</span>
                  <span className="hist-score">Score : {item.score}/100</span>
                  <span className="hist-date">{new Date(item.createdAt).toLocaleDateString('fr-FR')}</span>
                </div>
              </div>
            ))}
          </div>
        )}
      </div>
    </div>
  );
}
