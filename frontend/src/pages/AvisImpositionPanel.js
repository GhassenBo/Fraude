import React, { useState, useRef } from 'react';
import { api } from '../services/auth';
import './AvisImpositionPanel.css';

/**
 * Rapprochement d'un avis d'imposition avec les bulletins.
 *
 * La verification d'authenticite aupres de l'administration reste manuelle :
 * aucune API publique ne l'expose. L'application prepare les identifiants et
 * ouvre le service officiel, le gestionnaire conclut.
 */
export default function AvisImpositionPanel() {
  const [file, setFile] = useState(null);
  const [netImposable, setNetImposable] = useState('');
  const [result, setResult] = useState(null);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState('');
  const inputRef = useRef();

  const pick = (f) => {
    if (!f) return;
    if (!f.name.toLowerCase().endsWith('.pdf')) { setError('Seuls les fichiers PDF sont acceptés'); return; }
    if (f.size > 10 * 1024 * 1024) { setError('Fichier trop volumineux (max 10MB)'); return; }
    setError(''); setResult(null); setFile(f);
  };

  const submit = async () => {
    if (!file) return;
    setLoading(true); setError('');
    try {
      const form = new FormData();
      form.append('file', file);
      if (netImposable) form.append('netImposableMensuel', netImposable.replace(',', '.'));
      const res = await api.post('/api/analyze/avis-imposition', form,
        { headers: { 'Content-Type': 'multipart/form-data' } });
      setResult(res.data);
    } catch (err) {
      setError(err.response?.data?.error || 'Erreur lors de l’analyse.');
    }
    setLoading(false);
  };

  const reset = () => { setFile(null); setResult(null); setError(''); };

  return (
    <section className="avis-panel">
      <h2 className="avis-title">Rapprocher un avis d&rsquo;imposition</h2>
      <p className="avis-sub">
        L&rsquo;avis est bien plus difficile à falsifier qu&rsquo;un bulletin. Comparer les
        revenus déclarés à l&rsquo;administration avec ceux des bulletins révèle les écarts.
      </p>

      {!result && (
        <>
          <div className="avis-row">
            <button className="avis-file-btn" onClick={() => inputRef.current?.click()}>
              {file ? file.name : 'Choisir l’avis (PDF)'}
            </button>
            <input ref={inputRef} type="file" accept=".pdf" hidden
                   onChange={(e) => pick(e.target.files?.[0])} />

            <label className="avis-field">
              <span>Net imposable mensuel</span>
              <input type="text" inputMode="decimal" placeholder="2630"
                     value={netImposable}
                     onChange={(e) => setNetImposable(e.target.value)} />
            </label>
          </div>
          <p className="avis-hint">
            Relevez le net imposable sur un bulletin récent. Sans cette valeur, seules
            les données de l&rsquo;avis sont extraites, sans rapprochement.
          </p>

          {error && <div className="avis-error">{error}</div>}

          <button className="avis-submit" onClick={submit} disabled={!file || loading}>
            {loading ? 'Analyse…' : 'Rapprocher'}
          </button>
        </>
      )}

      {result && (
        <div className="avis-result">
          {result.checks?.map((c, i) => (
            <div key={i} className={`avis-check avis-${(c.status || '').toLowerCase()}`}>
              <span className="avis-check-icon">
                {c.status === 'OK' ? '✓' : c.status === 'WARNING' ? '!' : '✕'}
              </span>
              <div>
                <strong>{c.label}</strong>
                <span>{c.detail}</span>
              </div>
            </div>
          ))}

          {result.avis?.salairesDeclares?.length > 0 && (
            <div className="avis-ids">
              <div>
                <span>Salaires déclarés</span>
                <code>
                  {result.avis.salairesDeclares
                    .map((v) => `${Math.round(v).toLocaleString('fr-FR')} €`)
                    .join('  ·  ')}
                </code>
              </div>
              {result.avis.anneeRevenus && (
                <div>
                  <span>Revenus de</span>
                  <code>{result.avis.anneeRevenus}</code>
                </div>
              )}
            </div>
          )}

          {result.verificationUrl ? (
            <a className="avis-verify" href={result.verificationUrl}
               target="_blank" rel="noopener noreferrer">
              Vérifier auprès de l&rsquo;administration fiscale
            </a>
          ) : (
            <p className="avis-hint">
              Confirmez l&rsquo;authenticité de l&rsquo;avis sur le service officiel de
              vérification, à l&rsquo;aide du numéro fiscal et de la référence figurant
              en tête du document.
            </p>
          )}

          <button className="avis-reset" onClick={reset}>Analyser un autre avis</button>
        </div>
      )}
    </section>
  );
}
