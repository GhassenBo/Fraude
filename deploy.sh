#!/bin/bash
set -e

# ── FraudDetect Deploy Script ──
# Usage: ./deploy.sh [prod|update|rollback|logs|status]

COMPOSE="docker-compose"
APP_DIR="$(cd "$(dirname "$0")" && pwd)"
BACKUP_DIR="$APP_DIR/backups"

GREEN='\033[0;32m'
YELLOW='\033[1;33m'
RED='\033[0;31m'
NC='\033[0m'

log()    { echo -e "${GREEN}[✓]${NC} $1"; }
warn()   { echo -e "${YELLOW}[!]${NC} $1"; }
error()  { echo -e "${RED}[✗]${NC} $1"; exit 1; }

check_env() {
  if [ ! -f "$APP_DIR/.env" ]; then
    error ".env manquant. Copie .env.example → .env et remplis les valeurs."
  fi
  source "$APP_DIR/.env"
  [ -z "$DB_PASSWORD" ]           && error "DB_PASSWORD manquant dans .env"
  [ -z "$JWT_SECRET" ]            && error "JWT_SECRET manquant dans .env"
  [ -z "$STRIPE_API_KEY" ]        && error "STRIPE_API_KEY manquant dans .env"
  [ -z "$STRIPE_WEBHOOK_SECRET" ] && error "STRIPE_WEBHOOK_SECRET manquant dans .env"
  [ -z "$STRIPE_PRICE_ID" ]       && error "STRIPE_PRICE_ID manquant dans .env"
  log "Variables d'environnement OK"
}

backup_db() {
  mkdir -p "$BACKUP_DIR"
  TIMESTAMP=$(date +%Y%m%d_%H%M%S)
  BACKUP_FILE="$BACKUP_DIR/db_backup_$TIMESTAMP.sql"
  warn "Sauvegarde base de données → $BACKUP_FILE"
  $COMPOSE exec -T db pg_dump -U frauddetect frauddetect > "$BACKUP_FILE" 2>/dev/null || warn "Backup DB ignoré (DB pas encore lancée)"
  log "Backup terminé"
}

case "${1:-prod}" in

  # ── Premier déploiement ──
  prod)
    log "Démarrage déploiement FraudDetect..."
    check_env

    # Install Docker if missing
    if ! command -v docker &> /dev/null; then
      warn "Docker non installé. Installation..."
      curl -fsSL https://get.docker.com | sh
      usermod -aG docker $USER
      log "Docker installé"
    fi

    if ! command -v docker-compose &> /dev/null; then
      warn "Docker Compose non installé. Installation..."
      apt-get install -y docker-compose-plugin 2>/dev/null || \
      curl -SL "https://github.com/docker/compose/releases/download/v2.24.0/docker-compose-$(uname -s)-$(uname -m)" \
        -o /usr/local/bin/docker-compose && chmod +x /usr/local/bin/docker-compose
      log "Docker Compose installé"
    fi

    # SSL directory
    mkdir -p "$APP_DIR/ssl"
    if [ ! -f "$APP_DIR/ssl/cert.pem" ]; then
      warn "Certificats SSL absents dans ./ssl/ — HTTPS non configuré"
      warn "Pour Let's Encrypt: certbot certonly --standalone -d votredomaine.com"
    fi

    log "Build et démarrage des conteneurs..."
    cd "$APP_DIR"
    $COMPOSE pull db
    $COMPOSE up -d --build

    log "Attente du démarrage..."
    sleep 10

    # Health check
    if curl -sf http://localhost/api/health > /dev/null 2>&1; then
      log "Application démarrée avec succès !"
      log "Accessible sur : http://localhost"
    else
      warn "Health check échoué. Vérifier les logs : ./deploy.sh logs"
    fi
    ;;

  # ── Mise à jour sans downtime ──
  update)
    log "Mise à jour FraudDetect..."
    check_env
    backup_db

    cd "$APP_DIR"
    log "Pull dernières images..."
    git pull origin main 2>/dev/null || warn "Git pull ignoré (pas de repo git)"

    log "Rebuild et redémarrage..."
    $COMPOSE up -d --build --no-deps backend
    sleep 5
    $COMPOSE up -d --build --no-deps frontend

    log "Mise à jour terminée ✓"
    ;;

  # ── Rollback ──
  rollback)
    warn "Rollback — arrêt et redémarrage sans rebuild..."
    cd "$APP_DIR"
    $COMPOSE down
    $COMPOSE up -d
    log "Rollback terminé"
    ;;

  # ── Logs ──
  logs)
    SERVICE="${2:-}"
    cd "$APP_DIR"
    if [ -z "$SERVICE" ]; then
      $COMPOSE logs -f --tail=100
    else
      $COMPOSE logs -f --tail=100 "$SERVICE"
    fi
    ;;

  # ── Status ──
  status)
    cd "$APP_DIR"
    echo ""
    echo "═══════════════════════════════"
    echo "   FraudDetect — Status"
    echo "═══════════════════════════════"
    $COMPOSE ps
    echo ""
    echo "Health check API :"
    curl -sf http://localhost/api/health && echo "" || echo -e "${RED}API non joignable${NC}"
    ;;

  # ── Stop ──
  stop)
    warn "Arrêt de FraudDetect..."
    cd "$APP_DIR"
    $COMPOSE down
    log "Arrêt terminé"
    ;;

  # ── Backup DB ──
  backup)
    check_env
    cd "$APP_DIR"
    backup_db
    log "Backup disponible dans : $BACKUP_DIR"
    ;;

  *)
    echo "Usage: ./deploy.sh [prod|update|rollback|logs [service]|status|stop|backup]"
    ;;
esac
