#!/usr/bin/env node
/**
 * Node.js Agent for JetBrains Environment Variable Plugin
 *
 * This agent runs as a child process and communicates with the Kotlin plugin
 * via JSON messages over stdin/stdout.
 *
 * Commands:
 * - { "type": "getEnvVars" } -> Returns all environment variables
 * - { "type": "httpRequest", "id": string, "url": string, "method": string, "enableTrace": boolean } -> Execute HTTP request
 * - { "type": "shutdown" } -> Gracefully shutdown the agent
 */

const http = require('http');
const https = require('https');
const tls = require('tls');
const net = require('net');
const { URL } = require('url');
const readline = require('readline');

// Collect trace events for a request
class RequestTracer {
  constructor(requestId, enableTrace) {
    this.requestId = requestId;
    this.enableTrace = enableTrace;
    this.events = [];
    this.startTime = Date.now();
  }

  addEvent(phase, event, details = {}) {
    if (!this.enableTrace && phase !== 'error' && phase !== 'response') return;

    this.events.push({
      timestamp: Date.now() - this.startTime,
      phase,
      event,
      ...details
    });
  }

  getTrace() {
    return this.events;
  }
}

// Send a message to the Kotlin plugin
function send(message) {
  const json = JSON.stringify(message);
  process.stdout.write(json + '\n');
}

// Log to stderr (for debugging, won't interfere with protocol)
function log(message) {
  process.stderr.write(`[node-agent] ${message}\n`);
}

// Get all environment variables
function handleGetEnvVars() {
  const envVars = Object.entries(process.env)
    .map(([key, value]) => ({ key, value: value || '' }))
    .sort((a, b) => a.key.localeCompare(b.key));

  send({
    type: 'envVarsResult',
    data: envVars
  });
}

// Execute HTTP request with optional tracing
async function handleHttpRequest(command) {
  const { id, url, method = 'GET', headers = {}, enableTrace = false } = command;
  const tracer = new RequestTracer(id, enableTrace);

  try {
    const parsedUrl = new URL(url);
    const isHttps = parsedUrl.protocol === 'https:';
    const httpModule = isHttps ? https : http;

    tracer.addEvent('init', 'request_start', {
      url,
      method,
      protocol: parsedUrl.protocol,
      host: parsedUrl.hostname,
      port: parsedUrl.port || (isHttps ? 443 : 80)
    });

    // Check for proxy configuration
    const proxyEnvVar = isHttps ? 'HTTPS_PROXY' : 'HTTP_PROXY';
    const proxyUrl = process.env[proxyEnvVar] || process.env[proxyEnvVar.toLowerCase()];
    const noProxy = process.env.NO_PROXY || process.env.no_proxy || '';

    if (proxyUrl) {
      tracer.addEvent('proxy', 'proxy_detected', {
        proxyEnvVar,
        proxyUrl: proxyUrl.replace(/\/\/([^:]+):([^@]+)@/, '//$1:***@'), // mask password
        noProxy
      });
    }

    const result = await executeRequest(httpModule, parsedUrl, method, headers, tracer, isHttps);

    send({
      type: 'httpResult',
      id,
      success: true,
      statusCode: result.statusCode,
      statusMessage: result.statusMessage,
      headers: result.headers,
      body: result.body,
      trace: tracer.getTrace(),
      timing: {
        total: Date.now() - tracer.startTime
      }
    });

  } catch (error) {
    tracer.addEvent('error', 'request_failed', {
      message: error.message,
      code: error.code,
      stack: error.stack
    });

    send({
      type: 'httpResult',
      id,
      success: false,
      error: {
        message: error.message,
        code: error.code
      },
      trace: tracer.getTrace(),
      timing: {
        total: Date.now() - tracer.startTime
      }
    });
  }
}

function executeRequest(httpModule, parsedUrl, method, headers, tracer, isHttps) {
  return new Promise((resolve, reject) => {
    const options = {
      hostname: parsedUrl.hostname,
      port: parsedUrl.port || (isHttps ? 443 : 80),
      path: parsedUrl.pathname + parsedUrl.search,
      method,
      headers: {
        'User-Agent': 'JetBrains-EnvVar-Plugin-NodeAgent/1.0',
        ...headers
      }
    };

    tracer.addEvent('dns', 'lookup_start', { hostname: options.hostname });

    const req = httpModule.request(options, (res) => {
      tracer.addEvent('response', 'headers_received', {
        statusCode: res.statusCode,
        statusMessage: res.statusMessage,
        headers: res.headers,
        httpVersion: res.httpVersion
      });

      const chunks = [];
      res.on('data', (chunk) => {
        chunks.push(chunk);
        tracer.addEvent('response', 'data_chunk', { size: chunk.length });
      });

      res.on('end', () => {
        const body = Buffer.concat(chunks).toString('utf8');
        tracer.addEvent('response', 'complete', { bodySize: body.length });
        resolve({
          statusCode: res.statusCode,
          statusMessage: res.statusMessage,
          headers: res.headers,
          body
        });
      });
    });

    // Socket events for connection tracing
    req.on('socket', (socket) => {
      tracer.addEvent('socket', 'assigned', {});

      if (socket.connecting) {
        socket.on('lookup', (err, address, family, host) => {
          if (err) {
            tracer.addEvent('dns', 'lookup_error', { error: err.message });
          } else {
            tracer.addEvent('dns', 'lookup_complete', { address, family, host });
          }
        });

        socket.on('connect', () => {
          tracer.addEvent('socket', 'connected', {
            localAddress: socket.localAddress,
            localPort: socket.localPort,
            remoteAddress: socket.remoteAddress,
            remotePort: socket.remotePort
          });
        });
      } else {
        tracer.addEvent('socket', 'reused', {
          localAddress: socket.localAddress,
          remoteAddress: socket.remoteAddress
        });
      }

      // TLS-specific events (for HTTPS)
      if (isHttps && socket instanceof tls.TLSSocket) {
        traceTlsSocket(socket, tracer);
      } else if (isHttps) {
        // Socket might be wrapped later
        socket.on('secureConnect', () => {
          traceTlsSocket(socket, tracer);
        });
      }
    });

    req.on('error', (error) => {
      tracer.addEvent('error', 'request_error', {
        message: error.message,
        code: error.code
      });
      reject(error);
    });

    req.on('timeout', () => {
      tracer.addEvent('error', 'timeout', {});
      req.destroy(new Error('Request timeout'));
    });

    tracer.addEvent('request', 'sending', { method, path: options.path });
    req.end();
  });
}

function traceTlsSocket(socket, tracer) {
  try {
    const cert = socket.getPeerCertificate(true);
    const protocol = socket.getProtocol();
    const cipher = socket.getCipher();
    const authorized = socket.authorized;
    const authorizationError = socket.authorizationError;

    tracer.addEvent('tls', 'handshake_complete', {
      protocol,
      cipher: cipher ? {
        name: cipher.name,
        standardName: cipher.standardName,
        version: cipher.version
      } : null,
      authorized,
      authorizationError: authorizationError || null
    });

    if (cert && Object.keys(cert).length > 0) {
      tracer.addEvent('tls', 'certificate', {
        subject: cert.subject,
        issuer: cert.issuer,
        validFrom: cert.valid_from,
        validTo: cert.valid_to,
        serialNumber: cert.serialNumber,
        fingerprint: cert.fingerprint,
        fingerprint256: cert.fingerprint256
      });

      // Trace certificate chain
      if (cert.issuerCertificate && cert.issuerCertificate !== cert) {
        const chain = [];
        let currentCert = cert.issuerCertificate;
        let depth = 0;
        const seen = new Set();

        while (currentCert && depth < 10) {
          const fp = currentCert.fingerprint;
          if (seen.has(fp)) break;
          seen.add(fp);

          chain.push({
            depth: depth++,
            subject: currentCert.subject,
            issuer: currentCert.issuer,
            validFrom: currentCert.valid_from,
            validTo: currentCert.valid_to
          });

          if (currentCert.issuerCertificate === currentCert) break;
          currentCert = currentCert.issuerCertificate;
        }

        if (chain.length > 0) {
          tracer.addEvent('tls', 'certificate_chain', { chain });
        }
      }
    }
  } catch (e) {
    tracer.addEvent('tls', 'trace_error', { message: e.message });
  }
}

// Handle shutdown
function handleShutdown() {
  send({ type: 'shutdownAck' });
  log('Shutting down gracefully');
  process.exit(0);
}

// Process incoming commands
function processCommand(line) {
  try {
    const command = JSON.parse(line);

    switch (command.type) {
      case 'getEnvVars':
        handleGetEnvVars();
        break;
      case 'httpRequest':
        handleHttpRequest(command);
        break;
      case 'shutdown':
        handleShutdown();
        break;
      case 'ping':
        send({ type: 'pong' });
        break;
      default:
        send({ type: 'error', message: `Unknown command type: ${command.type}` });
    }
  } catch (error) {
    send({ type: 'error', message: `Failed to parse command: ${error.message}` });
  }
}

// Main entry point
function main() {
  log('Node.js agent starting...');
  log(`Node version: ${process.version}`);
  log(`Platform: ${process.platform}`);
  log(`PID: ${process.pid}`);

  // Signal that we're ready
  send({ type: 'ready', nodeVersion: process.version, pid: process.pid });

  // Read commands from stdin
  const rl = readline.createInterface({
    input: process.stdin,
    output: process.stdout,
    terminal: false
  });

  rl.on('line', processCommand);

  rl.on('close', () => {
    log('stdin closed, shutting down');
    process.exit(0);
  });

  // Handle process signals
  process.on('SIGTERM', () => {
    log('Received SIGTERM');
    process.exit(0);
  });

  process.on('SIGINT', () => {
    log('Received SIGINT');
    process.exit(0);
  });

  process.on('uncaughtException', (error) => {
    log(`Uncaught exception: ${error.message}`);
    send({ type: 'error', message: `Uncaught exception: ${error.message}` });
  });
}

main();
