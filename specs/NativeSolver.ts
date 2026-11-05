import type {TurboModule} from 'react-native';
import {TurboModuleRegistry} from 'react-native';

export interface Spec extends TurboModule {
  isServiceEnabled(): boolean;
  openAccessibilitySettings(): void;
  openAppInfo(): void;
  openGame(): boolean;
  getStatus(): string;
}

export default TurboModuleRegistry.getEnforcing<Spec>('NativeSolver');
