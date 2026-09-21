import axios from 'axios';

const TOKEN_KEY = 'fd_token';
const USER_KEY = 'fd_user';

export const api = axios.create({ baseURL: '' });

api.interceptors.request.use(config => {
  const token = getToken();
  if (token) config.headers.Authorization = `Bearer ${token}`;
  return config;
});

/**
 * Le jeton expire au bout de vingt-quatre heures. Sans ce traitement, chaque
 * appel echouait en affichant la reponse brute du serveur — "Forbidden" — alors
 * que l'interface continuait de montrer l'utilisateur connecte, lu dans le
 * stockage local. La session est donc effacee et la page de connexion
 * presentee, avec la raison.
 *
 * Le 403 d'une adresse email non confirmee n'est pas concerne : il vient d'un
 * utilisateur bien authentifie, et l'application le traite deja.
 */
api.interceptors.response.use(
  response => response,
  error => {
    const url = error.config?.url || '';
    const surLaConnexion = url.includes('/api/auth/login')
      || url.includes('/api/auth/register');

    if (error.response?.status === 401 && !surLaConnexion) {
      logout();
      // Rechargement plutot qu'un changement d'etat : l'intercepteur ne connait
      // pas l'arbre React, et la racine affiche la connexion faute de session.
      window.location.replace('/?session=expiree');
    }
    return Promise.reject(error);
  }
);

export function getToken() {
  return localStorage.getItem(TOKEN_KEY);
}

export function getUser() {
  const raw = localStorage.getItem(USER_KEY);
  try { return raw ? JSON.parse(raw) : null; } catch { return null; }
}

export function saveAuth(token, user) {
  localStorage.setItem(TOKEN_KEY, token);
  localStorage.setItem(USER_KEY, JSON.stringify(user));
}

export function logout() {
  localStorage.removeItem(TOKEN_KEY);
  localStorage.removeItem(USER_KEY);
}

export async function register(email, password) {
  const res = await api.post('/api/auth/register', { email, password });
  saveAuth(res.data.token, res.data.user);
  return res.data.user;
}

export async function login(email, password) {
  const res = await api.post('/api/auth/login', { email, password });
  saveAuth(res.data.token, res.data.user);
  return res.data.user;
}

export async function resendVerification() {
  const res = await api.post('/api/auth/resend-verification');
  return res.data;
}

export async function refreshMe() {
  const res = await api.get('/api/auth/me');
  localStorage.setItem(USER_KEY, JSON.stringify(res.data));
  return res.data;
}
