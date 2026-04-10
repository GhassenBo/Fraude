import React, { useState } from 'react';
import './ResultPage.css';

export default function ResultPage({ result, filename, onReset }) {
  const [activeTab, setActiveTab] = useState('overview');

  const scoreColor = result.color === 'green'
    ? 'var(--green)'
    : result.color === 'orange'
    ? 'var(--orange)'
    : 'var(--red)';

  const categories = [...new Set(result.checks?.map(c => c.category) || [])];

  return (
    <main className="result-page">
      {/* Score Hero */}
      <div className="score-hero">
        <div className="score-ring-container">
          <ScoreRing score={result.score} color={scoreColor} />
        </div>
        <div className="score-info">
          <div className="score-filename">{filename}</div>
          <div className="score-verdict" style={{ color: scoreColor }}>
            {result.verdict}
          </div>
          <div className="score-desc">
            {result.verdict === 'AUTHENTIQUE' && "Le document présente les caractéristiques d'un bulletin de salaire authentique."}
            {result.verdict === 'SUSPECT' && "Des anomalies ont été détectées. Une vérification manuelle est recommandée."}
            {result.verdict === 'FRAUDULEUX' && "Plusieurs indicateurs critiques de fraude ont été identifiés dans ce document."}
          </div>
          <button className="new-analysis-btn" onClick={onReset}>
            ← Nouvelle analyse
          </button>
        </div>
      </div>

      {/* Tabs */}
      <div className="tabs">
        {['overview', 'details', 'document'].map(tab => (
          <button
            key={tab}
            className={`tab ${activeTab === tab ? 'active' : ''}`}
            onClick={() => setActiveTab(tab)}
          >
            {tab === 'overview' && '📊 Vue d\'ensemble'}
            {tab === 'details' && '🔍 Contrôles détaillés'}
            {tab === 'document' && '📄 Infos document'}
          </button>
        ))}
      </div>

      {/* Overview Tab */}
      {activeTab === 'overview' && (
        <div className="tab-content">
          <div className="checks-summary">
            {categories.map(cat => {
              const catChecks = result.checks.filter(c => c.category === cat);
              const failed = catChecks.filter(c => c.status === 'FAILED').length;
              const warnings = catChecks.filter(c => c.status === 'WARNING').length;
              const ok = catChecks.filter(c => c.status === 'OK').length;
              const status = failed > 0 ? 'FAILED' : warnings > 0 ? 'WARNING' : 'OK';

              return (
                <div key={cat} className={`summary-card status-${status.toLowerCase()}`}>
                  <div className="summary-header">
                    <span className="summary-cat">{cat}</span>
                    <StatusBadge status={status} />
                  </div>
                  <div className="summary-counts">
                    {ok > 0 && <span className="count ok">{ok} OK</span>}
                    {warnings > 0 && <span className="count warning">{warnings} avert.</span>}
                    {failed > 0 && <span className="count failed">{failed} échec</span>}
                  </div>
                </div>
              );
            })}
          </div>

          {/* Critical issues */}
          {result.checks.filter(c => c.status === 'FAILED').length > 0 && (
            <div className="critical-section">
              <h3 className="section-title">⚠ Problèmes critiques</h3>
              {result.checks.filter(c => c.status === 'FAILED').map((check, i) => (
                <CheckItem key={i} check={check} />
              ))}
            </div>
          )}
        </div>
      )}

      {/* Details Tab */}
      {activeTab === 'details' && (
        <div className="tab-content">
          {categories.map(cat => (
            <div key={cat} className="category-section">
              <h3 className="category-title">{cat}</h3>
              {result.checks.filter(c => c.category === cat).map((check, i) => (
                <CheckItem key={i} check={check} />
              ))}
            </div>
          ))}
        </div>
      )}

      {/* Document Tab */}
      {activeTab === 'document' && (
        <div className="tab-content">
          <div className="doc-grid">
            {result.documentInfo && Object.entries({
              'Employeur': result.documentInfo.employeur,
              'SIRET': result.documentInfo.siret,
              'Employé': result.documentInfo.employe,
              'Période': result.documentInfo.periode,
              'Salaire brut': result.documentInfo.salaireBrut,
              'Net à payer': result.documentInfo.salaireNet,
              'Pages': result.documentInfo.pageCount,
              'Logiciel PDF': result.documentInfo.pdfCreatedWith,
              'Date création': result.documentInfo.pdfCreationDate,
              'Modifié': result.documentInfo.pdfModified ? '⚠ OUI' : 'Non',
            }).map(([key, value]) => (
              <div key={key} className="doc-field">
                <div className="doc-label">{key}</div>
                <div className={`doc-value ${key === 'Modifié' && value?.includes('OUI') ? 'doc-alert' : ''}`}>
                  {value || '—'}
                </div>
              </div>
            ))}
          </div>
        </div>
      )}
    </main>
  );
}

function ScoreRing({ score, color }) {
  const radius = 70;
  const circumference = 2 * Math.PI * radius;
  const offset = circumference - (score / 100) * circumference;

  return (
    <div className="score-ring">
      <svg width="180" height="180" viewBox="0 0 180 180">
        <circle cx="90" cy="90" r={radius} fill="none" stroke="var(--border)" strokeWidth="8" />
        <circle
          cx="90" cy="90" r={radius}
          fill="none"
          stroke={color}
          strokeWidth="8"
          strokeDasharray={circumference}
          strokeDashoffset={offset}
          strokeLinecap="round"
          transform="rotate(-90 90 90)"
          style={{ transition: 'stroke-dashoffset 1s ease', filter: `drop-shadow(0 0 8px ${color})` }}
        />
        <text x="90" y="82" textAnchor="middle" fill={color} fontSize="36" fontFamily="Space Mono" fontWeight="700">
          {score}
        </text>
        <text x="90" y="104" textAnchor="middle" fill="var(--text-muted)" fontSize="12" fontFamily="DM Sans">
          score
        </text>
      </svg>
    </div>
  );
}

function StatusBadge({ status }) {
  const config = {
    OK: { label: 'OK', color: 'var(--green)' },
    WARNING: { label: 'ATTENTION', color: 'var(--orange)' },
    FAILED: { label: 'ÉCHEC', color: 'var(--red)' },
  };
  const c = config[status] || config.OK;
  return (
    <span className="status-badge" style={{ color: c.color, borderColor: c.color }}>
      {c.label}
    </span>
  );
}

function CheckItem({ check }) {
  const icons = { OK: '✓', WARNING: '⚠', FAILED: '✕' };
  const colors = { OK: 'var(--green)', WARNING: 'var(--orange)', FAILED: 'var(--red)' };

  return (
    <div className={`check-item check-${check.status.toLowerCase()}`}>
      <div className="check-icon" style={{ color: colors[check.status] }}>
        {icons[check.status]}
      </div>
      <div className="check-body">
        <div className="check-label">{check.label}</div>
        <div className="check-detail">{check.detail}</div>
      </div>
    </div>
  );
}
