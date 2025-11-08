import { useEffect, useMemo, useState } from 'react';
import type { EnvVarTuple } from './bridge';

type Bridge = typeof window.PluginEnvVarBridge;

type Props = {
  bridge: Bridge;
};

const App = ({ bridge }: Props) => {
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
      <header className="header">
        <h1>Environment Variables</h1>
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
  );
};

export default App;
