export default function MobileActionBar({ actions = [], className = '' }) {
  if (!actions.length) {
    return null;
  }

  return (
    <div className={`mobile-action-bar ${className}`.trim()} aria-label="Page actions">
      {actions.map(({ key, label, icon: Icon, onClick, disabled, variant = 'primary' }) => (
        <button
          key={key || label}
          type="button"
          className={`mobile-action-button mobile-action-${variant}`}
          onClick={onClick}
          disabled={disabled}
        >
          {Icon ? <Icon size={18} aria-hidden="true" /> : null}
          <span>{label}</span>
        </button>
      ))}
    </div>
  );
}
