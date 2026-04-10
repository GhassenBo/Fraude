import React, { useState, useRef } from 'react';
import { api } from '../services/auth';
import './UploadPage.css';

export default function UploadPage({ onResult, onBatchResult, user, onUpgrade }) {
  const [mode, setMode] = useState('single'); // 'single' | 'batch'

  // Single mode
  const [file, setFile] = useState(null);

  // Batch mode
  const [batchFiles, setBatchFiles] = useState([]);

  const [dragging, setDragging] = useState(false);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState('');
  const [progress, setProgress] = useState(0);
  const inputRef = useRef();
  const batchInputRef = useRef();

  const validateFile = (f) => {
    if (!f.name.toLowerCase().endsWith('.pdf')) return 'Seuls les fichiers PDF sont acceptés';
    if (f.size > 10 * 1024 * 1024) return 'Fichier trop volumineux (max 10MB)';
    return null;
  };

  const handleFile = (f) => {
    if (!f) return;
    const err = validateFile(f);
    if (err) { setError(err); return; }
    setError(''); setFile(f);
  };

  const handleBatchFiles = (newFiles) => {
    setError('');
    const valid = [];
    for (const f of newFiles) {
      const err = validateFile(f);
      if (err) { setError(err); return; }
      if (batchFiles.length + valid.length >= 10) { setError('Maximum 10 fichiers par lot'); return; }
      valid.push(f);
    }
    setBatchFiles(prev => [...prev, ...valid]);
  };

  const removeBatchFile = (idx) => setBatchFiles(prev => prev.filter((_, i) => i !== idx));

  const handleDrop = (e) => {
    e.preventDefault(); setDragging(false);
    const files = Array.from(e.dataTransfer.files);
    if (mode === 'single') {
      handleFile(files[0]);
    } else {
      handleBatchFiles(files);
    }
  };

  const handleAnalyze = async () => {
    if (mode === 'single') {
      if (!file) return;
      setLoading(true); setError(''); setProgress(0);
      const formData = new FormData();
      formData.append('file', file);
      const interval = setInterval(() => setProgress(p => Math.min(p + 8, 85)), 200);
      try {
        const res = await api.post('/api/analyze', formData, { headers: { 'Content-Type': 'multipart/form-data' } });
        clearInterval(interval); setProgress(100);
        setTimeout(() => onResult(res.data, file.name), 400);
      } catch (err) {
        clearInterval(interval); setProgress(0);
        if (err.response?.data?.quotaExceeded) setError('quota');
        else setError(err.response?.data?.error || 'Erreur de connexion au serveur.');
        setLoading(false);
      }
    } else {
      if (batchFiles.length === 0) return;
      setLoading(true); setError(''); setProgress(0);
      const formData = new FormData();
      batchFiles.forEach(f => formData.append('files', f));
      const interval = setInterval(() => setProgress(p => Math.min(p + 5, 85)), 300);
      try {
        const res = await api.post('/api/analyze/batch', formData, { headers: { 'Content-Type': 'multipart/form-data' } });
        clearInterval(interval); setProgress(100);
        setTimeout(() => onBatchResult(res.data), 400);
      } catch (err) {
        clearInterval(interval); setProgress(0);
        if (err.response?.data?.quotaExceeded) setError('quota');
        else setError(err.response?.data?.error || 'Erreur de connexion au serveur.');
        setLoading(false);
      }
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

      {/* Mode toggle */}
      <div className="mode-toggle">
        <button
          className={`mode-btn ${mode === 'single' ? 'active' : ''}`}
          onClick={() => { setMode('single'); setError(''); setBatchFiles([]); }}
        >
          Document unique
        </button>
        <button
          className={`mode-btn ${mode === 'batch' ? 'active' : ''}`}
          onClick={() => { setMode('batch'); setError(''); setFile(null); }}
        >
          Analyse en lot <span className="mode-badge">NEW</span>
        </button>
      </div>

      <div className="upload-container">
        {mode === 'single' ? (
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
        ) : (
          <div className="batch-zone">
            <div
              className={`drop-zone batch-drop ${dragging ? 'dragging' : ''}`}
              onDragOver={(e) => { e.preventDefault(); setDragging(true); }}
              onDragLeave={() => setDragging(false)}
              onDrop={handleDrop}
              onClick={() => batchInputRef.current.click()}
            >
              <input
                ref={batchInputRef}
                type="file"
                accept=".pdf"
                multiple
                style={{ display: 'none' }}
                onChange={e => handleBatchFiles(Array.from(e.target.files))}
              />
              <div className="drop-content">
                <div className="drop-icon">
                  <svg width="40" height="40" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.5">
                    <rect x="2" y="5" width="7" height="14" rx="1"/>
                    <rect x="9.5" y="3" width="7" height="14" rx="1"/>
                    <rect x="17" y="7" width="7" height="14" rx="1"/>
                  </svg>
                </div>
                <div className="drop-title">Déposez plusieurs bulletins</div>
                <div className="drop-sub">Jusqu'à 10 PDF · Score global du dossier</div>
                <button className="drop-btn">Ajouter des fichiers</button>
              </div>
            </div>

            {batchFiles.length > 0 && (
              <div className="batch-file-list">
                <div className="batch-list-header">
                  <span>{batchFiles.length} fichier{batchFiles.length > 1 ? 's' : ''} sélectionné{batchFiles.length > 1 ? 's' : ''}</span>
                  <button className="clear-all-btn" onClick={() => setBatchFiles([])}>Tout supprimer</button>
                </div>
                {batchFiles.map((f, i) => (
                  <div key={i} className="batch-file-item">
                    <span className="batch-file-num">{i + 1}</span>
                    <span className="batch-file-name">{f.name}</span>
                    <span className="batch-file-size">{(f.size / 1024).toFixed(0)} Ko</span>
                    <button className="file-remove" onClick={() => removeBatchFile(i)}>✕</button>
                  </div>
                ))}
              </div>
            )}
          </div>
        )}

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
            <div className="progress-label">
              <span>{mode === 'batch' ? `Analyse du lot en cours...` : 'Analyse en cours...'}</span>
              <span>{progress}%</span>
            </div>
            <div className="progress-bar"><div className="progress-fill" style={{ width: `${progress}%` }}></div></div>
            <div className="progress-steps">
              <span className={progress > 20 ? 'active' : ''}>Métadonnées</span>
              <span className={progress > 45 ? 'active' : ''}>SIRET</span>
              <span className={progress > 65 ? 'active' : ''}>Calculs</span>
              <span className={progress > 85 ? 'active' : ''}>Rapport</span>
            </div>
          </div>
        )}

        {!loading && (mode === 'single' ? file : batchFiles.length > 0) && error !== 'quota' && (
          <button className="analyze-btn" onClick={handleAnalyze}>
            <span className="analyze-icon">⬡</span>
            {mode === 'batch'
              ? `Analyser ${batchFiles.length} document${batchFiles.length > 1 ? 's' : ''}`
              : 'Lancer l\'analyse'}
          </button>
        )}
      </div>

      <div className="features-grid">
        {[
          { icon: '🔍', title: 'Métadonnées PDF',   desc: 'Détection du logiciel de création, modifications suspectes, anomalies de structure' },
          { icon: '🏢', title: 'Vérification SIRET', desc: "Contrôle du numéro SIRET via l'algorithme de Luhn et l'API INSEE" },
          { icon: '🧮', title: 'Calculs de paie',    desc: 'Vérification du ratio Net/Brut, cohérence des cotisations, respect du SMIC' },
          { icon: '📋', title: 'Structure légale',   desc: 'Présence des champs obligatoires selon la législation française du travail' },
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
