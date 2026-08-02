import ResponsiveDialog from './ResponsiveDialog';

export default function ConfirmDialog({
  open,
  title,
  description,
  confirmLabel = 'Confirm',
  cancelLabel = 'Cancel',
  tone = 'danger',
  pending = false,
  onConfirm,
  onClose
}) {
  return (
    <ResponsiveDialog
      open={open}
      title={title}
      onClose={onClose}
      size="sm"
      className="confirm-dialog"
      actions={(
        <>
          <button type="button" className="secondary-button" onClick={onClose} disabled={pending}>{cancelLabel}</button>
          <button type="button" className={tone === 'danger' ? 'row-danger-btn' : ''} onClick={onConfirm} disabled={pending}>
            {pending ? 'Working...' : confirmLabel}
          </button>
        </>
      )}
    >
      <p className="confirm-dialog-copy">{description}</p>
    </ResponsiveDialog>
  );
}
