import { describe, expect, it, vi } from 'vitest';
import { render, screen } from '@testing-library/react';
import App from '../App';
import type { EnvVarTuple } from '../bridge';

const sample: EnvVarTuple[] = [
  { key: 'HOME', value: '/home/user', sensitive: false },
  { key: 'API_TOKEN', value: '••••', sensitive: true }
];

describe('App', () => {
  it('renders provided environment variables', () => {
    const requestSpy = vi.fn();
    const bridge = {
      requestEnvVars: requestSpy,
      onEnvVars: (callback: (payload: EnvVarTuple[]) => void) => {
        callback(sample);
        return vi.fn();
      }
    } as unknown as typeof window.PluginEnvVarBridge;

    render(<App bridge={bridge} />);

    expect(requestSpy).toHaveBeenCalled();
    expect(screen.getByText('HOME')).toBeInTheDocument();
    expect(screen.getByText('API_TOKEN')).toBeInTheDocument();
  });
});
