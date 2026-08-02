import { AlertCircle, CheckCircle2, LoaderCircle } from 'lucide-react';

export default function StatusToast({ error, message, loading }) {
  if (!error && !message && !loading) {
    return null;
  }

  const tone = error ? 'error' : message ? 'info' : 'loading';
  const text = error || message || 'Loading dashboard...';
  const Icon = error ? AlertCircle : message ? CheckCircle2 : LoaderCircle;

  return (
    <div className={`status-toast ${tone}`} role={error ? 'alert' : 'status'} aria-live="polite">
      <Icon size={18} className={loading && !error && !message ? 'spin-icon' : ''} aria-hidden="true" />
      <span>{text}</span>
    </div>
  );
}
