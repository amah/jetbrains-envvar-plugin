export type EnvVarTuple = {
  key: string;
  value: string;
  sensitive: boolean;
};

export type EnvVarsPayload = {
  jvm: EnvVarTuple[];
  node: EnvVarTuple[];
};

export type TraceEvent = {
  timestamp: number;
  phase: string;
  event: string;
  [key: string]: unknown;
};

export type HttpError = {
  message: string;
  code?: string;
};

export type HttpResult = {
  id?: string;
  success: boolean;
  statusCode?: number;
  statusMessage?: string;
  headers?: Record<string, unknown>;
  body?: string;
  error?: HttpError;
  trace: TraceEvent[];
  timing?: {
    total: number;
  };
};

export type NodeInfo = {
  nodePath: string;
  nodeVersion?: string | null;
  isRunning: boolean;
  error?: string | null;
};

declare global {
  interface Window {
    PluginEnvVarBridge?: {
      // Environment variables API
      requestEnvVars: () => void;
      onEnvVars: (callback: (payload: EnvVarsPayload) => void) => () => void;

      // HTTP Request API (executed via Node.js)
      executeHttpRequest: (
        url: string,
        method?: string,
        headers?: Record<string, string>,
        enableTrace?: boolean
      ) => void;
      onHttpResult: (callback: (result: HttpResult) => void) => () => void;

      // Node.js configuration API
      setNodePath: (nodePath: string) => void;
      getNodeInfo: () => void;
      onNodeInfo: (callback: (info: NodeInfo) => void) => () => void;

      // Internal dispatch methods
      __dispatchEnvVars?: (payload: EnvVarsPayload) => void;
      __dispatchHttpResult?: (result: HttpResult) => void;
      __dispatchNodeInfo?: (info: NodeInfo) => void;
    };
  }

  interface WindowEventMap {
    'plugin-env-var-bridge-ready': CustomEvent<void>;
  }
}

export {};
