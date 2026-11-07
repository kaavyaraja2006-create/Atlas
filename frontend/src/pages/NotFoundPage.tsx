import { Link } from 'react-router-dom';
import { EmptyState } from '@/components/ui';

export function NotFoundPage() {
  return (
    <EmptyState title="Page not found" body="This page does not exist, or it belongs to an organization you are not a member of."
      action={<Link to="/products" className="font-medium text-accent hover:underline">Go to the catalogue</Link>} />
  );
}
