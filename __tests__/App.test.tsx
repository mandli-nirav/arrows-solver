/**
 * @format
 */

import React from 'react';
import ReactTestRenderer from 'react-test-renderer';
import App from '../App';

// SafeAreaProvider renders nothing until native insets arrive; the library's mock supplies them.
jest.mock('react-native-safe-area-context', () => require('react-native-safe-area-context/jest/mock').default);

// The native solver module only exists on a device; stub it for Jest.
jest.mock('../specs/NativeSolver', () => ({
  __esModule: true,
  default: {
    isServiceEnabled: () => false,
    openAccessibilitySettings: () => {},
    openAppInfo: () => {},
    openGame: () => true,
    getStatus: () => 'test',
    getThemeMode: () => 'system',
    setThemeMode: () => {},
  },
}));

test('renders the status from the solver', async () => {
  let r: ReactTestRenderer.ReactTestRenderer | undefined;
  await ReactTestRenderer.act(() => {
    r = ReactTestRenderer.create(<App />);
  });
  expect(r!.root.findByProps({accessibilityLabel: 'Status. test'})).toBeTruthy();
  // unmount: App polls the service every 1.5 s, which would outlive the test
  await ReactTestRenderer.act(() => r!.unmount());
});
