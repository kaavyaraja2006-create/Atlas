import '@testing-library/jest-dom/vitest';
import { cleanup } from '@testing-library/react';
import { afterEach } from 'vitest';

afterEach(() => cleanup());

// jsdom lacks <dialog> methods.
if (typeof HTMLDialogElement !== 'undefined') {
  HTMLDialogElement.prototype.showModal ??= function (this: HTMLDialogElement) { this.open = true; };
  HTMLDialogElement.prototype.close ??= function (this: HTMLDialogElement) { this.open = false; };
}

// jsdom lacks ResizeObserver and DOMMatrixReadOnly, which React Flow uses for measuring and zoom.
class NoopResizeObserver {
  observe() {}
  unobserve() {}
  disconnect() {}
}
globalThis.ResizeObserver ??= NoopResizeObserver as unknown as typeof ResizeObserver;
if (typeof globalThis.DOMMatrixReadOnly === 'undefined') {
  class Matrix {
    m22: number;
    constructor(t?: string) {
      const scale = t?.match(/scale\(([\d.]+)\)/);
      this.m22 = scale ? Number(scale[1]) : 1;
    }
  }
  (globalThis as unknown as { DOMMatrixReadOnly: unknown }).DOMMatrixReadOnly = Matrix;
}
