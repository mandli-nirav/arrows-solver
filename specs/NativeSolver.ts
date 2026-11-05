import type {TurboModule} from 'react-native';
import {TurboModuleRegistry} from 'react-native';

export interface Spec extends TurboModule {
  isServiceEnabled(): boolean;
  openAccessibilitySettings(): void;
  openAppInfo(): void;
  openGame(): boolean;
  getStatus(): string;
  /** Totals over every level the bubble solved: levels, arrows cleared, solving time in ms. */
  getStats(): {levels: number; arrows: number; ms: number};
}

export default TurboModuleRegistry.getEnforcing<Spec>('NativeSolver');
