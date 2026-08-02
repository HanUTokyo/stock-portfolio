export default function PageToolbar({ eyebrow, title, description, actions, className = '' }) {
  return (
    <header className={`page-toolbar ${className}`.trim()}>
      <div className="page-toolbar-copy">
        {eyebrow ? <p className="eyebrow">{eyebrow}</p> : null}
        <h2>{title}</h2>
        {description ? <p>{description}</p> : null}
      </div>
      {actions ? <div className="page-toolbar-actions">{actions}</div> : null}
    </header>
  );
}
