import axios from 'axios';

const TOKEN_KEY = 'fd_token';
const USER_KEY = 'fd_user';

export const api = axios.create({ baseURL: '' });

api.interceptors.request.use(config => {
  const token = getToken();
  if (token) config.headers.Authorization = `Bearer ${token}`;
  return config;
});

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

export async function refreshMe() {
  const res = await api.get('/api/auth/me');
  localStorage.setItem(USER_KEY, JSON.stringify(res.data));
  return res.data;
}
