# FraudDetect 🔍

Détection de fraude documentaire sur bulletins de salaire.
**Stack** : Spring Boot 3 · React 18 · PostgreSQL · Docker · Stripe

---

## Démarrage local (dev)

### Prérequis
- Java 17+, Maven 3.8+, Node 18+

### Backend
```bash
cd backend
mvn spring-boot:run
# API sur http://localhost:8080
```

### Frontend
```bash
cd frontend
npm install
npm start
# App sur http://localhost:3000
```

---

## Déploiement en production

### 1. Préparer le serveur (VPS Ubuntu 22.04+)
```bash
# Cloner le projet
git clone https://github.com/TON_USER/frauddetect.git
cd frauddetect

# Configurer les variables
cp .env.example .env
nano .env   # Remplir toutes les valeurs
```

### 2. Variables d'environnement (.env)
```
DB_PASSWORD=mot_de_passe_fort
JWT_SECRET=chaine_aleatoire_64_chars_minimum
STRIPE_API_KEY=sk_live_xxx
STRIPE_WEBHOOK_SECRET=whsec_xxx
STRIPE_PRICE_ID=price_xxx
FRONTEND_URL=https://votredomaine.com
```

### 3. Lancer la prod
```bash
chmod +x deploy.sh
./deploy.sh prod
```

### Commandes utiles
```bash
./deploy.sh status      # État des conteneurs
./deploy.sh logs        # Tous les logs
./deploy.sh logs backend  # Logs backend seul
./deploy.sh update      # Mise à jour sans downtime
./deploy.sh backup      # Backup base de données
./deploy.sh stop        # Arrêt
```

---

## Configuration Stripe

### Créer le produit
1. Dashboard Stripe → Produits → Créer
2. Nom : "FraudDetect Pro"
3. Prix récurrent : 49€/mois
4. Copier le `price_xxx` → `STRIPE_PRICE_ID`

### Configurer le webhook
1. Dashboard Stripe → Développeurs → Webhooks
2. URL endpoint : `https://votredomaine.com/api/stripe/webhook`
3. Événements à écouter :
   - `checkout.session.completed`
   - `customer.subscription.deleted`
4. Copier la clé de signature → `STRIPE_WEBHOOK_SECRET`

---

## SSL (HTTPS)

```bash
# Installer Certbot
apt install certbot

# Générer le certificat
certbot certonly --standalone -d votredomaine.com

# Copier les certificats
mkdir -p ssl
cp /etc/letsencrypt/live/votredomaine.com/fullchain.pem ssl/cert.pem
cp /etc/letsencrypt/live/votredomaine.com/privkey.pem ssl/key.pem

# Redémarrer
./deploy.sh update
```

---

## Architecture

```
Internet
  └─ Nginx :80/:443
       ├─ /           → React (build statique)
       └─ /api/       → Spring Boot :8080
                           ├─ H2 (dev)
                           └─ PostgreSQL (prod)
```

## Fonctionnalités

| Fonctionnalité | Free | Pro |
|---|---|---|
| Analyses de bulletins PDF | 10 | Illimitées |
| Vérification SIRET | ✅ | ✅ |
| Contrôle calculs de paie | ✅ | ✅ |
| Analyse métadonnées PDF | ✅ | ✅ |
| Historique des analyses | ✅ | ✅ |
| Support prioritaire | ❌ | ✅ |

## Score de confiance

| Score | Verdict |
|---|---|
| 75–100 | ✅ AUTHENTIQUE |
| 45–74 | ⚠ SUSPECT |
| 0–44 | ❌ FRAUDULEUX |

## Variables d'environnement complètes

| Variable | Description |
|---|---|
| `DB_USER` | Utilisateur PostgreSQL (défaut: frauddetect) |
| `DB_PASSWORD` | Mot de passe PostgreSQL |
| `JWT_SECRET` | Secret JWT (min 64 chars) |
| `STRIPE_API_KEY` | Clé secrète Stripe |
| `STRIPE_WEBHOOK_SECRET` | Secret webhook Stripe |
| `STRIPE_PRICE_ID` | ID du prix Stripe (49€/mois) |
| `FRONTEND_URL` | URL publique du frontend |
