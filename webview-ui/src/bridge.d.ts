export type EnvVarTuple = {
  key: string;
  value: string;
  sensitive: boolean;
};

declare global {
  interface Window {
    PluginEnvVarBridge?: {
      requestEnvVars: () => void;
      onEnvVars: (
        callback: (payload: EnvVarTuple[]) => void
      ) => () => void;
      __dispatch?: (payload: EnvVarTuple[]) => void;
    };
  }

  interface WindowEventMap {
    'plugin-env-var-bridge-ready': CustomEvent<void>;
  }
}

export {};
