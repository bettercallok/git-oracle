import axios from 'axios';

// Connects to the Java API Gateway
const API_BASE_URL = import.meta.env.VITE_API_URL || 'http://localhost:8080/api/v1';

export const apiClient = axios.create({
  baseURL: API_BASE_URL,
  headers: {
    'Content-Type': 'application/json',
    'X-Tenant-ID': '00000000-0000-0000-0000-000000000000'
  },
});

// Request interceptor to inject API key if available
apiClient.interceptors.request.use((config) => {
  const apiKey = localStorage.getItem('gitoracle_api_key');
  if (apiKey) {
    config.headers['X-API-Key'] = apiKey;
  }
  return config;
});

// Add interceptors here if needed (e.g. auth tokens)
apiClient.interceptors.response.use(
  (response) => response,
  (error) => {
    console.error('API Error:', error.response?.data || error.message);
    if (error.response?.status === 401) {
      localStorage.removeItem('gitoracle_api_key');
      window.location.href = '/login';
    }
    return Promise.reject(error);
  }
);
