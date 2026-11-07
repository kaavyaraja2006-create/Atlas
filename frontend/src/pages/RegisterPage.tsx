import { zodResolver } from '@hookform/resolvers/zod';
import { useState } from 'react';
import { useForm } from 'react-hook-form';
import { Link, useNavigate } from 'react-router-dom';
import { z } from 'zod';
import { auth } from '@/api/endpoints';
import { Button, Field, InlineError, Input } from '@/components/ui';
import { useSession } from '@/session/SessionProvider';
import { AuthLayout } from './AuthLayout';

/** Mirrors the server's PasswordPolicy for fast feedback; the server remains the authority. */
export const registerSchema = z
  .object({
    displayName: z.string().trim().min(1, 'Enter your name').max(200),
    email: z.string().trim().min(1, 'Enter your email').email('Enter a valid email').max(320),
    password: z
      .string()
      .refine((v) => [...v].length >= 12, 'Use at least 12 characters')
      .refine((v) => new TextEncoder().encode(v).length <= 72, 'Use at most 72 bytes'),
    confirm: z.string(),
  })
  .refine((v) => v.password === v.confirm, { path: ['confirm'], message: 'Passwords do not match' });
type Values = z.infer<typeof registerSchema>;

export function RegisterPage() {
  const { signIn } = useSession();
  const navigate = useNavigate();
  const [error, setError] = useState<unknown>(null);
  const { register, handleSubmit, formState } = useForm<Values>({ resolver: zodResolver(registerSchema) });

  const onSubmit = handleSubmit(async ({ displayName, email, password }) => {
    setError(null);
    try {
      await auth.register({ email, displayName, password });
      await signIn(email, password);
      navigate('/organizations', { replace: true });
    } catch (e) {
      setError(e);
    }
  });

  return (
    <AuthLayout
      title="Create your account"
      subtitle="You can create or join an organization next."
      footer={<>Already registered? <Link className="font-medium text-accent hover:underline" to="/sign-in">Sign in</Link></>}
    >
      <form onSubmit={onSubmit} noValidate className="space-y-4">
        <Field label="Name" error={formState.errors.displayName?.message}>
          {(p) => <Input {...p} autoComplete="name" {...register('displayName')} />}
        </Field>
        <Field label="Email" error={formState.errors.email?.message}>
          {(p) => <Input {...p} type="email" autoComplete="email" {...register('email')} />}
        </Field>
        <Field label="Password" error={formState.errors.password?.message} hint="At least 12 characters. A passphrase works well.">
          {(p) => <Input {...p} type="password" autoComplete="new-password" {...register('password')} />}
        </Field>
        <Field label="Confirm password" error={formState.errors.confirm?.message}>
          {(p) => <Input {...p} type="password" autoComplete="new-password" {...register('confirm')} />}
        </Field>
        <InlineError error={error} />
        <Button type="submit" variant="primary" className="w-full" loading={formState.isSubmitting}>
          Create account
        </Button>
      </form>
    </AuthLayout>
  );
}
