import { useEffect, useMemo, useState } from 'react';
import type { EnvVarTuple } from './bridge';
import HttpTester from './HttpTester';

type Bridge = typeof window.PluginEnvVarBridge;

type Props = {
  bridge: Bridge;
};

type Tab = 'env-vars' | 'http-tester';

const App = ({ bridge }: Props) => {
  const [activeTab, setActiveTab] = useState<Tab>('env-vars');
  const [allVars, setAllVars] = useState<EnvVarTuple[]>([]);
  const [filter, setFilter] = useState('');
  const [lastUpdated, setLastUpdated] = useState<string>('');
  const [copyState, setCopyState] = useState<string>('');

  useEffect(() => {
    if (!bridge) {
      return;
    }
    const unsubscribe = bridge.onEnvVars((payload) => {
      console.info('[EnvVar UI] Received payload', payload.length);
      setAllVars(payload);
      setLastUpdated(new Date().toLocaleTimeString());
    });
    bridge.requestEnvVars();
    return unsubscribe;
  }, [bridge]);

  const filtered = useMemo(() => {
    const needle = filter.trim().toLowerCase();
    if (!needle) {
      return allVars;
    }
    return allVars.filter((entry) =>
      entry.key.toLowerCase().includes(needle) ||
      entry.value.toLowerCase().includes(needle)
    );
  }, [allVars, filter]);

  const handleCopy = (entry: EnvVarTuple) => {
    navigator.clipboard
      ?.writeText(entry.value)
      .then(() => setCopyState(`Copied ${entry.key}`))
      .catch((err) => {
        console.warn('[EnvVar UI] Clipboard unavailable', err);
        setCopyState('Clipboard unavailable');
      });
  };

  useEffect(() => {
    if (!copyState) {
      return;
    }
    const timeout = window.setTimeout(() => setCopyState(''), 2000);
    return () => window.clearTimeout(timeout);
  }, [copyState]);

  return (
    <div className="app">
      <nav className="tab-nav">
        <button
          type="button"
          className={`tab-button ${activeTab === 'env-vars' ? 'active' : ''}`}
          onClick={() => setActiveTab('env-vars')}
        >
          Environment Variables
        </button>
        <button
          type="button"
          className={`tab-button ${activeTab === 'http-tester' ? 'active' : ''}`}
          onClick={() => setActiveTab('http-tester')}
        >
          HTTP Tester
        </button>
      </nav>

      {activeTab === 'env-vars' && (
        <div className="tab-content">
          <header className="header">
            <div className="actions">
              <input
                className="search"
                placeholder="Filter by key or value"
                value={filter}
                onChange={(event) => setFilter(event.target.value)}
              />
              <button type="button" onClick={() => bridge.requestEnvVars()}>
                Refresh
              </button>
            </div>
            <p className="meta">
              {lastUpdated ? `Last updated at ${lastUpdated}` : 'Waiting for data…'}
            </p>
            {copyState ? <p className="feedback">{copyState}</p> : null}
          </header>
          <table className="env-table">
            <thead>
              <tr>
                <th>Key</th>
                <th>Value</th>
                <th aria-label="Copy column" />
              </tr>
            </thead>
            <tbody>
              {filtered.map((entry) => (
                <tr key={entry.key}>
                  <td>{entry.key}</td>
                  <td className={entry.sensitive ? 'masked' : ''}>{entry.value}</td>
                  <td>
                    <button type="button" onClick={() => handleCopy(entry)}>
                      Copy
                    </button>
                  </td>
                </tr>
              ))}
              {!filtered.length ? (
                <tr>
                  <td colSpan={3} className="empty">
                    {filter
                      ? 'No matches for current filter.'
                      : 'No environment variables available.'}
                  </td>
                </tr>
              ) : null}
            </tbody>
          </table>
        </div>
      )}

      {activeTab === 'http-tester' && (
        <div className="tab-content">
          <HttpTester bridge={bridge} />
        </div>
      )}
    </div>
  );
};

export default App;
