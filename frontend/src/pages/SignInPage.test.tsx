import { screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { ApiError } from '@/api/client';
import { renderWithProviders } from '@/test/render';
import { SignInPage } from './SignInPage';

const signIn = vi.fn();
vi.mock('@/session/SessionProvider', () => ({ useSession: () => ({ signIn }) }));

describe('SignInPage', () => {
  beforeEach(() => signIn.mockReset());

  it('shows the server message on failure and never echoes the password', async () => {
    signIn.mockRejectedValueOnce(new ApiError({
      status: 401, title: 'Unauthorized', detail: 'Invalid email or password, or the account is temporarily locked.',
    }));
    renderWithProviders(<SignInPage />);
    await userEvent.type(screen.getByLabelText('Email'), 'k@atlas.test');
    await userEvent.type(screen.getByLabelText('Password'), 'not-my-password');
    await userEvent.click(screen.getByRole('button', { name: 'Sign in' }));
    expect(await screen.findByRole('alert')).toHaveTextContent('Invalid email or password, or the account is temporarily locked.');
    expect(signIn).toHaveBeenCalledWith('k@atlas.test', 'not-my-password');
    expect(screen.getByLabelText('Password')).toHaveAttribute('type', 'password');
  });

  it('validates before calling the server', async () => {
    renderWithProviders(<SignInPage />);
    await userEvent.click(screen.getByRole('button', { name: 'Sign in' }));
    expect(await screen.findByText('Enter your email')).toBeInTheDocument();
    expect(signIn).not.toHaveBeenCalled();
  });
});
