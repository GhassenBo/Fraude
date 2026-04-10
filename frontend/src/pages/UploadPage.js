import React, { useState, useRef } from 'react';
import { api } from '../services/auth';
import './UploadPage.css';

export default function UploadPage({ onResult, user, onUpgrade }) {
  const [file, setFile] = useState(null);
  const [dragging, setDragging] = useState(false);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState('');
  const [progress, setProgress] = useState(0);
  const inputRef = useRef();

  const handleFile = (f) => {
    if (!f) return;
    if (!f.name.toLowerCase().endsWith('.pdf')) { setError('Seuls les fichiers PDF sont acceptés'); return; }
    if (f.size > 10 * 1024 * 1024) { setError('Fichier trop volumineux (max 10MB)'); return; }
    setError(''); setFile(f);
  };

  const handleDrop = (e) => {
    e.preventDefault(); setDragging(false);
    handleFile(e.dataTransfer.files[0]);
  };

  const handleAnalyze = async () => {
    if (!file) return;
    setLoading(true); setError(''); setProgress(0);
    const formData = new FormData();
    formData.append('file', file);
    const interval = setInterval(() => setProgress(p => Math.min(p + 8, 85)), 200);
    try {
      const response = await api.post('/api/analyze', formData, { headers: { 'Content-Type': 'multipart/form-data' } });
      clearInterval(interval); setProgress(100);
      setTimeout(() => onResult(response.data, file.name), 400);
    } catch (err) {
      clearInterval(interval); setProgress(0);
      if (err.response?.data?.quotaExceeded) {
        setError('quota');
      } else {
        setError(err.response?.data?.error || 'Erreur de connexion au serveur.');
      }
      setLoading(false);
    }
  };

  const remaining = user?.remainingDocuments;
  const isPro = user?.isPro;

  return (
    <main className="upload-page">
      <div className="upload-hero">
        <div className="hero-tag">ANALYSE IA · DÉTECTION FRAUDE</div>
        <h1 className="hero-title">Vérifiez l'authenticité<br /><span className="hero-accent">d'un bulletin de salaire</span></h1>
        <p className="hero-sub">Analyse en temps réel : métadonnées PDF, calculs de cotisations,<br />vérification SIRET et structure documentaire française.</p>
      </div>

      {/* Quota indicator */}
      {user && !isPro && (
        <div className={`quota-indicator ${remaining <= 2 ? 'warning' : ''}`}>
          <span>🎁</span>
          <span><strong>{remaining}</strong> analyse{remaining !== 1 ? 's' : ''} gratuite{remaining !== 1 ? 's' : ''} restante{remaining !== 1 ? 's' : ''}</span>
          {remaining <= 3 && <button className="quota-upgrade-btn" onClick={onUpgrade}>Passer Pro →</button>}
        </div>
      )}

      {isPro && (
        <div className="quota-indicator pro">
          <span>⭐</span>
          <span>Plan Pro — analyses <strong>illimitées</strong></span>
        </div>
      )}

      <div className="upload-container">
        <div
          className={`drop-zone ${dragging ? 'dragging' : ''} ${file ? 'has-file' : ''}`}
          onDragOver={(e) => { e.preventDefault(); setDragging(true); }}
          onDragLeave={() => setDragging(false)}
          onDrop={handleDrop}
          onClick={() => !file && inputRef.current.click()}
        >
          <input ref={inputRef} type="file" accept=".pdf" style={{ display: 'none' }} onChange={e => handleFile(e.target.files[0])} />
          {!file ? (
            <div className="drop-content">
              <div className="drop-icon">
                <svg width="48" height="48" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.5">
                  <path d="M14 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V8z"/>
                  <polyline points="14 2 14 8 20 8"/>
                  <line x1="12" y1="18" x2="12" y2="12"/>
                  <polyline points="9 15 12 12 15 15"/>
                </svg>
              </div>
              <div className="drop-title">Déposez votre bulletin de salaire</div>
              <div className="drop-sub">PDF uniquement · 10MB maximum</div>
              <button className="drop-btn">Parcourir les fichiers</button>
            </div>
          ) : (
            <div className="file-ready">
              <div className="file-icon">📄</div>
              <div className="file-info">
                <div className="file-name">{file.name}</div>
                <div className="file-size">{(file.size / 1024).toFixed(0)} Ko · PDF</div>
              </div>
              <button className="file-remove" onClick={(e) => { e.stopPropagation(); setFile(null); }}>✕</button>
            </div>
          )}
        </div>

        {/* Quota exceeded */}
        {error === 'quota' && (
          <div className="quota-exceeded">
            <div className="qe-title">🔒 Limite gratuite atteinte</div>
            <div className="qe-desc">Vous avez utilisé vos 10 analyses gratuites. Passez au plan Pro pour continuer.</div>
            <button className="qe-btn" onClick={onUpgrade}>Passer Pro — 49€/mois →</button>
          </div>
        )}

        {error && error !== 'quota' && (
          <div className="error-msg"><span>⚠</span> {error}</div>
        )}

        {loading && (
          <div className="progress-container">
            <div className="progress-label"><span>Analyse en cours...</span><span>{progress}%</span></div>
            <div className="progress-bar"><div className="progress-fill" style={{ width: `${progress}%` }}></div></div>
            <div className="progress-steps">
              <span className={progress > 20 ? 'active' : ''}>Métadonnées</span>
              <span className={progress > 45 ? 'active' : ''}>SIRET</span>
              <span className={progress > 65 ? 'active' : ''}>Calculs</span>
              <span className={progress > 85 ? 'active' : ''}>Rapport</span>
            </div>
          </div>
        )}

        {!loading && file && error !== 'quota' && (
          <button className="analyze-btn" onClick={handleAnalyze}>
            <span className="analyze-icon">⬡</span> Lancer l'analyse
          </button>
        )}
      </div>

      <div className="features-grid">
        {[
          { icon: '🔍', title: 'Métadonnées PDF', desc: 'Détection du logiciel de création, modifications suspectes, anomalies de structure' },
          { icon: '🏢', title: 'Vérification SIRET', desc: "Contrôle du numéro SIRET via l'algorithme de Luhn et l'API INSEE" },
          { icon: '🧮', title: 'Calculs de paie', desc: 'Vérification du ratio Net/Brut, cohérence des cotisations, respect du SMIC' },
          { icon: '📋', title: 'Structure légale', desc: 'Présence des champs obligatoires selon la législation française du travail' },
        ].map((f, i) => (
          <div key={i} className="feature-card">
            <div className="feature-icon">{f.icon}</div>
            <div className="feature-title">{f.title}</div>
            <div className="feature-desc">{f.desc}</div>
          </div>
        ))}
      </div>
    </main>
  );
}
