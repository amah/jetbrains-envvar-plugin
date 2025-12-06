import { useEffect, useMemo, useState } from 'react';
import type { EnvVarTuple, EnvVarsPayload, NodeInfo } from './bridge';
import HttpTester from './HttpTester';

type Bridge = typeof window.PluginEnvVarBridge;

type Props = {
  bridge: Bridge;
};

type Tab = 'env-vars' | 'http-tester';
type EnvSource = 'node' | 'jvm';

const App = ({ bridge }: Props) => {
  const [activeTab, setActiveTab] = useState<Tab>('env-vars');
  const [envSource, setEnvSource] = useState<EnvSource>('node');
  const [jvmVars, setJvmVars] = useState<EnvVarTuple[]>([]);
  const [nodeVars, setNodeVars] = useState<EnvVarTuple[]>([]);
  const [filter, setFilter] = useState('');
  const [lastUpdated, setLastUpdated] = useState<string>('');
  const [copyState, setCopyState] = useState<string>('');
  const [nodeInfo, setNodeInfo] = useState<NodeInfo | null>(null);
  const [nodePath, setNodePath] = useState('');
  const [isSettingNodePath, setIsSettingNodePath] = useState(false);

  // Subscribe to env vars
  useEffect(() => {
    if (!bridge) return;

    const unsubscribe = bridge.onEnvVars((payload: EnvVarsPayload) => {
      console.info('[EnvVar UI] Received payload', {
        jvm: payload.jvm?.length,
        node: payload.node?.length,
      });
      setJvmVars(payload.jvm || []);
      setNodeVars(payload.node || []);
      setLastUpdated(new Date().toLocaleTimeString());
    });
    bridge.requestEnvVars();
    return unsubscribe;
  }, [bridge]);

  // Subscribe to node info
  useEffect(() => {
    if (!bridge) return;

    const unsubscribe = bridge.onNodeInfo((info: NodeInfo) => {
      console.info('[EnvVar UI] Node info:', info);
      setNodeInfo(info);
      setIsSettingNodePath(false);
      if (!nodePath && info.nodePath) {
        setNodePath(info.nodePath);
      }
    });
    bridge.getNodeInfo();
    return unsubscribe;
  }, [bridge]);

  const allVars = envSource === 'node' ? nodeVars : jvmVars;

  const filtered = useMemo(() => {
    const needle = filter.trim().toLowerCase();
    if (!needle) {
      return allVars;
    }
    return allVars.filter(
      (entry) =>
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
    if (!copyState) return;
    const timeout = window.setTimeout(() => setCopyState(''), 2000);
    return () => window.clearTimeout(timeout);
  }, [copyState]);

  const handleSetNodePath = () => {
    if (!bridge) return;
    setIsSettingNodePath(true);
    bridge.setNodePath(nodePath);
  };

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
          {/* Node.js Configuration Panel */}
          <div className="node-config-panel">
            <div className="node-config-row">
              <label className="node-config-label">Node.js Path:</label>
              <input
                type="text"
                className="node-path-input"
                placeholder="e.g., /usr/local/bin/node"
                value={nodePath}
                onChange={(e) => setNodePath(e.target.value)}
                onKeyDown={(e) => e.key === 'Enter' && handleSetNodePath()}
              />
              <button
                type="button"
                onClick={handleSetNodePath}
                disabled={isSettingNodePath}
                className="node-path-button"
              >
                {isSettingNodePath ? 'Applying...' : 'Apply'}
              </button>
            </div>
            {nodeInfo && (
              <div className="node-status">
                <span
                  className={`node-status-indicator ${nodeInfo.isRunning ? 'running' : 'stopped'}`}
                >
                  {nodeInfo.isRunning ? '●' : '○'}
                </span>
                <span className="node-status-text">
                  {nodeInfo.isRunning
                    ? `Node.js ${nodeInfo.nodeVersion || ''} running`
                    : nodeInfo.error || 'Node.js not running'}
                </span>
              </div>
            )}
          </div>

          {/* Environment Source Toggle */}
          <div className="env-source-toggle">
            <button
              type="button"
              className={`env-source-button ${envSource === 'node' ? 'active' : ''}`}
              onClick={() => setEnvSource('node')}
            >
              Node.js ({nodeVars.length})
            </button>
            <button
              type="button"
              className={`env-source-button ${envSource === 'jvm' ? 'active' : ''}`}
              onClick={() => setEnvSource('jvm')}
            >
              JVM/Plugin ({jvmVars.length})
            </button>
          </div>

          <header className="header">
            <div className="actions">
              <input
                className="search"
                placeholder="Filter by key or value"
                value={filter}
                onChange={(event) => setFilter(event.target.value)}
              />
              <button type="button" onClick={() => bridge?.requestEnvVars()}>
                Refresh
              </button>
            </div>
            <p className="meta">
              {lastUpdated
                ? `Last updated at ${lastUpdated}`
                : 'Waiting for data…'}
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
                  <td className={entry.sensitive ? 'masked' : ''}>
                    {entry.value}
                  </td>
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
