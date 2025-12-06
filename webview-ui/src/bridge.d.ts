export type EnvVarTuple = {
  key: string;
  value: string;
  sensitive: boolean;
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

declare global {
  interface Window {
    PluginEnvVarBridge?: {
      // Environment variables API
      requestEnvVars: () => void;
      onEnvVars: (callback: (payload: EnvVarTuple[]) => void) => () => void;

      // HTTP Request API (executed via Node.js)
      executeHttpRequest: (
        url: string,
        method?: string,
        headers?: Record<string, string>,
        enableTrace?: boolean
      ) => void;
      onHttpResult: (callback: (result: HttpResult) => void) => () => void;

      // Internal dispatch methods
      __dispatchEnvVars?: (payload: EnvVarTuple[]) => void;
      __dispatchHttpResult?: (result: HttpResult) => void;
    };
  }

  interface WindowEventMap {
    'plugin-env-var-bridge-ready': CustomEvent<void>;
  }
}

export {};
