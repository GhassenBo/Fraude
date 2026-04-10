#!/bin/bash
echo "🚀 Démarrage FraudDetect..."

# Start backend
echo "📦 Démarrage du backend Spring Boot..."
cd backend && mvn spring-boot:run &
BACKEND_PID=$!

# Wait for backend
echo "⏳ Attente du backend..."
until curl -s http://localhost:8080/api/health > /dev/null 2>&1; do
  sleep 2
done
echo "✅ Backend prêt sur http://localhost:8080"

# Start frontend
echo "🎨 Démarrage du frontend React..."
cd ../frontend && npm install --silent && npm start &
FRONTEND_PID=$!

echo ""
echo "✅ FraudDetect est prêt !"
echo "   Frontend : http://localhost:3000"
echo "   Backend  : http://localhost:8080"
echo ""
echo "Ctrl+C pour arrêter"

# Wait
trap "kill $BACKEND_PID $FRONTEND_PID; exit" SIGINT SIGTERM
wait
