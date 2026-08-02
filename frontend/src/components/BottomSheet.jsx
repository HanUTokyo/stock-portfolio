import ResponsiveDialog from './ResponsiveDialog';

export default function BottomSheet({
  open,
  title,
  eyebrow,
  children,
  actions,
  onClose,
  fullHeight = false,
  className = ''
}) {
  return (
    <ResponsiveDialog
      open={open}
      title={title}
      eyebrow={eyebrow}
      actions={actions}
      onClose={onClose}
      fullHeight={fullHeight}
      presentation="sheet"
      className={['bottom-sheet', className].filter(Boolean).join(' ')}
    >
      {children}
    </ResponsiveDialog>
  );
}
