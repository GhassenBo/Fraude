import React, { useState } from 'react';
import { login } from '../services/auth';
import './AuthPage.css';

export default function LoginPage({ onLogin, onRegister }) {
  const [email, setEmail] = useState('');
  const [password, setPassword] = useState('');
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState('');

  const handleSubmit = async (e) => {
    e.preventDefault();
    setLoading(true); setError('');
    try {
      const user = await login(email, password);
      onLogin(user);
    } catch (err) {
      setError(err.response?.data?.error || 'Erreur de connexion');
    } finally { setLoading(false); }
  };

  return (
    <div className="auth-page">
      <div className="auth-card">
        <div className="auth-header">
          <div className="auth-icon">🔐</div>
          <h1>Connexion</h1>
          <p>Accédez à votre compte FraudDetect</p>
        </div>

        <div className="free-banner">
          <span className="free-icon">🎁</span>
          <span><strong>10 analyses gratuites</strong> à l'inscription — sans carte bancaire</span>
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
              placeholder="••••••••" required />
          </div>
          {error && <div className="auth-error">⚠ {error}</div>}
          <button type="submit" className="auth-btn" disabled={loading}>
            {loading ? 'Connexion...' : 'Se connecter'}
          </button>
        </form>

        <div className="auth-footer">
          Pas encore de compte ?{' '}
          <button className="auth-link" onClick={onRegister}>Créer un compte gratuit</button>
        </div>
      </div>
    </div>
  );
}
