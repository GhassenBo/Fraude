import React, { useState } from 'react';
import { api } from '../services/auth';
import './PricingPage.css';

export default function PricingPage({ user, onBack }) {
  const [loading, setLoading] = useState(false);

  const handleUpgrade = async () => {
    setLoading(true);
    try {
      const res = await api.post('/api/stripe/checkout');
      window.location.href = res.data.url;
    } catch (e) {
      alert('Erreur : ' + (e.response?.data?.error || e.message));
      setLoading(false);
    }
  };

  return (
    <div className="pricing-page">
      <div className="pricing-hero">
        <div className="pricing-tag">TARIFS SIMPLES · SANS ENGAGEMENT</div>
        <h1>Choisissez votre plan</h1>
        <p>Commencez gratuitement, passez Pro quand vous en avez besoin</p>
      </div>

      <div className="plans-grid">
        {/* Free */}
        <div className="plan-card">
          <div className="plan-header">
            <div className="plan-name">Gratuit</div>
            <div className="plan-price">
              <span className="price-amount">0€</span>
              <span className="price-period">pour toujours</span>
            </div>
          </div>
          <div className="plan-desc">Parfait pour tester ou un usage ponctuel</div>
          <ul className="plan-features">
            <li className="feat ok">✓ 10 analyses de bulletins PDF</li>
            <li className="feat ok">✓ Vérification SIRET</li>
            <li className="feat ok">✓ Contrôle des calculs de paie</li>
            <li className="feat ok">✓ Analyse des métadonnées PDF</li>
            <li className="feat no">✗ Analyses illimitées</li>
            <li className="feat no">✗ Historique complet</li>
            <li className="feat no">✗ Export des rapports</li>
            <li className="feat no">✗ Support prioritaire</li>
          </ul>
          <div className={`plan-cta ${user?.isPro ? 'current' : ''}`}>
            {!user?.isPro ? (
              <button className="cta-btn secondary" onClick={onBack}>
                Plan actuel
              </button>
            ) : (
              <div className="cta-label">Plan précédent</div>
            )}
          </div>
        </div>

        {/* Pro */}
        <div className="plan-card featured">
          <div className="plan-popular">⭐ Le plus populaire</div>
          <div className="plan-header">
            <div className="plan-name">Pro</div>
            <div className="plan-price">
              <span className="price-amount">49€</span>
              <span className="price-period">/ mois · HT</span>
            </div>
          </div>
          <div className="plan-desc">Pour les agences, RH et professionnels de l'immobilier</div>
          <ul className="plan-features">
            <li className="feat ok">✓ <strong>Analyses illimitées</strong></li>
            <li className="feat ok">✓ Vérification SIRET (API INSEE)</li>
            <li className="feat ok">✓ Contrôle des calculs de paie</li>
            <li className="feat ok">✓ Analyse des métadonnées PDF</li>
            <li className="feat ok">✓ <strong>Historique complet</strong></li>
            <li className="feat ok">✓ <strong>Export PDF des rapports</strong></li>
            <li className="feat ok">✓ <strong>Support prioritaire</strong></li>
            <li className="feat ok">✓ <strong>API d'intégration</strong> (bientôt)</li>
          </ul>
          <div className="plan-cta">
            {user?.isPro ? (
              <div className="cta-label active">✓ Plan actuel</div>
            ) : (
              <button className="cta-btn primary" onClick={handleUpgrade} disabled={loading}>
                {loading ? 'Redirection...' : 'Passer au Pro →'}
              </button>
            )}
          </div>
          <div className="plan-note">Sans engagement · Résiliation à tout moment</div>
        </div>
      </div>

      {/* FAQ */}
      <div className="faq-section">
        <h2>Questions fréquentes</h2>
        <div className="faq-grid">
          {[
            { q: 'Comment fonctionne la période gratuite ?', a: 'Vous bénéficiez de 10 analyses complètes sans carte bancaire. Aucune limite de temps.' },
            { q: 'Puis-je annuler à tout moment ?', a: 'Oui, l\'abonnement Pro est sans engagement. Vous pouvez annuler depuis votre espace client Stripe à tout moment.' },
            { q: 'Mes documents sont-ils conservés ?', a: 'Non. Vos bulletins de salaire ne sont jamais stockés sur nos serveurs. Seul le résultat de l\'analyse est conservé.' },
            { q: "L'analyse est-elle fiable à 100% ?", a: 'Le système détecte les anomalies les plus courantes avec une précision élevée. Pour les cas critiques, une vérification humaine complémentaire reste recommandée.' },
          ].map((item, i) => (
            <div key={i} className="faq-item">
              <div className="faq-q">{item.q}</div>
              <div className="faq-a">{item.a}</div>
            </div>
          ))}
        </div>
      </div>

      <div className="pricing-back">
        <button className="back-btn" onClick={onBack}>← Retour</button>
      </div>
    </div>
  );
}
