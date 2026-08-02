export default function FormField({ label, hint, className = '', children }) {
  return (
    <label className={['form-field', className].filter(Boolean).join(' ')}>
      <span className="form-field-label">{label}</span>
      {children}
      {hint ? <span className="form-field-hint">{hint}</span> : null}
    </label>
  );
}
