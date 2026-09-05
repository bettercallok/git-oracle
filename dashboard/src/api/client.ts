import axios from 'axios';
import { getApiKey, clearApiKey } from '../auth/apiKeyStore';

// Connects to the Java API Gateway
const API_BASE_URL = import.meta.env.VITE_API_URL || 'http://localhost:8080/api/v1';

export const apiClient = axios.create({
  baseURL: API_BASE_URL,
  // No X-Tenant-ID. The tenant is derived server-side from the API key at the
  // gateway, and any client-supplied value is stripped before routing — sending
  // one here would have no effect beyond suggesting to a reader that the
  // browser gets to choose which tenant's data it sees.
  headers: {
    'Content-Type': 'application/json',
  },
});

// Request interceptor to inject the API key if one is held.
// Read from the in-memory store, never from localStorage — see
// src/auth/apiKeyStore.ts for why the key is no longer persisted.
apiClient.interceptors.request.use((config) => {
  const apiKey = getApiKey();
  if (apiKey) {
    config.headers['X-API-Key'] = apiKey;
  }
  return config;
});

apiClient.interceptors.response.use(
  (response) => response,
  (error) => {
    console.error('API Error:', error.response?.data || error.message);
    if (error.response?.status === 401) {
      clearApiKey();
      window.location.href = '/login';
    }
    return Promise.reject(error);
  }
);
