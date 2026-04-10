# FraudDetect

Plateforme de détection de fraude documentaire sur les **bulletins de salaire français**.

Analyse multi-couches : métadonnées PDF · vérification SIRET (INSEE) · calculs salariaux · structure documentaire. Score de confiance de 0 à 100 avec verdict automatisé.

---

## Stack technique

| Couche | Technologies |
|--------|-------------|
| **Backend** | Java 17 · Spring Boot 3.2 · Spring Security · JWT (JJWT) |
| **Frontend** | React 18 · Axios |
| **Base de données** | PostgreSQL 16 (prod) · H2 in-memory (dev) |
| **PDF** | Apache PDFBox 3.0 |
| **Paiement** | Stripe (Checkout · Subscriptions · Webhooks) |
| **Infra** | Docker · Docker Compose · Nginx · Let's Encrypt |

---

## Fonctionnalités

### Analyse de bulletins PDF
- Upload de fichiers PDF jusqu'à 10 MB
- **Score de confiance 0–100** avec verdict automatique
- 4 catégories d'analyse :

| Catégorie | Contrôles effectués |
|-----------|---------------------|
| **Métadonnées** | Logiciel de création (Sage, ADP, Silae…), modification après création, nombre de pages |
| **Employeur** | Format SIRET (14 chiffres), algorithme de Luhn, vérification API INSEE |
| **Calculs** | Ratio Net/Brut, cohérence des cotisations, comparaison SMIC |
| **Structure** | Présence des champs obligatoires (SIRET, convention collective, congés, cotisations, net à payer) |

### Modèle freemium
| | Free | Pro (49€/mois) |
|--|------|----------------|
| Analyses PDF | 10 | Illimitées |
| Vérification SIRET | ✓ | ✓ |
| Contrôle des calculs | ✓ | ✓ |
| Historique des analyses | ✓ | ✓ |
| Support prioritaire | — | ✓ |

### Score de confiance
| Score | Verdict | Interprétation |
|-------|---------|----------------|
| 75 – 100 | **AUTHENTIQUE** | Document probablement authentique |
| 45 – 74 | **SUSPECT** | Anomalies détectées, vérification recommandée |
| 0 – 44 | **FRAUDULEUX** | Multiples incohérences, fort risque de fraude |

---

## Architecture

```
Internet
  └─ Nginx :80 / :443
       ├─ /           → React (build statique)
       └─ /api/       → Spring Boot :8080
                           ├─ PostgreSQL (prod) / H2 (dev)
                           ├─ API INSEE (vérification SIRET)
                           └─ Stripe API (paiements)
```

### Structure du projet
```
frauddetect/
├── backend/
│   ├── src/main/java/com/frauddetect/
│   │   ├── config/         # Spring Security, CORS
│   │   ├── controller/     # Endpoints REST (Auth, Analysis, Stripe)
│   │   ├── dto/            # Data Transfer Objects
│   │   ├── entity/         # Entités JPA (User, Analysis)
│   │   ├── model/          # Modèles de réponse (AnalysisResult)
│   │   ├── repository/     # Interfaces Spring Data JPA
│   │   ├── security/       # Filtre JWT, utilitaire JWT
│   │   ├── service/        # Logique métier (Auth, FraudDetection, Siret, Salary, Stripe)
│   │   └── util/           # PdfAnalyzer (PDFBox)
│   ├── src/main/resources/
│   │   ├── application.properties       # Config dev (H2)
│   │   └── application-prod.properties  # Config prod (PostgreSQL)
│   └── Dockerfile
├── frontend/
│   ├── src/
│   │   ├── pages/          # UploadPage, ResultPage, DashboardPage, PricingPage, Auth
│   │   └── services/       # Client Axios + gestion token JWT
│   ├── nginx.conf          # Config Nginx (reverse proxy + SSL)
│   └── Dockerfile
├── docker-compose.yml      # Orchestration (db, backend, frontend)
├── deploy.sh               # Script de déploiement (prod/update/logs/backup…)
├── start.sh                # Démarrage rapide en développement
├── .env.example            # Modèle de variables d'environnement
└── README.md
```

---

## Démarrage rapide — développement

### Prérequis
- Java 17+
- Maven 3.8+
- Node 18+

### Option 1 — Script tout-en-un
```bash
chmod +x start.sh
./start.sh
```
Frontend : http://localhost:3000 · Backend : http://localhost:8080

### Option 2 — Lancement manuel

**Backend**
```bash
cd backend
mvn spring-boot:run
# API      : http://localhost:8080
# H2 console : http://localhost:8080/h2-console
```

**Frontend** (dans un second terminal)
```bash
cd frontend
npm install
npm start
# Application : http://localhost:3000
```

> En développement, la base de données H2 in-memory est utilisée automatiquement. Aucune configuration requise.

---

## Déploiement en production

### 1. Préparer le serveur (Ubuntu 22.04+)
```bash
git clone <url-du-repo> frauddetect
cd frauddetect
cp .env.example .env
nano .env   # Remplir toutes les valeurs requises
```

### 2. Variables d'environnement (`.env`)

| Variable | Description | Exemple |
|----------|-------------|---------|
| `DB_USER` | Utilisateur PostgreSQL | `frauddetect` |
| `DB_PASSWORD` | Mot de passe PostgreSQL | *(chaîne forte)* |
| `JWT_SECRET` | Secret JWT — **minimum 64 caractères** | *(générer avec `openssl rand -hex 64`)* |
| `STRIPE_API_KEY` | Clé secrète Stripe (`sk_live_…`) | — |
| `STRIPE_WEBHOOK_SECRET` | Secret de signature webhook (`whsec_…`) | — |
| `STRIPE_PRICE_ID` | ID du prix Stripe pour l'abonnement Pro | `price_…` |
| `FRONTEND_URL` | URL publique de l'application | `https://votredomaine.com` |

### 3. Lancer la production
```bash
chmod +x deploy.sh
./deploy.sh prod
```

---

## Commandes de déploiement

```bash
./deploy.sh prod              # Premier démarrage (build + lancement)
./deploy.sh update            # Mise à jour sans downtime
./deploy.sh rollback          # Retour à l'état précédent
./deploy.sh status            # État des conteneurs + health check
./deploy.sh logs              # Logs de tous les services
./deploy.sh logs backend      # Logs du backend uniquement
./deploy.sh logs frontend     # Logs du frontend/Nginx
./deploy.sh backup            # Sauvegarde PostgreSQL → ./backups/
./deploy.sh stop              # Arrêt de tous les conteneurs
```

---

## Configuration SSL (HTTPS)

```bash
# Installer Certbot
apt install certbot

# Générer le certificat Let's Encrypt
certbot certonly --standalone -d votredomaine.com

# Copier les certificats dans le dossier attendu par Nginx
mkdir -p ssl
cp /etc/letsencrypt/live/votredomaine.com/fullchain.pem ssl/cert.pem
cp /etc/letsencrypt/live/votredomaine.com/privkey.pem   ssl/key.pem

# Redémarrer avec HTTPS
./deploy.sh update
```

---

## Configuration Stripe

1. **Créer le produit** : Dashboard Stripe → Produits → Créer
   - Nom : `FraudDetect Pro`
   - Prix récurrent : `49,00 € / mois`
   - Copier l'ID `price_xxx` → variable `STRIPE_PRICE_ID`

2. **Configurer le webhook** : Développeurs → Webhooks → Ajouter un endpoint
   - URL : `https://votredomaine.com/api/stripe/webhook`
   - Événements à écouter :
     - `checkout.session.completed`
     - `customer.subscription.deleted`
   - Copier la clé de signature `whsec_xxx` → variable `STRIPE_WEBHOOK_SECRET`

---

## API Reference

Toutes les routes protégées nécessitent le header : `Authorization: Bearer <token>`

### Authentification — `/api/auth`
| Méthode | Endpoint | Auth | Description |
|---------|----------|------|-------------|
| `POST` | `/api/auth/register` | Non | Inscription (email + mot de passe ≥ 8 chars) |
| `POST` | `/api/auth/login` | Non | Connexion → retourne un token JWT |
| `GET` | `/api/auth/me` | Oui | Profil de l'utilisateur connecté |

### Analyse — `/api`
| Méthode | Endpoint | Auth | Description |
|---------|----------|------|-------------|
| `POST` | `/api/analyze` | Oui | Analyser un PDF (`multipart/form-data`, champ `file`) |
| `GET` | `/api/history` | Oui | Historique des analyses de l'utilisateur |
| `GET` | `/api/health` | Non | Santé du service |

### Paiement — `/api/stripe`
| Méthode | Endpoint | Auth | Description |
|---------|----------|------|-------------|
| `POST` | `/api/stripe/checkout` | Oui | Créer une session Stripe Checkout |
| `POST` | `/api/stripe/portal` | Oui | Accéder au portail de facturation Stripe |
| `POST` | `/api/stripe/webhook` | Non* | Récepteur webhook Stripe (*signature vérifiée) |

### Exemple de réponse `/api/analyze`
```json
{
  "score": 82,
  "verdict": "AUTHENTIQUE",
  "color": "green",
  "isPro": false,
  "remainingDocuments": 7,
  "documentInfo": {
    "employeur": "ACME SAS",
    "siret": "12345678901234",
    "employe": "DUPONT Jean",
    "periode": "Janvier 2024",
    "salaireBrut": "3200,00 €",
    "salaireNet": "2496,00 €"
  },
  "checks": [
    {
      "category": "Métadonnées",
      "label": "Logiciel de création",
      "status": "OK",
      "detail": "Logiciel de paie reconnu : sage"
    }
  ]
}
```

---

## Développement

### Lancer les tests backend
```bash
cd backend
mvn test
```

### Build de production frontend
```bash
cd frontend
npm run build
```

### Accéder à la console H2 (dev)
URL : http://localhost:8080/h2-console
- JDBC URL : `jdbc:h2:mem:frauddetect`
- User : `sa` · Password : *(vide)*
