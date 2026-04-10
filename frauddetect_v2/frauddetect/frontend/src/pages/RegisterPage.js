import React, { useState } from 'react';
import { register } from '../services/auth';
import './AuthPage.css';

export default function RegisterPage({ onLogin, onBack }) {
  const [email, setEmail] = useState('');
  const [password, setPassword] = useState('');
  const [confirm, setConfirm] = useState('');
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState('');

  const handleSubmit = async (e) => {
    e.preventDefault();
    if (password !== confirm) { setError('Les mots de passe ne correspondent pas'); return; }
    if (password.length < 8) { setError('Mot de passe minimum 8 caractères'); return; }
    setLoading(true); setError('');
    try {
      const user = await register(email, password);
      onLogin(user);
    } catch (err) {
      setError(err.response?.data?.error || 'Erreur lors de l\'inscription');
    } finally { setLoading(false); }
  };

  return (
    <div className="auth-page">
      <div className="auth-card">
        <div className="auth-header">
          <div className="auth-icon">✨</div>
          <h1>Créer un compte</h1>
          <p>Commencez avec 10 analyses gratuites</p>
        </div>

        <div className="free-banner">
          <span className="free-icon">🎁</span>
          <span><strong>10 analyses gratuites</strong> · Pas de carte bancaire requise</span>
        </div>

        <form onSubmit={handleSubmit} className="auth-form">
          <div className="field">
            <label>Email</label>
            <input type="email" value={email} onChange={e => setEmail(e.target.value)}
              placeholder="vous@email.com" required autoFocus />
          </div>
          <div className="field">
            <label>Mot de passe</label>
            <input type="password" value={password} onChange={e => setPassword(e.target.value)}
              placeholder="Minimum 8 caractères" required />
          </div>
          <div className="field">
            <label>Confirmer le mot de passe</label>
            <input type="password" value={confirm} onChange={e => setConfirm(e.target.value)}
              placeholder="••••••••" required />
          </div>
          {error && <div className="auth-error">⚠ {error}</div>}
          <button type="submit" className="auth-btn" disabled={loading}>
            {loading ? 'Création...' : 'Créer mon compte gratuit'}
          </button>
        </form>

        <div className="auth-footer">
          Déjà un compte ?{' '}
          <button className="auth-link" onClick={onBack}>Se connecter</button>
        </div>
      </div>
    </div>
  );
}
