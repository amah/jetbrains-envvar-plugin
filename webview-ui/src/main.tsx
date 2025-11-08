import React from 'react';
import ReactDOM from 'react-dom/client';
import App from './App';
import './index.css';

const mountPoint = document.getElementById('root');

if (!mountPoint) {
  throw new Error('Missing #root element; check index.html packaging');
}

const root = ReactDOM.createRoot(mountPoint);

const renderWithBridge = () => {
  const bridge = window.PluginEnvVarBridge;
  if (!bridge) {
    console.warn('[EnvVar UI] PluginEnvVarBridge not yet available');
    return false;
  }
  console.info('[EnvVar UI] Bridge detected, mounting React tree');
  root.render(
    <React.StrictMode>
      <App bridge={bridge} />
    </React.StrictMode>
  );
  return true;
};

if (!renderWithBridge()) {
  window.addEventListener(
    'plugin-env-var-bridge-ready',
    () => {
      console.info('[EnvVar UI] Received bridge-ready event');
      renderWithBridge();
    },
    { once: true }
  );
}
