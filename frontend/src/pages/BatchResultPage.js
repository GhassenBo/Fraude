import React, { useState } from 'react';
import { exportBatchPdf } from '../services/pdfExport';
import './BatchResultPage.css';

export default function BatchResultPage({ batchResult, onReset, onUpgrade }) {
  const [expanded, setExpanded] = useState(null);

  const scoreColor = (color) =>
    color === 'green' ? 'var(--green)' : color === 'orange' ? 'var(--orange)' : 'var(--red)';

  const globalColor = scoreColor(batchResult.globalColor);

  return (
    <main className="batch-page">
      {/* Global score hero */}
      <div className="batch-hero">
        <div className="batch-hero-left">
          <div className="batch-label">DOSSIER COMPLET · {batchResult.documentsAnalyzed} DOCUMENTS</div>
          <div className="batch-verdict" style={{ color: globalColor }}>
            {batchResult.globalVerdict}
          </div>
          <div className="batch-sub">
            Score global basé sur le document le plus risqué du dossier.
          </div>
          <div className="batch-actions">
            <button className="new-analysis-btn" onClick={onReset}>← Nouvelle analyse</button>
            <button className="export-btn" onClick={() => exportBatchPdf(batchResult)}>
              ↓ Exporter le dossier PDF
            </button>
          </div>
        </div>
        <div className="batch-score-ring">
          <GlobalScoreRing score={batchResult.globalScore} color={globalColor} />
        </div>
      </div>

      {/* Summary row */}
      <div className="batch-stats">
        {[
          { label: 'Analysés', value: batchResult.documentsAnalyzed },
          { label: 'Authentiques', value: batchResult.results.filter(r => r.verdict === 'AUTHENTIQUE').length, color: 'var(--green)' },
          { label: 'Suspects', value: batchResult.results.filter(r => r.verdict === 'SUSPECT').length, color: 'var(--orange)' },
          { label: 'Frauduleux', value: batchResult.results.filter(r => r.verdict === 'FRAUDULEUX').length, color: 'var(--red)' },
        ].map((s, i) => (
          <div key={i} className="batch-stat">
            <div className="batch-stat-value" style={{ color: s.color || 'var(--text)' }}>{s.value}</div>
            <div className="batch-stat-label">{s.label}</div>
          </div>
        ))}
      </div>

      {batchResult.crossChecks?.length > 0 && (
        <>
          <h2 className="batch-section-title">Recoupement entre bulletins</h2>
          <div className="batch-cross">
            {batchResult.crossChecks.map((check, i) => (
              <div key={i} className={`cross-check cross-${(check.status || '').toLowerCase()}`}>
                <span className="cross-icon">
                  {check.status === 'OK' ? '✓' : check.status === 'WARNING' ? '!' : '✕'}
                </span>
                <div className="cross-body">
                  <strong>{check.label}</strong>
                  <span>{check.detail}</span>
                </div>
              </div>
            ))}
          </div>
        </>
      )}

      {batchResult.dossier && <DossierSection dossier={batchResult.dossier} />}

      {/* Document cards */}
      <h2 className="batch-section-title">Résultats par document</h2>
      <div className="batch-docs">
        {batchResult.results.map((result, i) => {
          const c = scoreColor(result.color);
          const fname = batchResult.filenames?.[i] || `Document ${i + 1}`;
          const isExpanded = expanded === i;
          const failed = result.checks?.filter(c => c.status === 'FAILED').length || 0;
          const warnings = result.checks?.filter(c => c.status === 'WARNING').length || 0;

          return (
            <div key={i} className={`batch-doc-card ${isExpanded ? 'expanded' : ''}`}>
              <div className="batch-doc-header" onClick={() => setExpanded(isExpanded ? null : i)}>
                <div className="batch-doc-num">{i + 1}</div>
                <div className="batch-doc-info">
                  <div className="batch-doc-name">{fname}</div>
                  <div className="batch-doc-counts">
                    {failed > 0 && <span className="count-badge failed">{failed} échec{failed > 1 ? 's' : ''}</span>}
                    {warnings > 0 && <span className="count-badge warning">{warnings} avert.</span>}
                    {failed === 0 && warnings === 0 && <span className="count-badge ok">Tout OK</span>}
                  </div>
                </div>
                <div className="batch-doc-score" style={{ color: c }}>
                  <span className="score-num">{result.score}</span>
                  <span className="score-verdict">{result.verdict}</span>
                </div>
                <div className="expand-icon">{isExpanded ? '▲' : '▼'}</div>
              </div>

              {isExpanded && (
                <div className="batch-doc-detail">
                  {[...new Set(result.checks?.map(c => c.category) || [])].map(cat => (
                    <div key={cat} className="mini-category">
                      <div className="mini-cat-title">{cat}</div>
                      {result.checks.filter(c => c.category === cat).map((check, j) => (
                        <div key={j} className={`mini-check check-${check.status.toLowerCase()}`}>
                          <span className="mini-icon">
                            {check.status === 'OK' ? '✓' : check.status === 'WARNING' ? '⚠' : '✕'}
                          </span>
                          <span className="mini-label">{check.label}</span>
                          <span className="mini-detail">{check.detail}</span>
                        </div>
                      ))}
                    </div>
                  ))}
                </div>
              )}
            </div>
          );
        })}
      </div>
    </main>
  );
}

/**
 * Controles issus de l'avis d'imposition joint au dossier : rapprochement des
 * revenus, cachet 2D-DOC, et concordance d'identite. La base du rapprochement
 * est affichee : le gestionnaire doit pouvoir voir de quel bulletin sort le
 * montant compare, et non seulement son verdict.
 */
function DossierSection({ dossier }) {
  const checks = [...(dossier.dossierChecks || []), ...(dossier.avisChecks || [])];
  if (checks.length === 0) return null;

  const base = dossier.baseRapprochement;

  return (
    <>
      <h2 className="batch-section-title">
        Avis d&rsquo;imposition{dossier.fichier ? ` · ${dossier.fichier}` : ''}
      </h2>

      {base && (
        <p className="batch-dossier-base">
          Base du rapprochement : {base.netImposableMensuel.toLocaleString('fr-FR', {
            minimumFractionDigits: 2, maximumFractionDigits: 2,
          })} € par mois, {base.origine}.
        </p>
      )}

      <div className="batch-cross">
        {checks.map((check, i) => (
          <div key={i} className={`cross-check cross-${(check.status || '').toLowerCase()}`}>
            <span className="cross-icon">
              {check.status === 'OK' ? '✓' : check.status === 'WARNING' ? '!' : '✕'}
            </span>
            <div className="cross-body">
              <strong>{check.label}</strong>
              <span>{check.detail}</span>
            </div>
          </div>
        ))}
      </div>
    </>
  );
}

function GlobalScoreRing({ score, color }) {
  const r = 65;
  const circ = 2 * Math.PI * r;
  const offset = circ - (score / 100) * circ;
  return (
    <div className="score-ring">
      <svg width="170" height="170" viewBox="0 0 170 170">
        <circle cx="85" cy="85" r={r} fill="none" stroke="var(--border)" strokeWidth="8" />
        <circle
          cx="85" cy="85" r={r}
          fill="none" stroke={color} strokeWidth="8"
          strokeDasharray={circ} strokeDashoffset={offset}
          strokeLinecap="round"
          transform="rotate(-90 85 85)"
          style={{ transition: 'stroke-dashoffset 1s ease', filter: `drop-shadow(0 0 8px ${color})` }}
        />
        <text x="85" y="78" textAnchor="middle" fill={color} fontSize="34" fontFamily="Space Mono" fontWeight="700">{score}</text>
        <text x="85" y="98" textAnchor="middle" fill="var(--text-muted)" fontSize="11" fontFamily="DM Sans">score global</text>
      </svg>
    </div>
  );
}
