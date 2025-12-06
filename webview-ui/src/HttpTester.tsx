import { useState, useEffect, useCallback } from 'react';
import type { HttpResult, TraceEvent } from './bridge';

type Bridge = typeof window.PluginEnvVarBridge;

type Props = {
  bridge: Bridge;
};

const HttpTester = ({ bridge }: Props) => {
  const [url, setUrl] = useState('https://httpbin.org/get');
  const [method, setMethod] = useState('GET');
  const [enableTrace, setEnableTrace] = useState(true);
  const [isLoading, setIsLoading] = useState(false);
  const [result, setResult] = useState<HttpResult | null>(null);
  const [expandedSections, setExpandedSections] = useState<Set<string>>(
    new Set(['response', 'trace'])
  );

  // Subscribe to HTTP results
  useEffect(() => {
    if (!bridge) return;

    const unsubscribe = bridge.onHttpResult((httpResult) => {
      console.log('[HTTP Tester] Received result:', httpResult);
      setResult(httpResult);
      setIsLoading(false);
    });

    return unsubscribe;
  }, [bridge]);

  const handleCall = useCallback(() => {
    if (!bridge || !url.trim()) return;

    setIsLoading(true);
    setResult(null);

    console.log(`[HTTP Tester] Calling ${method} ${url} (trace: ${enableTrace})`);
    bridge.executeHttpRequest(url, method, {}, enableTrace);
  }, [bridge, url, method, enableTrace]);

  const toggleSection = (section: string) => {
    setExpandedSections((prev) => {
      const next = new Set(prev);
      if (next.has(section)) {
        next.delete(section);
      } else {
        next.add(section);
      }
      return next;
    });
  };

  const formatJson = (data: unknown): string => {
    if (data === undefined || data === null) return '';
    if (typeof data === 'string') {
      try {
        return JSON.stringify(JSON.parse(data), null, 2);
      } catch {
        return data;
      }
    }
    return JSON.stringify(data, null, 2);
  };

  const getPhaseColor = (phase: string): string => {
    switch (phase) {
      case 'init': return '#3574f0';
      case 'dns': return '#9c27b0';
      case 'socket': return '#ff9800';
      case 'tls': return '#4caf50';
      case 'proxy': return '#e91e63';
      case 'request': return '#00bcd4';
      case 'response': return '#8bc34a';
      case 'error': return '#f44336';
      default: return '#9e9e9e';
    }
  };

  const renderTraceEvent = (event: TraceEvent, index: number) => {
    const { timestamp, phase, event: eventName, ...details } = event;
    const hasDetails = Object.keys(details).length > 0;

    return (
      <div key={index} className="trace-event">
        <div className="trace-event-header">
          <span className="trace-timestamp">{timestamp}ms</span>
          <span
            className="trace-phase"
            style={{ backgroundColor: getPhaseColor(phase) }}
          >
            {phase}
          </span>
          <span className="trace-event-name">{eventName}</span>
        </div>
        {hasDetails && (
          <pre className="trace-details">{formatJson(details)}</pre>
        )}
      </div>
    );
  };

  return (
    <div className="http-tester">
      <div className="http-controls">
        <div className="http-input-row">
          <select
            className="http-method-select"
            value={method}
            onChange={(e) => setMethod(e.target.value)}
          >
            <option value="GET">GET</option>
            <option value="POST">POST</option>
            <option value="PUT">PUT</option>
            <option value="DELETE">DELETE</option>
            <option value="HEAD">HEAD</option>
            <option value="OPTIONS">OPTIONS</option>
          </select>
          <input
            type="text"
            className="http-url-input"
            placeholder="Enter URL (e.g., https://httpbin.org/get)"
            value={url}
            onChange={(e) => setUrl(e.target.value)}
            onKeyDown={(e) => e.key === 'Enter' && !isLoading && handleCall()}
          />
          <button
            type="button"
            onClick={handleCall}
            disabled={isLoading || !bridge}
            className="http-call-button"
          >
            {isLoading ? 'Calling...' : 'Call'}
          </button>
        </div>

        <div className="http-options-row">
          <label className="http-trace-toggle">
            <input
              type="checkbox"
              checked={enableTrace}
              onChange={(e) => setEnableTrace(e.target.checked)}
            />
            <span>Enable detailed tracing (DNS, TLS, proxy, connection)</span>
          </label>
        </div>
      </div>

      {!bridge && (
        <div className="http-warning">
          Bridge not available. Make sure the plugin is running correctly.
        </div>
      )}

      {result && (
        <div className="http-results">
          {/* Status Summary */}
          <div
            className={`http-status-banner ${result.success ? 'success' : 'error'}`}
          >
            <span className="http-status-indicator">
              {result.success ? '✓' : '✗'}
            </span>
            <span className="http-status-text">
              {result.success
                ? `${result.statusCode} ${result.statusMessage}`
                : result.error?.message || 'Request failed'}
            </span>
            {result.timing && (
              <span className="http-timing">{result.timing.total}ms</span>
            )}
          </div>

          {/* Response Headers Section */}
          {result.headers && (
            <div className="http-section">
              <div
                className="http-section-header"
                onClick={() => toggleSection('headers')}
              >
                <span className="http-section-toggle">
                  {expandedSections.has('headers') ? '▼' : '▶'}
                </span>
                <span>Response Headers</span>
              </div>
              {expandedSections.has('headers') && (
                <pre className="http-section-content">
                  {formatJson(result.headers)}
                </pre>
              )}
            </div>
          )}

          {/* Response Body Section */}
          {result.body && (
            <div className="http-section">
              <div
                className="http-section-header"
                onClick={() => toggleSection('response')}
              >
                <span className="http-section-toggle">
                  {expandedSections.has('response') ? '▼' : '▶'}
                </span>
                <span>Response Body ({result.body.length} bytes)</span>
              </div>
              {expandedSections.has('response') && (
                <pre className="http-section-content">{formatJson(result.body)}</pre>
              )}
            </div>
          )}

          {/* Trace Events Section */}
          {result.trace && result.trace.length > 0 && (
            <div className="http-section">
              <div
                className="http-section-header"
                onClick={() => toggleSection('trace')}
              >
                <span className="http-section-toggle">
                  {expandedSections.has('trace') ? '▼' : '▶'}
                </span>
                <span>Connection Trace ({result.trace.length} events)</span>
              </div>
              {expandedSections.has('trace') && (
                <div className="http-trace-container">
                  {result.trace.map((event, index) =>
                    renderTraceEvent(event, index)
                  )}
                </div>
              )}
            </div>
          )}
        </div>
      )}

      {!result && !isLoading && (
        <div className="http-placeholder">
          <p>Enter a URL and click "Call" to execute an HTTP request via Node.js.</p>
          <p className="http-hint">
            With tracing enabled, you'll see detailed information about:
          </p>
          <ul className="http-hint-list">
            <li>DNS resolution</li>
            <li>TCP connection</li>
            <li>TLS/SSL handshake and certificates</li>
            <li>Proxy negotiation (if configured via HTTP_PROXY/HTTPS_PROXY)</li>
            <li>Request/response timing</li>
          </ul>
        </div>
      )}
    </div>
  );
};

export default HttpTester;
