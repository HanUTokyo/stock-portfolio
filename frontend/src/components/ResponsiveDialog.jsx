import { useEffect, useRef } from 'react';
import { X } from 'lucide-react';
import useBodyScrollLock from '../hooks/useBodyScrollLock';
import useIsMobile from '../hooks/useIsMobile';

function focusableNodes(root) {
  if (!root) return [];
  return [...root.querySelectorAll(
    'a[href], button:not([disabled]), input:not([disabled]), select:not([disabled]), textarea:not([disabled]), [tabindex]:not([tabindex="-1"])'
  )].filter((node) => !node.hasAttribute('hidden'));
}

export default function ResponsiveDialog({
  open,
  title,
  eyebrow,
  children,
  actions,
  onClose,
  fullHeight = false,
  className = '',
  presentation = 'auto',
  size = 'md',
  closeLabel = 'Close',
  initialFocusSelector
}) {
  const isMobile = useIsMobile();
  const dialogRef = useRef(null);
  const closeRef = useRef(null);
  const onCloseRef = useRef(onClose);
  useBodyScrollLock(open);

  const isSheet = presentation === 'sheet' || (presentation === 'auto' && isMobile);

  useEffect(() => {
    onCloseRef.current = onClose;
  }, [onClose]);

  useEffect(() => {
    if (!open) return undefined;

    const previousActive = document.activeElement;
    const frame = window.requestAnimationFrame(() => closeRef.current?.focus());
    const onKeyDown = (event) => {
      if (event.key === 'Escape') {
        event.preventDefault();
        onCloseRef.current?.();
        return;
      }
      if (event.key !== 'Tab') return;

      const nodes = focusableNodes(dialogRef.current);
      if (!nodes.length) {
        event.preventDefault();
        return;
      }
      const first = nodes[0];
      const last = nodes[nodes.length - 1];
      if (event.shiftKey && document.activeElement === first) {
        event.preventDefault();
        last.focus();
      } else if (!event.shiftKey && document.activeElement === last) {
        event.preventDefault();
        first.focus();
      }
    };

    document.addEventListener('keydown', onKeyDown);
    return () => {
      window.cancelAnimationFrame(frame);
      document.removeEventListener('keydown', onKeyDown);
      previousActive?.focus?.();
    };
  }, [open]);

  useEffect(() => {
    if (!open || !initialFocusSelector) return undefined;

    const frame = window.requestAnimationFrame(() => {
      dialogRef.current?.querySelector(initialFocusSelector)?.focus();
    });
    return () => window.cancelAnimationFrame(frame);
  }, [open, initialFocusSelector]);

  if (!open) return null;

  return (
    <div
      className={`responsive-dialog-backdrop ${isSheet ? 'is-sheet' : 'is-modal'}`}
      role="presentation"
      onMouseDown={(event) => {
        if (event.target === event.currentTarget) onClose?.();
      }}
    >
      <section
        ref={dialogRef}
        className={[
          'responsive-dialog',
          isSheet ? 'responsive-dialog-sheet' : 'responsive-dialog-modal',
          fullHeight ? 'responsive-dialog-full' : '',
          `responsive-dialog-${size}`,
          className
        ].filter(Boolean).join(' ')}
        role="dialog"
        aria-modal="true"
        aria-label={title}
      >
        {isSheet ? <div className="responsive-dialog-grip" aria-hidden="true" /> : null}
        <header className="responsive-dialog-header">
          <div>
            {eyebrow ? <p className="eyebrow">{eyebrow}</p> : null}
            <h2>{title}</h2>
          </div>
          <button
            ref={closeRef}
            type="button"
            className="icon-button responsive-dialog-close"
            onClick={onClose}
            aria-label={closeLabel}
            title={closeLabel}
          >
            <X size={18} aria-hidden="true" />
          </button>
        </header>
        <div className="responsive-dialog-body">{children}</div>
        {actions ? <footer className="responsive-dialog-actions">{actions}</footer> : null}
      </section>
    </div>
  );
}
