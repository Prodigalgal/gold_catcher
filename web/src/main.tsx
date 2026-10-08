import React from 'react';
import { createRoot } from 'react-dom/client';
import App from './App';
import './styles.css';
import './live.css';

createRoot(document.getElementById('gold-catcher-app')!).render(<React.StrictMode><App /></React.StrictMode>);
