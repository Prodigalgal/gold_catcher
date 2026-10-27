import React from 'react';
import { createRoot } from 'react-dom/client';
import App from './App';
import OAuthConsentPage from './OAuthConsentPage';
import './styles.css';
import './live.css';

createRoot(document.getElementById('gold-catcher-app')!).render(<React.StrictMode>{window.location.pathname === '/oauth/consent' ? <OAuthConsentPage /> : <App />}</React.StrictMode>);
