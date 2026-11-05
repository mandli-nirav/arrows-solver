/**
 * @format
 */

import React from 'react';
import ReactTestRenderer from 'react-test-renderer';
import App from '../App';

// The native solver module only exists on a device; stub it for Jest.
jest.mock('../specs/NativeSolver', () => ({
  __esModule: true,
  default: {
    isServiceEnabled: () => false,
    openAccessibilitySettings: () => {},
    openAppInfo: () => {},
    openGame: () => true,
    getStatus: () => 'test',
  },
}));

test('renders correctly', async () => {
  await ReactTestRenderer.act(() => {
    ReactTestRenderer.create(<App />);
  });
});
