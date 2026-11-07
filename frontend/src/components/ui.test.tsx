import { render, screen } from '@testing-library/react';
import { describe, expect, it } from 'vitest';
import { Modal, Table } from './ui';

describe('Modal', () => {
  it('is named by its title, even when the title has spaces and punctuation', () => {
    render(
      <Modal open onClose={() => {}} title="Execute plan 2?">
        body
      </Modal>,
    );
    // Found by axe in Phase 9: an id built from the title pointed at nothing.
    expect(screen.getByRole('dialog', { name: 'Execute plan 2?' })).toBeTruthy();
  });

  it('gives two dialogs with the same title distinct ids', () => {
    render(
      <>
        <Modal open onClose={() => {}} title="Same">
          a
        </Modal>
        <Modal open onClose={() => {}} title="Same">
          b
        </Modal>
      </>,
    );
    const ids = screen.getAllByRole('dialog', { name: 'Same' }).map((d) => d.getAttribute('aria-labelledby'));
    expect(ids).toHaveLength(2);
    expect(new Set(ids).size).toBe(2);
  });
});

describe('Table', () => {
  it('is not an extra tab stop when it does not scroll', () => {
    render(
      <Table label="Orders">
        <tbody>
          <tr>
            <td>x</td>
          </tr>
        </tbody>
      </Table>,
    );
    // jsdom has no layout: scrollWidth equals clientWidth (0), so not scrollable.
    expect(screen.queryByRole('region')).toBeNull();
  });
});
