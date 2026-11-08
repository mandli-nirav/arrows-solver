import type {TurboModule} from 'react-native';
import {TurboModuleRegistry} from 'react-native';

export interface Spec extends TurboModule {
  isServiceEnabled(): boolean;
  openAccessibilitySettings(): void;
  openAppInfo(): void;
  openGame(): boolean;
  getStatus(): string;
  /** Appearance: 'system', 'light' or 'dark'. The solver's drawer follows it too. */
  getThemeMode(): string;
  setThemeMode(mode: string): void;
}

export default TurboModuleRegistry.getEnforcing<Spec>('NativeSolver');
