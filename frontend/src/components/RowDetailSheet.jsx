import ResponsiveDialog from './ResponsiveDialog';

export default function RowDetailSheet({ open, title, eyebrow, children, actions, onClose, fullHeight = false, initialFocusSelector }) {
  return (
    <ResponsiveDialog
      open={open}
      title={title}
      eyebrow={eyebrow}
      actions={actions}
      onClose={onClose}
      fullHeight={fullHeight}
      className="row-detail-sheet"
      initialFocusSelector={initialFocusSelector}
    >
      {children}
    </ResponsiveDialog>
  );
}
