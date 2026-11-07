import { safeNext } from '@/lib/safeNext';
import { zodResolver } from '@hookform/resolvers/zod';
import { Eye, EyeOff } from 'lucide-react';
import { useState } from 'react';
import { useForm } from 'react-hook-form';
import { Link, useNavigate, useSearchParams } from 'react-router-dom';
import { z } from 'zod';
import { Button, Field, InlineError, Input } from '@/components/ui';
import { useSession } from '@/session/SessionProvider';
import { AuthLayout } from './AuthLayout';

const schema = z.object({
  email: z.string().trim().min(1, 'Enter your email').email('Enter a valid email'),
  password: z.string().min(1, 'Enter your password'),
});
type Values = z.infer<typeof schema>;


export function SignInPage() {
  const { signIn } = useSession();
  const navigate = useNavigate();
  const [params] = useSearchParams();
  const [showPassword, setShowPassword] = useState(false);
  const [error, setError] = useState<unknown>(null);
  const { register, handleSubmit, formState } = useForm<Values>({ resolver: zodResolver(schema) });

  const onSubmit = handleSubmit(async (values) => {
    setError(null);
    try {
      await signIn(values.email, values.password);
      navigate(safeNext(params.get('next')), { replace: true });
    } catch (e) {
      setError(e);
    }
  });

  return (
    <AuthLayout
      title="Sign in"
      subtitle="Use your ATLAS account."
      footer={<>New here? <Link className="font-medium text-accent hover:underline" to="/register">Create an account</Link></>}
    >
      <form onSubmit={onSubmit} noValidate className="space-y-4">
        <Field label="Email" error={formState.errors.email?.message}>
          {(p) => <Input {...p} type="email" autoComplete="username" {...register('email')} />}
        </Field>
        <Field label="Password" error={formState.errors.password?.message}>
          {(p) => (
            <div className="relative">
              <Input {...p} type={showPassword ? 'text' : 'password'} autoComplete="current-password"
                className="pr-10" {...register('password')} />
              <button type="button" onClick={() => setShowPassword((v) => !v)}
                className="absolute inset-y-0 right-0 flex w-10 items-center justify-center text-muted hover:text-text"
                aria-label={showPassword ? 'Hide password' : 'Show password'}>
                {showPassword ? <EyeOff className="h-4 w-4" aria-hidden /> : <Eye className="h-4 w-4" aria-hidden />}
              </button>
            </div>
          )}
        </Field>
        <InlineError error={error} />
        <Button type="submit" variant="primary" className="w-full" loading={formState.isSubmitting}>
          Sign in
        </Button>
      </form>
    </AuthLayout>
  );
}
